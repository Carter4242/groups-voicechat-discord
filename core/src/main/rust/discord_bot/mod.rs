use std::{
    sync::Arc,
    thread,
    time::Duration,
};

use dashmap::DashMap;
use uuid::Uuid;
use eyre::{Report};
use parking_lot::{Mutex, RwLock};
use serenity::all::{ChannelId, GuildId, Http};
use songbird::{
    driver::{
        DecodeMode,
    },
    Config, Songbird,
};
use tokio::task::AbortHandle;
use tracing::{info, warn};

use crate::audio_util::MAX_AUDIO_BUFFER;
use crate::audio_util::RAW_AUDIO_SIZE;
use crate::audio_util::RawAudio;
use std::f32::consts::PI;
use crate::runtime::RUNTIME;

mod discord_receive;
mod discord_speak;
mod jni;
mod log_in;
mod start;

struct GroupAudioBuffer {
    pcm_buffer_tx: flume::Sender<RawAudio>,
    pcm_buffer_rx: flume::Receiver<RawAudio>,
    opus_buffer_tx: flume::Sender<Vec<u8>>,
}

struct DiscordBot {
    token: String,
    pub vc_id: ChannelId,
    songbird: Arc<Songbird>,
    state: RwLock<State>,
    received_audio_tx: flume::Sender<Vec<u8>>,
    received_audio_rx: flume::Receiver<Vec<u8>>,
    client_task: Mutex<Option<AbortHandle>>,
    /// Buffers for Discord -> Minecraft audio (Opus data per group)
    discord_to_mc_buffers: Arc<DashMap<Uuid, GroupAudioBuffer>>,
    /// Buffers for Minecraft -> Discord audio (PCM data per group)
    mc_to_discord_buffers: Arc<DashMap<Uuid, GroupAudioBuffer>>,
    opus_decoder: parking_lot::Mutex<songbird::driver::opus::coder::Decoder>,
}

enum State {
    NotLoggedIn,
    LoggingIn,
    LoggedIn {
        http: Arc<Http>,
    },
    Started {
        http: Arc<Http>,
        guild_id: GuildId,
    },
}

impl DiscordBot {
    /// Returns true if bot is in Started state
    pub fn is_audio_active(&self) -> bool {
        if let Some(lock) = self.state.try_read() {
            matches!(*lock, State::Started { .. })
        } else {
            false
        }
    }
    pub fn new(token: String, vc_id: ChannelId) -> DiscordBot {
        let (received_audio_tx, received_audio_rx) = flume::bounded(MAX_AUDIO_BUFFER);
        DiscordBot {
            token,
            vc_id,
            songbird: {
                let songbird_config = Config::default().decode_mode(DecodeMode::Decrypt);
                Songbird::serenity_from_config(songbird_config)
            },
            state: RwLock::new(State::NotLoggedIn),
            received_audio_tx,
            received_audio_rx,
            client_task: Mutex::new(None),
            discord_to_mc_buffers: Arc::new(DashMap::new()),
            mc_to_discord_buffers: Arc::new(DashMap::new()),
            opus_decoder: parking_lot::Mutex::new(
                songbird::driver::opus::coder::Decoder::new(
                    crate::audio_util::OPUS_SAMPLE_RATE,
                    crate::audio_util::OPUS_CHANNELS,
                ).expect("Unable to create Opus decoder")
            ),
        }
    }

    /// Route Discord Opus payload to all group buffers (for Minecraft transmission)
    pub fn decode_and_route_to_groups(&self, opus_payload: &[u8]) {
        for entry in self.discord_to_mc_buffers.iter() {
            let group_id = *entry.key();
            let buffer = entry.value();
            let opus_buffer_occupancy = buffer.opus_buffer_tx.len();
            tracing::info!("[Discord->Minecraft] Opus buffer occupancy for group_id={}: {} packets", group_id, opus_buffer_occupancy);
            self.add_opus_to_minecraft_buffer(group_id, opus_payload);
        }
    }

    /// Add Opus data to Minecraft buffer (for transmission)
    pub fn add_opus_to_minecraft_buffer(&self, group_id: Uuid, opus_payload: &[u8]) {
        tracing::info!(
            "add_opus_to_minecraft_buffer called for group_id={} payload_len={}",
            group_id,
            opus_payload.len()
        );
        let buffer = self.discord_to_mc_buffers.entry(group_id)
            .or_insert_with(|| {
                tracing::info!("Creating new Discord->Minecraft GroupAudioBuffer for group_id={}", group_id);
                let (pcm_tx, pcm_rx) = flume::bounded(MAX_AUDIO_BUFFER);
                let (opus_tx, _) = flume::bounded(MAX_AUDIO_BUFFER);
                GroupAudioBuffer {
                    pcm_buffer_tx: pcm_tx,
                    pcm_buffer_rx: pcm_rx,
                    opus_buffer_tx: opus_tx,
                }
            });
        if buffer.opus_buffer_tx.is_full() {
            tracing::warn!("Group Opus audio buffer is full for group_id={}", group_id);
            return;
        }
        let _ = buffer.opus_buffer_tx.send(opus_payload.to_vec());
        tracing::info!("Opus audio added to buffer for group_id={}", group_id);
    }

    #[tracing::instrument(skip(self), fields(self.vc_id = %self.vc_id))]
    fn disconnect(&self, guild_id: GuildId) {
        let mut tries = 0;
        loop {
            if let Err(error) = RUNTIME.block_on(self.songbird.remove(guild_id)) {
                warn!(?error, "Failed to disconnect from the call: {error}");
                tries += 1;
            } else {
                info!("Successfully disconnected from call");
                break;
            }
            if tries < 5 {
                info!("Trying to disconnect again in 500 milliseconds");
                thread::sleep(Duration::from_millis(500));
            } else {
                warn!("Giving up on call disconnection");
                break;
            }
        }
    }

    #[tracing::instrument(skip(self), fields(self.vc_id = %self.vc_id))]
    pub fn stop(&self) -> Result<(), Report> {
        let mut state_lock = self.state.write();
        let State::Started { http, guild_id } = &*state_lock else {
            info!("Bot is not started");
            return Ok(());
        };
        self.disconnect(*guild_id);
        *state_lock = State::LoggedIn { http: http.clone() };
        Ok(())
    }

    pub fn block_for_speaking_opus_data(&self) -> Result<Vec<u8>, Report> {
        match self.received_audio_rx.recv_timeout(Duration::from_secs(1)) {
            Ok(data) => Ok(data),
            Err(flume::RecvTimeoutError::Timeout) => {
                Err(eyre::eyre!("no one is speaking (timeout waiting for audio)"))
            }
            Err(flume::RecvTimeoutError::Disconnected) => {
                Err(eyre::eyre!("audio channel closed (bot disconnected or no senders)"))
            }
        }
    }

    /// Decode Opus payload and send PCM to the group buffer
    pub fn add_pcm_to_playback_buffer(&self, group_id: Uuid, payload: Vec<u8>) {
        // Decode Opus to PCM (RawAudio)
        let mut audio = vec![0i16; RAW_AUDIO_SIZE];
        let mut decoder = self.opus_decoder.lock();
        let packet = match (&payload).try_into() {
            Ok(p) => p,
            Err(e) => {
                tracing::error!("Invalid opus data for group_id={}: {:?}", group_id, e);
                return;
            }
        };
        let output = match (&mut audio).try_into() {
            Ok(o) => o,
            Err(e) => {
                tracing::error!("Unable to wrap output for group_id={}: {:?}", group_id, e);
                return;
            }
        };
        match decoder.decode(Some(packet), output, false) {
            Ok(_) => {
                let len = audio.len();
                // Log first 10 samples of decoded PCM before sending to buffer
                match audio.try_into() {
                    Ok(pcm) => {
                        let buffer = self.mc_to_discord_buffers.entry(group_id)
                            .or_insert_with(|| {
                                tracing::info!("Creating new Minecraft->Discord GroupAudioBuffer for group_id={}", group_id);
                                let (pcm_tx, pcm_rx) = flume::bounded(MAX_AUDIO_BUFFER);
                                let (opus_tx, _) = flume::bounded(MAX_AUDIO_BUFFER);
                                GroupAudioBuffer {
                                    pcm_buffer_tx: pcm_tx,
                                    pcm_buffer_rx: pcm_rx,
                                    opus_buffer_tx: opus_tx,
                                }
                            });
                        let _pcm_buffer_occupancy = buffer.pcm_buffer_tx.len();
                        //tracing::info!("[Minecraft->Discord] PCM buffer occupancy for group_id={}: {} packets", group_id, _pcm_buffer_occupancy);
                        if buffer.pcm_buffer_tx.is_full() {
                            tracing::warn!("Group PCM buffer is full for group_id={}", group_id);
                            return;
                        }
                        let _ = buffer.pcm_buffer_tx.send(pcm);

                        // TEST: Inject a 440Hz sine wave for 1 second (48kHz, mono)
                        //let test_pcm = Self::generate_sine_wave(440.0, 1.0);
                        //let _ = buffer.pcm_buffer_tx.send(test_pcm);
                        //tracing::info!("Injected test sine wave into PCM buffer for group_id={}", group_id);
                    }
                    Err(_) => {
                        tracing::error!("Decoded audio is of length {} when it should be {} for group_id={}", len, RAW_AUDIO_SIZE, group_id);
                    }
                }
            }
            Err(e) => {
                tracing::error!("Opus decode failed for group_id={}: {:?}", group_id, e);
            }
        }
    }

    /// Generate a mono 440Hz sine wave for `duration_secs` seconds (i16 PCM, 48kHz)
    #[allow(dead_code)]
    pub fn generate_sine_wave(freq: f32, duration_secs: f32) -> RawAudio {
        let sample_rate = 48000.0;
        let samples = RAW_AUDIO_SIZE.min((sample_rate * duration_secs) as usize);
        let mut buf = [0i16; RAW_AUDIO_SIZE];
        for i in 0..samples {
            let t = i as f32 / sample_rate;
            let val = (freq * 2.0 * PI * t).sin();
            buf[i] = (val * i16::MAX as f32 * 0.8) as i16; // 20% volume
        }
        buf
    }
}

impl Drop for DiscordBot {
    #[tracing::instrument(skip(self), fields(self.vc_id = %self.vc_id))]
    fn drop(&mut self) {
        info!("DiscordBot::drop called for vc_id={}", self.vc_id);
        if let Some(client_task) = &*self.client_task.lock() {
            info!("Aborting client task");
            client_task.abort();
        }
    }
}
