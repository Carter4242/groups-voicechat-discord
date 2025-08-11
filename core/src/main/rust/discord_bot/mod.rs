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
    pub category_id: ChannelId, // The Discord category where channels are created
    pub channel_id: parking_lot::Mutex<Option<ChannelId>>, // The managed voice channel (created dynamically)
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
    pub fn new(token: String, category_id: ChannelId) -> DiscordBot {
        let (received_audio_tx, received_audio_rx) = flume::bounded(MAX_AUDIO_BUFFER);
        DiscordBot {
            token,
            category_id,
            channel_id: parking_lot::Mutex::new(None),
            songbird: {
                use std::num::NonZeroUsize;
                let songbird_config = Config::default()
                    .decode_mode(DecodeMode::Decrypt)
                    .playout_buffer_length(NonZeroUsize::new(7).unwrap())
                    .playout_spike_length(3);
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

    /// Asynchronously create a Discord voice channel in the configured category.
    pub async fn create_voice_channel(&self, http: &Arc<Http>, group_name: &str) -> Result<ChannelId, Report> {
        use serenity::all::ChannelType;
        use serenity::builder::CreateChannel as CreateChannelBuilder;

        // Get the parent category's guild ID
        let category_id = self.category_id;
        let category_channel = category_id.to_channel(http).await?;
        let guild_id = match category_channel.guild() {
            Some(guild_channel) => guild_channel.guild_id,
            None => return Err(eyre::eyre!("Category ID is not a guild channel")),
        };

        // Create the voice channel in the category
        let builder = CreateChannelBuilder::new(group_name)
            .kind(ChannelType::Voice)
            .category(category_id);
        let channel = guild_id.create_channel(http.as_ref(), builder).await?;
        let new_channel_id = channel.id;
        info!("Created Discord voice channel '{}' with ID {} in category {}", group_name, new_channel_id, category_id);
        *self.channel_id.lock() = Some(new_channel_id);
        Ok(new_channel_id)
    }

    /// Asynchronously delete the managed Discord voice channel, if it exists.
    pub async fn delete_voice_channel(&self, http: &Arc<Http>) -> Result<(), Report> {
        let mut channel_id_lock = self.channel_id.lock();
        if let Some(channel_id) = *channel_id_lock {
            match channel_id.delete(http.as_ref()).await {
                Ok(_) => {
                    info!("Deleted Discord voice channel with ID {}", channel_id);
                    *channel_id_lock = None;
                },
                Err(e) => {
                    warn!(?e, "Failed to delete Discord voice channel with ID {}", channel_id);
                }
            }
        } else {
            warn!("No Discord channel to delete");
        }
        Ok(())
    }



    #[tracing::instrument(skip(self), fields(self.category_id = %self.category_id, self.channel_id = ?self.channel_id))]
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

    #[tracing::instrument(skip(self), fields(self.category_id = %self.category_id, self.channel_id = ?self.channel_id))]
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
    #[tracing::instrument(skip(self), fields(self.category_id = %self.category_id, self.channel_id = ?self.channel_id))]
    fn drop(&mut self) {
        info!("DiscordBot::drop called for category_id={}, channel_id={:?}", self.category_id, self.channel_id);
        if let Some(client_task) = &*self.client_task.lock() {
            info!("Aborting client task");
            client_task.abort();
        }
    }
}
