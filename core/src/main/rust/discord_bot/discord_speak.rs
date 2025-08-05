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
use crate::discord_bot::GroupAudioBuffer;

#[inline]
/// Only use mc_to_discord_buffers for Minecraft->Discord audio
pub fn create_playable_input(mc_to_discord_buffers: Arc<dashmap::DashMap<Uuid, GroupAudioBuffer>>) -> Result<Input, Report> {
    let audio_source = GroupAudioSource { mc_to_discord_buffers };
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

struct GroupAudioSource {
    mc_to_discord_buffers: Arc<dashmap::DashMap<Uuid, GroupAudioBuffer>>,
}

impl io::Read for GroupAudioSource {
    fn read(&mut self, mut buf: &mut [u8]) -> io::Result<usize> {
        use tracing::info;
        use std::time::{SystemTime, UNIX_EPOCH};
        use std::collections::VecDeque;
        use std::sync::Mutex;
        use once_cell::sync::Lazy;
        const JITTER_BUFFER_SIZE: usize = 40;
        static JITTER_BUFFER: Lazy<Mutex<VecDeque<[i16; 960]>>> = Lazy::new(|| Mutex::new(VecDeque::with_capacity(JITTER_BUFFER_SIZE)));

        let mut jitter_buffer = JITTER_BUFFER.lock().unwrap();

        // Block to fill to JITTER_BUFFER_SIZE only at startup or when buffer drops to zero
        let mut refill_attempts = 0;
        if jitter_buffer.len() == 0 {
            //info!("[JitterBuffer] Buffer is empty, blocking to refill to {} packets", JITTER_BUFFER_SIZE);
            while jitter_buffer.len() < JITTER_BUFFER_SIZE {
                let mut filled = false;
                for entry in self.mc_to_discord_buffers.iter() {
                    let buffer_rx = &entry.value().pcm_buffer_rx;
                    let buffered = buffer_rx.len();
                    //info!(group=?entry.key(), "[JitterBuffer] PCM buffer occupancy: {} packets (refilling, have {})", buffered, jitter_buffer.len());
                    match buffer_rx.try_recv() {
                        Ok(samples) => {
                            jitter_buffer.push_back(samples);
                            filled = true;
                            //info!(group=?entry.key(), "[JitterBuffer] Added packet to jitter buffer (now {})", jitter_buffer.len());
                        },
                        Err(flume::TryRecvError::Empty) => {},
                        Err(flume::TryRecvError::Disconnected) => {
                            info!(group=?entry.key(), "PCM buffer disconnected");
                        }
                    }
                }
                if !filled {
                    std::thread::sleep(std::time::Duration::from_millis(2));
                    refill_attempts += 1;
                    if refill_attempts % 50 == 0 {
                        info!("[JitterBuffer] Still waiting to refill to {} packets (waited {}ms)", JITTER_BUFFER_SIZE, refill_attempts * 2);
                    }
                }
            }
        }

        // Pop one packet and send (if buffer is not empty)
        let samples = match jitter_buffer.pop_front() {
            Some(samples) => {
                //info!("[JitterBuffer] Sent packet, jitter buffer now has {} packets", jitter_buffer.len());
                samples
            },
            None => {
                // Buffer is empty, block and refill to JITTER_BUFFER_SIZE
                //info!("[JitterBuffer] Buffer empty during send, blocking to refill to {} packets", JITTER_BUFFER_SIZE);
                refill_attempts = 0;
                while jitter_buffer.len() < JITTER_BUFFER_SIZE {
                    let mut filled = false;
                    for entry in self.mc_to_discord_buffers.iter() {
                        let buffer_rx = &entry.value().pcm_buffer_rx;
                        let buffered = buffer_rx.len();
                        //info!(group=?entry.key(), "[JitterBuffer] PCM buffer occupancy: {} packets (refilling, have {})", buffered, jitter_buffer.len());
                        match buffer_rx.try_recv() {
                            Ok(samples) => {
                                jitter_buffer.push_back(samples);
                                filled = true;
                                //info!(group=?entry.key(), "[JitterBuffer] Added packet to jitter buffer (now {})", jitter_buffer.len());
                            },
                            Err(flume::TryRecvError::Empty) => {},
                            Err(flume::TryRecvError::Disconnected) => {
                                info!(group=?entry.key(), "PCM buffer disconnected");
                            }
                        }
                    }
                    if !filled {
                        std::thread::sleep(std::time::Duration::from_millis(2));
                        refill_attempts += 1;
                        if refill_attempts % 50 == 0 {
                            info!("[JitterBuffer] Still waiting to refill to {} packets (waited {}ms)", JITTER_BUFFER_SIZE, refill_attempts * 2);
                        }
                    }
                }
                let samples = jitter_buffer.pop_front().expect("Jitter buffer should have at least one packet after refill");
                //info!("[JitterBuffer] Sent packet after forced refill, jitter buffer now has {} packets", jitter_buffer.len());
                samples
            }
        };

        // Try to refill buffer (non-blocking)
        for entry in self.mc_to_discord_buffers.iter() {
            let buffer_rx = &entry.value().pcm_buffer_rx;
            match buffer_rx.try_recv() {
                Ok(samples) => {
                    jitter_buffer.push_back(samples);
                    //info!(group=?entry.key(), "[JitterBuffer] Refilled packet after send (now {})", jitter_buffer.len());
                },
                _ => {}
            }
        }

        // Log first and last 5 frames of the packet
        info!("Sent packet first 5 frames: {:?}", &samples[..5]);
        info!("Sent packet last 5 frames: {:?}", &samples[samples.len()-5..]);

        // Write: output as f32 PCM bytes (Discord expects f32 samples)
        let mut written = 0;
        for sample in samples.iter() {
            let converted = (*sample as f32) / (i16::MAX as f32);
            written += buf.write(&converted.to_le_bytes())?;
        }
        let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap();
        info!(
            "GroupAudioSource::read called: requested_buf_size={} written_size={} timestamp={:?}",
            buf.len(),
            written,
            now
        );
        Ok(written)
    }
}

impl MediaSource for GroupAudioSource {
    #[inline]
    fn is_seekable(&self) -> bool {
        false
    }

    #[inline]
    fn byte_len(&self) -> Option<u64> {
        None
    }
}

impl io::Seek for GroupAudioSource {
    fn seek(&mut self, _pos: io::SeekFrom) -> io::Result<u64> {
        Err(io::ErrorKind::Unsupported.into())
    }
}