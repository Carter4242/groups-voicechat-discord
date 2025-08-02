use std::{f64::consts::PI, time::Instant};

use dashmap::{mapref::one::RefMut, DashMap};
use eyre::{eyre, Context as _, Report};
use parking_lot::Mutex;
use songbird::driver::opus::coder::Decoder;

use crate::audio_util::{
    adjust_volume, RawAudio, CHANNELS, MAX_AUDIO_BUFFER, OPUS_CHANNELS, OPUS_SAMPLE_RATE,
    RAW_AUDIO_SIZE,
};

use super::{Sender, SenderId, State};

impl super::DiscordBot {
    #[inline]
    fn get_or_insert_sender(
        senders: &DashMap<SenderId, Sender>,
        sender_id: SenderId,
    ) -> RefMut<'_, SenderId, Sender> {
        if !senders.contains_key(&sender_id) {
            let (audio_buffer_tx, audio_buffer_rx) = flume::bounded(MAX_AUDIO_BUFFER);
            senders.insert(
                sender_id,
                Sender {
                    audio_buffer_tx,
                    audio_buffer_rx,
                    decoder: Mutex::new(
                        Decoder::new(OPUS_SAMPLE_RATE, OPUS_CHANNELS)
                            .expect("Unable to make opus decoder"),
                    ),
                    last_audio_received: Mutex::new(None),
                },
            );
        }
        // we just inserted it, this shouldn't fail
        senders.get_mut(&sender_id).unwrap()
    }

    #[tracing::instrument(skip(self, raw_opus_data), fields(self.vc_id = %self.vc_id))]
    pub fn add_audio_to_hearing_buffer(
        &mut self,
        sender_id: SenderId,
        raw_opus_data: Vec<u8>,
        adjust_based_on_distance: bool,
        distance: f64,
        max_distance: f64,
    ) -> Result<(), Report> {
        tracing::info!("add_audio_to_hearing_buffer called: sender_id={:?}, raw_opus_data_len={}, adjust_based_on_distance={}, distance={}, max_distance={}", sender_id, raw_opus_data.len(), adjust_based_on_distance, distance, max_distance);
        let State::Started { senders, .. } = &*self.state.read() else {
            tracing::warn!("Bot is not started, cannot add audio");
            return Err(eyre!("Bot is not started"));
        };
        let sender = Self::get_or_insert_sender(senders, sender_id);
        if sender.audio_buffer_tx.is_full() {
            tracing::warn!("Sender audio buffer is full for sender_id={:?}", sender_id);
            return Err(eyre!("Sender audio buffer is full"));
        }

        let mut audio = vec![0i16; RAW_AUDIO_SIZE * CHANNELS as usize];
        let decode_result = sender
            .decoder
            .lock()
            .decode(
                Some((&raw_opus_data).try_into().wrap_err("Invalid opus data")?),
                (&mut audio).try_into().wrap_err("Unable to wrap output")?,
                false,
            );
        match decode_result {
            Ok(_) => tracing::info!("Decoded opus data for sender_id={:?}", sender_id),
            Err(e) => {
                tracing::error!("Unable to decode raw opus data for sender_id={:?}: {:?}", sender_id, e);
                return Err(eyre!("Unable to decode raw opus data: {:?}", e));
            }
        }
        let len = audio.len();
        let mut audio: RawAudio = match audio.try_into() {
            Ok(a) => a,
            Err(_) => {
                tracing::error!("Decoded audio is of length {} when it should be {} for sender_id={:?}", len, RAW_AUDIO_SIZE, sender_id);
                return Err(eyre!("Decoded audio is of length {len} when it should be {RAW_AUDIO_SIZE}"));
            }
        };

        if adjust_based_on_distance {
            // Hopefully this is a similar volume curve to what Minecraft/OpenAL uses
            let volume = ((distance / max_distance) * (PI / 2.0)).cos();
            tracing::info!("Calculated volume for sender_id={:?}: {}", sender_id, volume);
            if volume <= 0.0 {
                tracing::warn!("Skipping packet since volume is {} for sender_id={:?}", volume, sender_id);
                return Err(eyre!("Skipping packet since volume is {volume}"));
            }
            if volume < 1.0 {
                // only adjust volume if it's less than 100%
                tracing::info!("Adjusting volume for sender_id={:?} to {}", sender_id, volume);
                adjust_volume(&mut audio, volume);
            }
        }

        match sender.audio_buffer_tx.send(audio) {
            Ok(_) => tracing::info!("Audio sent to buffer for sender_id={:?}", sender_id),
            Err(e) => {
                tracing::error!("Failed to send audio to buffer for sender_id={:?}: {:?}", sender_id, e);
                return Err(eyre!("audio_buffer rx closed - please file a GitHub issue"));
            }
        }
        // get the now before acquiring lock
        // if we did Some(Instant::now()) I'm not sure if it
        // would delay the now until lock is acquired
        let now = Instant::now();
        *(sender.last_audio_received.lock()) = Some(now);
        tracing::info!("Updated last_audio_received for sender_id={:?}", sender_id);
        Ok(())
    }
}
