use songbird::driver::opus::{Channels, SampleRate};

pub const OPUS_SAMPLE_RATE: SampleRate = SampleRate::Hz48000;
pub const OPUS_CHANNELS: Channels = Channels::Mono;

pub const SAMPLE_RATE: u32 = OPUS_SAMPLE_RATE as i32 as u32;
pub const CHANNELS: u32 = OPUS_CHANNELS as i32 as u32;

pub const MAX_AUDIO_BUFFER: usize = 128;
