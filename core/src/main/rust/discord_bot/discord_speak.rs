use std::{
    io::{self, Write},
    sync::Arc,
};

use uuid::Uuid;
use eyre::{Context, Report};
use songbird::input::{
    codecs::{CODEC_REGISTRY, PROBE},
    core::io::MediaSource,
    Input, RawAdapter,
};

use crate::audio_util::{CHANNELS, SAMPLE_RATE};
use crate::audio_util::{OPUS_SAMPLE_RATE, OPUS_CHANNELS};
use crate::discord_bot::PlayerToDiscordBuffer;
use crate::discord_bot::playout_buffer::PacketLookup;
use songbird::driver::opus::coder::Decoder as OpusDecoder;
use std::collections::HashMap;
use std::fs::File;
use hound;
use std::sync::Mutex;

#[inline]


pub fn create_playable_input(player_to_discord_buffers: Arc<dashmap::DashMap<Uuid, PlayerToDiscordBuffer>>) -> Result<Input, Report> {
    let wav_writers = HashMap::new();
    let combined_writer = Some(hound::WavWriter::create("combined_output.wav", hound::WavSpec {
        channels: 1,
        sample_rate: SAMPLE_RATE,
        bits_per_sample: 16,
        sample_format: hound::SampleFormat::Int,
    })?);
    let audio_source = PlayerAudioSource {
        player_to_discord_buffers,
        next_frame_time: None,
        last_frame_sent: None,
        //packet_timestamps: std::collections::VecDeque::with_capacity(100),
        wav_writers,
        combined_writer,
        prev_zero: false,
        opus_decoders: Mutex::new(HashMap::new()),
    };
    let input: Input = RawAdapter::new(audio_source, SAMPLE_RATE, CHANNELS).into();
    let input = match input {
        Input::Live(i, _) => i,
        _ => unreachable!("From<RawAdapter> for Input always gives Input::Live"),
    };
    let parsed = input
        .promote(&CODEC_REGISTRY, &PROBE)
        .wrap_err("Unable to promote input")?;
    Ok(Input::Live(parsed, None))
}


struct PlayerAudioSource {
    player_to_discord_buffers: Arc<dashmap::DashMap<Uuid, PlayerToDiscordBuffer>>,
    next_frame_time: Option<std::time::Instant>,
    last_frame_sent: Option<std::time::Instant>,
    //packet_timestamps: std::collections::VecDeque<std::time::Instant>,
    wav_writers: HashMap<Uuid, hound::WavWriter<std::io::BufWriter<File>>>,
    combined_writer: Option<hound::WavWriter<std::io::BufWriter<File>>>,
    prev_zero: bool,
    opus_decoders: Mutex<HashMap<Uuid, OpusDecoder>>,
}


impl io::Read for PlayerAudioSource {
    fn read(&mut self, mut buf: &mut [u8]) -> io::Result<usize> {
        use tracing::info;

        let now = std::time::Instant::now();
        const FRAME_DURATION: std::time::Duration = std::time::Duration::from_millis(20);
        let frame_samples = 960; // 20ms @ 48kHz mono
        let sample_bytes = std::mem::size_of::<f32>();
        let frame_bytes = frame_samples * sample_bytes;

        let next_time = self.next_frame_time.unwrap_or(now);
        if next_time > now {
            let sleep_time = next_time - now;
            tracing::info!("PlayerAudioSource::read: sleeping for {:?} until next frame time", sleep_time);
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

        tracing::info!(
            "PlayerAudioSource::read: requested {} frames ({}ms elapsed), buffer can fit {}, will process {} frames",
            frames_to_catch_up,
            elapsed.as_millis(),
            max_frames,
            frames
        );

        self.next_frame_time = Some(next_time + FRAME_DURATION * (frames as u32));

        loop {
            if self.prev_zero {
                frames = 1;
                self.prev_zero = false;
                self.next_frame_time = Some(std::time::Instant::now() + FRAME_DURATION);
            }
            let mut written = 0;
            let mut frames_returned = 0;
            let mut frames_skipped = 0;
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
                                    info!(player=?uuid, seq=pkt.seq, "Decoded opus packet");
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
                                    info!(player=?uuid, "PLC generated frame");
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
                    frames_skipped += 1;
                    continue;
                }
                frames_returned += 1;

                // Write each user's PCM to a separate wav file
                for (uuid, pcm) in &user_pcm {
                    if !self.wav_writers.contains_key(uuid) {
                        let fname = format!("user_{}.wav", uuid);
                        tracing::info!("Creating WAV writer for user {}", uuid);
                        let writer = hound::WavWriter::create(fname, hound::WavSpec {
                            channels: 1,
                            sample_rate: SAMPLE_RATE,
                            bits_per_sample: 16,
                            sample_format: hound::SampleFormat::Int,
                        }).unwrap();
                        self.wav_writers.insert(*uuid, writer);
                    }
                    if let Some(writer) = self.wav_writers.get_mut(uuid) {
                        for sample in pcm.iter() {
                            writer.write_sample(*sample).ok();
                        }
                    }
                }

                let combined = crate::audio_util::combine_audio_parts(all_samples);

                if let Some(writer) = self.combined_writer.as_mut() {
                    for sample in combined.iter() {
                        writer.write_sample(*sample).ok();
                    }
                }

                for sample in combined.iter() {
                    let converted = (*sample as f32) / (i16::MAX as f32);
                    written += buf.write(&converted.to_le_bytes())?;
                }
            }
            tracing::info!("PlayerAudioSource::read: returned {} frames, skipped {} frames (all missing)", frames_returned, frames_skipped);
            if written == 0 {
                // If we just failed to send frames, only return 1 frame next time
                self.prev_zero = true;
                std::thread::sleep(std::time::Duration::from_millis(2));
                continue;
            }
            // Log actual time since last frame sent
            let now = std::time::Instant::now();
            let elapsed = if let Some(last) = self.last_frame_sent {
                now.duration_since(last)
            } else {
                std::time::Duration::from_secs(0)
            };
            if elapsed > std::time::Duration::from_millis(30) {
                tracing::info!(?elapsed, "Actual time since last frame sent (over 30ms!)");
            } else {
                tracing::info!(?elapsed, "Actual time since last frame sent");
            }
            self.last_frame_sent = Some(now);
            self.prev_zero = false;
            return Ok(written);
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