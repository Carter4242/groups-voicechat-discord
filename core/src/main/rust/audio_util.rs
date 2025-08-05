use songbird::driver::opus::{Channels, SampleRate};
use tracing::{warn};

pub const OPUS_SAMPLE_RATE: SampleRate = SampleRate::Hz48000;
pub const OPUS_CHANNELS: Channels = Channels::Mono;

pub const SAMPLE_RATE: u32 = OPUS_SAMPLE_RATE as i32 as u32;
pub const CHANNELS: u32 = OPUS_CHANNELS as i32 as u32;

pub const MAX_AUDIO_BUFFER: usize = 50;
pub const RAW_AUDIO_SIZE: usize = 960;

/// 20 ms of 16-bit PCM
pub type RawAudio = [i16; RAW_AUDIO_SIZE];

pub fn combine_audio_parts(parts: Vec<RawAudio>) -> RawAudio {
    // Based on https://github.com/DV8FromTheWorld/JDA/blob/11c5bf02a1f4df3372ab68e0ccb4a94d0db368df/src/main/java/net/dv8tion/jda/internal/audio/AudioConnection.java#L529
    use tracing::info;
    //info!("combine_audio_parts called: {} input parts", parts.len());
    for (i, part) in parts.iter().enumerate() {
        info!("part {} length: {} samples ({} ms)", i, part.len(), (part.len() as f32 / 48_000.0 * 1000.0));
        //info!("part {} first 10 samples: {:?}", i, &part[..10.min(part.len())]);
    }
    let Some(max_length) = parts.iter().map(|p| p.len()).max() else {
        //info!("combine_audio_parts: no input parts, returning silence");
        return [0; RAW_AUDIO_SIZE];
    };
    //info!("combine_audio_parts: max_length={} samples ({} ms)", max_length, (max_length as f32 / 48_000.0 * 1000.0));
    let mut mixed = [0; RAW_AUDIO_SIZE];
    let mut sample: i32;
    for i in 0..max_length {
        if i >= mixed.len() {
            warn!(len = mixed.len(), "Audio parts are bigger than 20ms! Some audio may be lost. Please report to GitHub Issues!");
            break;
        }
        sample = 0;
        for part in &parts {
            // We don't need to check part.len() against i, because,
            // unlike Java, we have a guarantee part is of length
            // RAW_AUDIO_SIZE and we already checked that i isn't above that 😎
            sample += part[i] as i32;
        }
        if sample > i16::MAX as i32 {
            mixed[i] = i16::MAX;
        } else if sample < i16::MIN as i32 {
            mixed[i] = i16::MIN;
        } else {
            mixed[i] = sample as i16
        }
    }
    mixed
}