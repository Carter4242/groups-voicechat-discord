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
pub fn create_playable_input(group_buffers: Arc<dashmap::DashMap<Uuid, GroupAudioBuffer>>) -> Result<Input, Report> {
    let audio_source = GroupAudioSource { group_buffers };
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
    group_buffers: Arc<dashmap::DashMap<Uuid, GroupAudioBuffer>>,
}

impl io::Read for GroupAudioSource {
    fn read(&mut self, mut buf: &mut [u8]) -> io::Result<usize> {
        // For now, just use the first group buffer if present
        let mut written = 0;
        if let Some(entry) = self.group_buffers.iter().next() {
            let buffer_rx = &entry.value().buffer_rx;
            if let Ok(samples) = buffer_rx.try_recv() {
                for sample in samples {
                    let converted = (sample as f32) / (i16::MIN as f32).abs();
                    written += buf.write(&converted.to_le_bytes())?;
                }
            }
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