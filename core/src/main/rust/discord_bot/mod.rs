use std::{
    sync::Arc,
    thread,
    time::Duration,
};

use dashmap::DashMap;
use uuid::Uuid;
use eyre::{Context as _, Report};
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

struct GroupAudioBuffer {
    buffer_tx: flume::Sender<Vec<u8>>,
    buffer_rx: flume::Receiver<Vec<u8>>,
}

struct DiscordBot {
    token: String,
    pub vc_id: ChannelId,
    songbird: Arc<Songbird>,
    state: RwLock<State>,
    received_audio_tx: flume::Sender<Vec<u8>>,
    received_audio_rx: flume::Receiver<Vec<u8>>,
    client_task: Mutex<Option<AbortHandle>>,
    group_buffers: Arc<DashMap<Uuid, GroupAudioBuffer>>,
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
                let songbird_config = Config::default().decode_mode(DecodeMode::Decrypt);
                Songbird::serenity_from_config(songbird_config)
            },
            state: RwLock::new(State::NotLoggedIn),
            received_audio_tx,
            received_audio_rx,
            client_task: Mutex::new(None),
            group_buffers: Arc::new(DashMap::new()),
        }
    }

    /// Route Discord Opus payload to all group buffers
    pub fn decode_and_route_to_groups(&self, payload: &[u8]) {
        for entry in self.group_buffers.iter() {
            let group_id = *entry.key();
            self.add_audio_to_group_buffer(group_id, payload);
        }
    }

    #[allow(dead_code)]
    pub fn is_started(&self) -> bool {
        if let Some(lock) = self.state.try_read() {
            matches!(*lock, State::Started { .. })
        } else {
            false
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
    pub fn add_audio_to_group_buffer(&self, group_id: Uuid, payload: &[u8]) {
        let buffer = self.group_buffers.entry(group_id)
            .or_insert_with(|| {
                let (tx, rx) = flume::bounded(MAX_AUDIO_BUFFER);
                GroupAudioBuffer {
                    buffer_tx: tx,
                    buffer_rx: rx,
                }
            });
        if buffer.buffer_tx.is_full() {
            warn!("Group audio buffer is full for group_id={:?}", group_id);
            return;
        }
        let _ = buffer.buffer_tx.send(payload.to_vec());
    }
}

impl Drop for DiscordBot {
    #[tracing::instrument(skip(self), fields(self.vc_id = %self.vc_id))]
    fn drop(&mut self) {
        if let Some(client_task) = &*self.client_task.lock() {
            info!("Aborting client task");
            client_task.abort();
        }
    }
}
