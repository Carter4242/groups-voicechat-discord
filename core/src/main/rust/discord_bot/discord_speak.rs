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
use crate::discord_bot::PlayerToDiscordBuffer;
use crate::discord_bot::playout_buffer::PacketLookup;
use std::collections::HashMap;
use std::fs::File;
use hound;

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
        packet_timestamps: std::collections::VecDeque::with_capacity(100),
        wav_writers,
        combined_writer,
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
    packet_timestamps: std::collections::VecDeque<std::time::Instant>,
    wav_writers: HashMap<Uuid, hound::WavWriter<std::io::BufWriter<File>>>,
    combined_writer: Option<hound::WavWriter<std::io::BufWriter<File>>>,
}


impl io::Read for PlayerAudioSource {
    fn read(&mut self, mut buf: &mut [u8]) -> io::Result<usize> {
        use tracing::info;
        info!("PlayerAudioSource::read called");
        loop {
            let now = std::time::Instant::now();
            const FRAME_DURATION: std::time::Duration = std::time::Duration::from_millis(20);
            if self.next_frame_time.is_none() {
                self.next_frame_time = Some(now);
            }
            let mut next_time = self.next_frame_time.unwrap();
            while now < next_time {
                let sleep_time = next_time - now;
                info!(?sleep_time, "PlayerAudioSource::read sleeping for pacing");
                std::thread::sleep(sleep_time);
                let now2 = std::time::Instant::now();
                if now2 >= next_time {
                    break;
                }
            }
            let now2 = std::time::Instant::now();
            if next_time <= now2 {
                let missed = ((now2.duration_since(next_time).as_millis() / FRAME_DURATION.as_millis()) + 1) as u32;
                next_time += FRAME_DURATION * missed;
            }
            self.next_frame_time = Some(next_time);

            let mut all_samples = Vec::new();
            let mut got_data = false;
            let mut packets_attempted = 0;
            let mut packets_sent = 0;
            let mut user_pcm: HashMap<Uuid, [i16; 960]> = HashMap::new();
            // For time-aligned mixing, always iterate over all users
            for entry in self.player_to_discord_buffers.iter() {
                let buffer = &entry.value();
                let mut playout = buffer.playout_buffer.lock().unwrap();
                packets_attempted += 1;
                match playout.fetch_packet() {
                    PacketLookup::Packet(pkt) => {
                        info!(player=?entry.key(), seq=pkt.seq, "Read {} samples from playout buffer", pkt.pcm.len());
                        all_samples.push(pkt.pcm);
                        user_pcm.insert(*entry.key(), pkt.pcm);
                        got_data = true;
                        packets_sent += 1;
                    }
                    PacketLookup::MissedPacket => {
                        // Insert silence for missed packet
                        user_pcm.insert(*entry.key(), [0; 960]);
                        all_samples.push([0; 960]);
                    }
                    PacketLookup::Filling => {
                        // Insert silence for filling
                        user_pcm.insert(*entry.key(), [0; 960]);
                        all_samples.push([0; 960]);
                    }
                }
            }
            // Write each user's PCM to a separate wav file
            for (uuid, pcm) in &user_pcm {
                if !self.wav_writers.contains_key(uuid) {
                    let fname = format!("user_{}.wav", uuid);
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
            if got_data {
                let elapsed = if let Some(last) = self.last_frame_sent {
                    now.duration_since(last)
                } else {
                    std::time::Duration::from_secs(0)
                };
                info!(?elapsed, "Actual time since last frame sent");
                self.last_frame_sent = Some(now);
                let window = std::time::Duration::from_secs(2);
                self.packet_timestamps.push_back(now);
                while let Some(&front) = self.packet_timestamps.front() {
                    if now.duration_since(front) > window {
                        self.packet_timestamps.pop_front();
                    } else {
                        break;
                    }
                }
                let avg_pps = self.packet_timestamps.len() as f64 / window.as_secs_f64();
                info!(avg_pps, "Average packets/sec through read() (rolling 2s window)");
                info!(packets_sent, packets_attempted, "Sending {} PCM packets to Discord (attempted: {})", packets_sent, packets_attempted);
                let combined = crate::audio_util::combine_audio_parts(all_samples);
                if let Some(writer) = self.combined_writer.as_mut() {
                    for sample in combined.iter() {
                        writer.write_sample(*sample).ok();
                    }
                }
                let mut written = 0;
                for sample in combined.iter() {
                    let converted = (*sample as f32) / (i16::MAX as f32);
                    written += buf.write(&converted.to_le_bytes())?;
                }
                return Ok(written);
            } else {
                std::thread::sleep(std::time::Duration::from_millis(2));
            }
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