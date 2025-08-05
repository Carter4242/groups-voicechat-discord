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
        let mut all_samples = Vec::new();
        let mut total_buffered = 0;
        for entry in self.mc_to_discord_buffers.iter() {
            let buffer_rx = &entry.value().pcm_buffer_rx;
            let buffered = buffer_rx.len();
            total_buffered += buffered;
            info!(group=?entry.key(), "PCM buffer occupancy: {} packets", buffered);
            if let Ok(samples) = buffer_rx.try_recv() {
                info!(group=?entry.key(), "Read {} samples from group buffer", samples.len());
                all_samples.push(samples);
            }
        }
        info!("Total PCM packets buffered across all groups: {}", total_buffered);
        let combined = crate::audio_util::combine_audio_parts(all_samples);

        let mut written = 0;
        for sample in combined.iter() {
            let converted = (*sample as f32) / (i16::MAX as f32);
            written += buf.write(&converted.to_le_bytes())?;
        }
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