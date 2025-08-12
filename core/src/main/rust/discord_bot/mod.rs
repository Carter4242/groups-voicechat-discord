use std::{
    sync::Arc,
    time::Duration,
};

use dashmap::DashMap;
use futures_util::FutureExt;
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

mod discord_receive;
mod discord_speak;
mod jni_bridge;
mod log_in;
mod start;

struct DiscordToMinecraftBuffer {
    received_audio_tx: flume::Sender<Vec<(String, Vec<u8>)>>,
    received_audio_rx: flume::Receiver<Vec<(String, Vec<u8>)>>,
}


// --- Jitter buffer integration ---
mod playout_buffer;
use playout_buffer::{PlayoutBuffer, StoredPacket};
use std::sync::Mutex as StdMutex;

pub struct PlayerToDiscordBuffer {
    pub playout_buffer: StdMutex<PlayoutBuffer>,
}

use std::sync::atomic::{AtomicBool, Ordering};

use jni::JavaVM;
use jni::objects::GlobalRef;

pub struct DiscordBot {
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
    audio_shutdown: Arc<AtomicBool>,
    /// JNI: JavaVM for cross-thread callback
    pub java_vm: Arc<JavaVM>,
    /// JNI: GlobalRef to the Java DiscordBot object
    pub java_bot_obj: GlobalRef,
    /// Map of user_id -> username, updated when users join a channel
    user_id_to_username: Arc<DashMap<u64, String>>,
    /// UUID for the audio source, used for poking after start
    audio_source_uuid: Arc<StdMutex<Option<Uuid>>>,
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
    pub fn new(token: String, category_id: ChannelId, java_vm: Arc<JavaVM>, java_bot_obj: GlobalRef) -> DiscordBot {
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
            audio_shutdown: Arc::new(AtomicBool::new(false)),
            java_vm,
            java_bot_obj,
            user_id_to_username: Arc::new(DashMap::new()),
            audio_source_uuid: Arc::new(StdMutex::new(None)),
        }
    }

    /// Returns the UUID of the audio source, if set
    pub fn get_audio_source_uuid(&self) -> Option<Uuid> {
        *self.audio_source_uuid.lock().unwrap()
    }

    // Looks up the username for a given user_id from the local map
    pub fn lookup_username(&self, user_id: impl Into<u64>) -> Option<String> {
        let user_id = user_id.into();
        self.user_id_to_username.get(&user_id).map(|entry| entry.value().clone())
    }

    /// Call this when a user joins a channel to update the user_id -> username map
    pub fn update_username_mapping(&self, user_id: impl Into<u64>, username: &str) {
        let user_id = user_id.into();
        self.user_id_to_username.insert(user_id, username.to_string());
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
    pub async fn delete_voice_channel(&self, http: &Arc<Http>, guild_id: Option<GuildId>) -> Result<(), Report> {
        let result = std::panic::AssertUnwindSafe(async {
            info!("Rust: delete_voice_channel called (guild_id={:?})", guild_id);
            // Copy out the channel_id, then drop the lock before awaiting
            let channel_id_opt = {
                let channel_id_lock = self.channel_id.lock();
                let channel_id = *channel_id_lock;
                channel_id
            };
            if let Some(channel_id) = channel_id_opt {
                use tokio::time::timeout;
                use std::time::Duration;
                info!("Rust: Attempting to delete Discord voice channel with ID {}", channel_id);
                // If we are in a call in this guild, disconnect first
                if let Some(guild_id) = guild_id {
                    info!("Rust: Disconnecting from guild_id {} before deleting channel", guild_id);
                    self.disconnect(guild_id).await;
                }
                info!("Rust: About to await channel_id.delete for channel {} (with 1.5s timeout)", channel_id);
                let delete_result = timeout(Duration::from_millis(1500), std::panic::AssertUnwindSafe(channel_id.delete(http.as_ref())).catch_unwind()).await;
                match delete_result {
                    Ok(inner) => {
                        info!("Rust: Finished awaiting channel_id.delete for channel {} (result: {:?})", channel_id, inner.as_ref().map(|r| r.as_ref().map(|_| "Ok").unwrap_or("Err")).unwrap_or("Panic"));
                        match inner {
                            Ok(Ok(_)) => {
                                info!("Deleted Discord voice channel with ID {}", channel_id);
                                // Set to None after successful deletion
                                let mut channel_id_lock = self.channel_id.lock();
                                *channel_id_lock = None;
                            },
                            Ok(Err(e)) => {
                                warn!(?e, "Failed to delete Discord voice channel with ID {}", channel_id);
                            },
                            Err(_) => {
                                warn!("Panic occurred while deleting Discord voice channel with ID {}", channel_id);
                            }
                        }
                    },
                    Err(_) => {
                        warn!("Timeout (1.5s) while deleting Discord voice channel with ID {}", channel_id);
                    }
                }
            } else {
                warn!("Rust: No Discord channel to delete");
            }
            Ok(())
        }).catch_unwind().await;
        match result {
            Ok(r) => r,
            Err(_) => {
                warn!("Caught panic in delete_voice_channel (likely due to runtime shutdown or FFI error)");
                Ok(())
            }
        }
    }

    /// Asynchronously update the managed Discord voice channel's name, if it exists.
    pub async fn update_voice_channel_name(&self, http: &Arc<Http>, new_name: &str) -> Result<(), Report> {
        let result = std::panic::AssertUnwindSafe(async {
            use tokio::time::timeout;
            use std::time::Duration;
            // Copy out the channel_id, then drop the lock before awaiting
            let channel_id_opt = {
                let channel_id_lock = self.channel_id.lock();
                channel_id_lock.clone()
            };
            info!("update_voice_channel_name: called with new_name='{}', channel_id_opt={:?}", new_name, channel_id_opt);
            if let Some(channel_id) = channel_id_opt {
                let builder = serenity::builder::EditChannel::new().name(new_name);
                info!("update_voice_channel_name: attempting to edit channel {} with new name '{}' (with 1.5s timeout)", channel_id, new_name);
                let edit_result = timeout(Duration::from_millis(1500), channel_id.edit(http.as_ref(), builder)).await;
                match edit_result {
                    Ok(Ok(_)) => {
                        info!("Updated Discord voice channel name to '{}' for channel {}", new_name, channel_id);
                    },
                    Ok(Err(e)) => {
                        warn!(?e, "Failed to update Discord voice channel name for channel {} (new_name='{}')", channel_id, new_name);
                    },
                    Err(_) => {
                        warn!("Timeout (1.5s) while updating Discord voice channel name for channel {} (new_name='{}')", channel_id, new_name);
                    }
                }
            } else {
                warn!("No Discord channel to update name (new_name='{}')", new_name);
            }
            Ok(())
        }).catch_unwind().await;
        match result {
            Ok(r) => r,
            Err(_) => {
                warn!("Caught panic in update_voice_channel_name (likely due to runtime shutdown)");
                Ok(())
            }
        }
    }

    /// Send a text message to the managed Discord voice channel's text chat.
    pub async fn send_text_message(&self, message: &str) -> Result<(), Report> {
        let result = std::panic::AssertUnwindSafe(async {
            let state = self.state.read();
            let http = match &*state {
                State::LoggedIn { http } | State::Started { http, .. } => http.clone(),
                _ => {
                    warn!("send_text_message: Bot not logged in");
                    return Err(eyre::eyre!("Bot not logged in"));
                }
            };
            // Copy out the channel_id, then drop the lock before awaiting
            let channel_id_opt = {
                let channel_id_lock = self.channel_id.lock();
                *channel_id_lock
            };
            let channel_id = match channel_id_opt {
                Some(id) => id,
                None => {
                    warn!("send_text_message: No Discord channel to send text message");
                    return Err(eyre::eyre!("No Discord channel to send text message"));
                }
            };
            drop(state);
            use serenity::builder::CreateMessage;
            let msg = CreateMessage::new().content(message);
            channel_id.send_message(http.as_ref(), msg).await?;
            Ok(())
        }).catch_unwind().await;
        match result {
            Ok(r) => r,
            Err(_) => {
                warn!("Caught panic in send_text_message (likely due to runtime shutdown)");
                Ok(())
            }
        }
    }


    #[tracing::instrument(skip(self), fields(self.category_id = %self.category_id, self.channel_id = ?self.channel_id))]
    pub async fn disconnect(&self, guild_id: GuildId) {
        let mut tries = 0;
        loop {
            match self.songbird.remove(guild_id).await {
                Ok(_) => {
                    info!("Successfully disconnected from call");
                    break;
                },
                Err(error) => {
                    // Check for NoCall error
                    let error_str = format!("{}", error);
                    warn!(?error, "Failed to disconnect from the call: {error}");
                    if error_str.contains("NoCall") {
                        info!("Disconnect error is NoCall; treating as success and not retrying");
                        break;
                    }
                    tries += 1;
                    if tries < 5 {
                        info!("Trying to disconnect again in 500 milliseconds");
                        tokio::time::sleep(Duration::from_millis(500)).await;
                    } else {
                        warn!("Giving up on call disconnection");
                        break;
                    }
                }
            }
        }
    }

    #[tracing::instrument(skip(self), fields(self.category_id = %self.category_id, self.channel_id = ?self.channel_id))]
    pub fn stop(&self) -> Result<(), Report> {
        let mut state_lock = self.state.write();
        let State::Started { .. } = &*state_lock else {
            info!("Bot is not started");
            return Ok(());
        };
        info!("Stopping DiscordBot: transitioning from Started to LoggedIn, setting audio_shutdown to true");
        self.audio_shutdown.store(true, Ordering::SeqCst);
        // Remove audio source UUID from registry if present
        if let Some(uuid) = self.get_audio_source_uuid() {
            crate::discord_bot::discord_speak::remove_audio_source(&uuid);
        }
        *state_lock = match &*state_lock {
            State::Started { http, .. } => State::LoggedIn { http: http.clone() },
            _ => unreachable!(),
        };
        Ok(())
    }

    pub fn block_for_speaking_opus_data(&self) -> Result<Vec<(String, Vec<u8>)>, Report> {
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
        if self.audio_shutdown.load(Ordering::SeqCst) {
            return;
        }
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
