use std::{
    io::{self},
    sync::Arc,
    sync::atomic::{AtomicBool, Ordering},
};

use uuid::Uuid;
use eyre::{Context, Report};
use songbird::input::{
    codecs::{get_codec_registry, get_probe},
    core::io::MediaSource,
    Input, RawAdapter,
};

use crate::audio_util::{CHANNELS, SAMPLE_RATE};
use crate::audio_util::{OPUS_SAMPLE_RATE, OPUS_CHANNELS};
use once_cell::sync::Lazy;
use crate::discord_bot::PlayerToDiscordBuffer;
use crate::discord_bot::playout_buffer::PacketLookup;
use songbird::driver::opus::coder::Decoder as OpusDecoder;
use std::collections::HashMap;
use std::sync::Mutex;


#[inline]
pub fn create_playable_input(
    player_to_discord_buffers: Arc<dashmap::DashMap<Uuid, PlayerToDiscordBuffer>>,
    shutdown: Arc<AtomicBool>,
) -> Result<(Input, Uuid), Report> {
    let should_send_silence = Arc::new(AtomicBool::new(false));
    let audio_source_id = Uuid::new_v4();
    let audio_source = PlayerAudioSource {
        player_to_discord_buffers,
        next_frame_time: None,
        last_frame_sent: None,
        prev_zero: false,
        opus_decoders: Mutex::new(HashMap::new()),
        shutdown,
        should_send_silence: should_send_silence.clone(),
        silent_countdown: 0,
        leftover: Vec::new(),
        _id: audio_source_id,
    };
    // Register this audio source globally
    AUDIO_SOURCE_REGISTRY.insert(audio_source_id, should_send_silence);
    let input: Input = RawAdapter::new(audio_source, SAMPLE_RATE, CHANNELS).into();
    let input = match input {
        Input::Live(i, _) => i,
        _ => unreachable!("From<RawAdapter> for Input always gives Input::Live"),
    };
    let parsed = input
        .promote(get_codec_registry(), get_probe())
        .wrap_err("Unable to promote input")?;
    Ok((Input::Live(parsed, None), audio_source_id))
}


struct PlayerAudioSource {
    player_to_discord_buffers: Arc<dashmap::DashMap<Uuid, PlayerToDiscordBuffer>>,
    next_frame_time: Option<std::time::Instant>,
    last_frame_sent: Option<std::time::Instant>,
    prev_zero: bool,
    opus_decoders: Mutex<HashMap<Uuid, OpusDecoder>>,
    shutdown: Arc<AtomicBool>,
    should_send_silence: Arc<AtomicBool>,
    silent_countdown: u8,
    leftover: Vec<u8>,
    _id: Uuid,
}

// Global registry of all PlayerAudioSource's should_send_silence flags
static AUDIO_SOURCE_REGISTRY: Lazy<dashmap::DashMap<Uuid, Arc<AtomicBool>>> = Lazy::new(|| dashmap::DashMap::new());

/// Remove an audio source from the registry by UUID
pub fn remove_audio_source(uuid: &Uuid) {
    AUDIO_SOURCE_REGISTRY.remove(uuid);
}

/// Call this when a new bot starts to poke all other bots to send a single silent frame if stuck
pub fn poke_all_audio_sources() {
    for entry in AUDIO_SOURCE_REGISTRY.iter() {
        entry.value().store(true, Ordering::SeqCst);
    }
}


impl io::Read for PlayerAudioSource {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        tracing::info!(
            "PlayerAudioSource::read: shutdown={}, buf_size={}, leftover_len={}",
            self.shutdown.load(Ordering::SeqCst), buf.len(), self.leftover.len()
        );
        if self.shutdown.load(Ordering::SeqCst) {
            tracing::info!("PlayerAudioSource::read: returning early due to shutdown, bytes=0");
            return Ok(0);
        }

        // Serve leftover bytes first
        if !self.leftover.is_empty() {
            let to_copy = std::cmp::min(buf.len(), self.leftover.len());
            buf[..to_copy].copy_from_slice(&self.leftover[..to_copy]);
            if to_copy < self.leftover.len() {
                self.leftover = self.leftover[to_copy..].to_vec();
            } else {
                self.leftover.clear();
            }
            tracing::info!("PlayerAudioSource::read: returning leftover bytes, bytes={}", to_copy);
            return Ok(to_copy);
        }

        let now = std::time::Instant::now();
        const FRAME_DURATION: std::time::Duration = std::time::Duration::from_millis(20);
        let frame_samples = 960; // 20ms @ 48kHz mono
        let sample_bytes = std::mem::size_of::<f32>();
        let frame_bytes = frame_samples * sample_bytes;

        let next_time = self.next_frame_time.unwrap_or(now);
        if next_time > now {
            let sleep_time = next_time - now;
            if self.shutdown.load(Ordering::SeqCst) {
                return Ok(0);
            }
            std::thread::sleep(sleep_time);
        }
        let now = std::time::Instant::now();
        let elapsed = now.duration_since(next_time);
        let mut frames_to_catch_up = 1;
        if elapsed > FRAME_DURATION {
            frames_to_catch_up = (elapsed.as_millis() / FRAME_DURATION.as_millis()) as usize + 1;
        }

        // How many frames fit in the buffer?
        let max_frames = buf.len() / frame_bytes;
        // Always process at least one frame
        let mut frames = std::cmp::min(frames_to_catch_up.max(1), max_frames.max(1));

        // If we previously had a gap (all missing), only return 1 frame this time
        if self.prev_zero {
            frames = 1;
            self.prev_zero = false;
        }

        self.next_frame_time = Some(next_time + FRAME_DURATION * (frames as u32));

        loop {
            if self.shutdown.load(Ordering::SeqCst) {
                tracing::info!("PlayerAudioSource::read: returning early due to shutdown in loop, bytes=0");
                return Ok(0);
            }
            if self.prev_zero {
                frames = 1;
                self.prev_zero = false;
                self.next_frame_time = Some(std::time::Instant::now() + FRAME_DURATION);
            }
            let mut temp = Vec::new();
            let mut _frames_returned = 0;
            let mut _frames_skipped = 0;
            for _ in 0..frames {
                let mut all_samples = Vec::new();
                let mut user_pcm: HashMap<Uuid, [i16; 960]> = HashMap::new();
                let mut any_real_audio = false;
                for entry in self.player_to_discord_buffers.iter() {
                    let uuid = *entry.key();
                    let buffer = &entry.value();
                    let mut playout = buffer.playout_buffer.lock().unwrap();
                    let mut decoders = self.opus_decoders.lock().unwrap();
                    let decoder = decoders.entry(uuid).or_insert_with(|| {
                        OpusDecoder::new(
                            OPUS_SAMPLE_RATE,
                            OPUS_CHANNELS,
                        ).expect("Failed to create Opus decoder")
                    });
                    let mut pcm = [0i16; 960];
                    match playout.fetch_packet() {
                        PacketLookup::Packet(pkt) => {
                            // Decode Opus packet
                            let packet = match (&pkt.opus).as_slice().try_into() {
                                Ok(p) => p,
                                Err(_) => {
                                    tracing::error!("Invalid opus data for user {:?}", uuid);
                                    user_pcm.insert(uuid, [0; 960]);
                                    all_samples.push([0; 960]);
                                    continue;
                                }
                            };
                            let output = (&mut pcm[..]).try_into().unwrap();
                            match decoder.decode(Some(packet), output, false) {
                                Ok(_) => {
                                    user_pcm.insert(uuid, pcm);
                                    all_samples.push(pcm);
                                    // Check if PCM is not all zero
                                    if !pcm.iter().all(|&s| s == 0) {
                                        any_real_audio = true;
                                    }
                                }
                                Err(e) => {
                                    tracing::error!(?e, "Opus decode failed for user {:?}", uuid);
                                    user_pcm.insert(uuid, [0; 960]);
                                    all_samples.push([0; 960]);
                                }
                            }
                        }
                        PacketLookup::MissedPacket => {
                            // PLC: decode None
                            let output = (&mut pcm[..]).try_into().unwrap();
                            match decoder.decode(None, output, false) {
                                Ok(_) => {
                                    user_pcm.insert(uuid, pcm);
                                    all_samples.push(pcm);
                                    // Check if PLC output is not all zero
                                    if !pcm.iter().all(|&s| s == 0) {
                                        any_real_audio = true;
                                    }
                                }
                                Err(e) => {
                                    tracing::error!(?e, "Opus PLC failed for user {:?}", uuid);
                                    user_pcm.insert(uuid, [0; 960]);
                                    all_samples.push([0; 960]);
                                }
                            }
                        }
                        PacketLookup::Filling => {
                        }
                    }
                }
                if !any_real_audio {
                    // All users missing/filling, skip this frame
                    _frames_skipped += 1;
                    continue;
                }
                _frames_returned += 1;

                let combined = crate::audio_util::combine_audio_parts(all_samples);

                for sample in combined.iter() {
                    let converted = (*sample as f32) / (i16::MAX as f32);
                    temp.extend_from_slice(&converted.to_le_bytes());
                }
            }
            if temp.is_empty() {
                // If we just failed to send frames, only return 1 frame next time
                // If we've been poked, set the silent_countdown to 3
                if self.should_send_silence.swap(false, Ordering::SeqCst) {
                    self.silent_countdown = 3;
                }
                // If countdown is active, send a silent frame and decrement
                if self.silent_countdown > 0 {
                    let silent_frame = [0f32; 960];
                    for sample in silent_frame.iter() {
                        temp.extend_from_slice(&sample.to_le_bytes());
                    }
                    self.silent_countdown -= 1;
                    let to_copy = std::cmp::min(buf.len(), temp.len());
                    buf[..to_copy].copy_from_slice(&temp[..to_copy]);
                    tracing::info!("PlayerAudioSource::read: returning silent frame, bytes={}", to_copy);
                    return Ok(to_copy);
                }
                self.prev_zero = true;
                std::thread::sleep(std::time::Duration::from_millis(2));
                tracing::info!("PlayerAudioSource::read: restarting after failed frame, bytes=0");
                continue;
            }
            // Log actual time since last frame sent
            let now = std::time::Instant::now();
            self.last_frame_sent = Some(now);
            self.prev_zero = false;
            let to_copy: usize = std::cmp::min(buf.len(), temp.len());
            buf[..to_copy].copy_from_slice(&temp[..to_copy]);
            if to_copy < temp.len() {
                self.leftover = temp[to_copy..].to_vec();
            } else {
                self.leftover.clear();
            }
            tracing::info!("PlayerAudioSource::read: returning with audio, bytes={}", to_copy);
            return Ok(to_copy);
        }
    }
}

impl MediaSource for PlayerAudioSource {
    #[inline]
    fn is_seekable(&self) -> bool {
        false
    }

    #[inline]
    fn byte_len(&self) -> Option<u64> {
        None
    }
}

impl io::Seek for PlayerAudioSource {
    fn seek(&mut self, _pos: io::SeekFrom) -> io::Result<u64> {
        Err(io::ErrorKind::Unsupported.into())
    }
}