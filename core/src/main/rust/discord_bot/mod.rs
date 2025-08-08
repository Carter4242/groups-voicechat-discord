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
use crate::runtime::RUNTIME;

mod discord_receive;
mod discord_speak;
mod jni;
mod log_in;
mod start;

struct DiscordToMinecraftBuffer {
    received_audio_tx: flume::Sender<Vec<Vec<u8>>>,
    received_audio_rx: flume::Receiver<Vec<Vec<u8>>>,
}


// --- Jitter buffer integration ---
mod playout_buffer;
use playout_buffer::{PlayoutBuffer, StoredPacket};
use std::sync::Mutex as StdMutex;

pub struct PlayerToDiscordBuffer {
    pub playout_buffer: StdMutex<PlayoutBuffer>,
}

struct DiscordBot {
    token: String,
    pub vc_id: ChannelId,
    songbird: Arc<Songbird>,
    state: RwLock<State>,
    client_task: Mutex<Option<AbortHandle>>,
    /// Buffer for Discord -> Minecraft audio (Opus data, single group)
    discord_to_mc_buffer: DiscordToMinecraftBuffer,
    /// Buffers for Minecraft -> Discord audio (PCM data per player)
    player_to_discord_buffers: Arc<DashMap<Uuid, PlayerToDiscordBuffer>>,
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
    pub fn new(token: String, vc_id: ChannelId) -> DiscordBot {
        let (received_audio_tx, received_audio_rx) = flume::bounded(MAX_AUDIO_BUFFER);
        DiscordBot {
            token,
            vc_id,
            songbird: {
                use std::num::NonZeroUsize;
                let songbird_config = Config::default()
                    .decode_mode(DecodeMode::Decrypt)
                    .playout_buffer_length(NonZeroUsize::new(8).unwrap()) // 5 packets = 100ms
                    .playout_spike_length(3); // 3 extra packets for bursts
                Songbird::serenity_from_config(songbird_config)
            },
            state: RwLock::new(State::NotLoggedIn),
            client_task: Mutex::new(None),
            discord_to_mc_buffer: DiscordToMinecraftBuffer {
                received_audio_tx,
                received_audio_rx,
            },
            player_to_discord_buffers: Arc::new(DashMap::new()),
        }
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

    pub fn block_for_speaking_opus_data(&self) -> Result<Vec<Vec<u8>>, Report> {
        match self.discord_to_mc_buffer.received_audio_rx.recv_timeout(Duration::from_millis(50)) {
            Ok(data) => Ok(data),
            Err(flume::RecvTimeoutError::Timeout) => {
                Err(eyre::eyre!("no one is speaking (timeout waiting for audio)"))
            }
            Err(flume::RecvTimeoutError::Disconnected) => {
                Err(eyre::eyre!("audio channel closed (bot disconnected or no senders)"))
            }
        }
    }

    /// Store Opus payload in the player buffer, using the provided sequence number
    pub fn add_opus_to_playback_buffer(&self, player_id: Uuid, payload: Vec<u8>, seq: u16) {
        // Special handling for zero-length packets: treat as end-of-speech marker
        if payload.is_empty() {
            let buffer = self.player_to_discord_buffers.entry(player_id)
                .or_insert_with(|| {
                    tracing::info!("Creating new PlayerToDiscordBuffer for player_id={}", player_id);
                    PlayerToDiscordBuffer {
                        playout_buffer: StdMutex::new(PlayoutBuffer::new(8, seq)),
                    }
                });
            let mut playout = buffer.playout_buffer.lock().unwrap();
            playout.force_drain();
            tracing::info!("Received zero-length packet for player_id={}: forcing playout buffer to drain mode", player_id);
            return;
        }
        let buffer = self.player_to_discord_buffers.entry(player_id)
            .or_insert_with(|| {
                tracing::info!("Creating new PlayerToDiscordBuffer for player_id={}", player_id);
                PlayerToDiscordBuffer {
                    playout_buffer: StdMutex::new(PlayoutBuffer::new(8, seq)),
                }
            });
        let mut playout = buffer.playout_buffer.lock().unwrap();
        let stored = StoredPacket { opus: payload, decrypted: true, seq };
        playout.store_packet(stored);
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
