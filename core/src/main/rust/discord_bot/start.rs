use std::sync::Arc;

use eyre::{eyre, Context as _, Report};
use serenity::all::{Channel, ChannelType};
use songbird::CoreEvent;

use crate::runtime::RUNTIME;

use super::discord_receive::VoiceHandler;
use super::discord_speak::create_playable_input;
use std::sync::atomic::Ordering;
use super::State;

impl super::DiscordBot {
    /// Returns the voice channel name
    #[tracing::instrument(skip(bot), fields(bot_channel_id = ?bot.channel_id))]
    pub fn start(bot: Arc<super::DiscordBot>) -> Result<String, Report> {
        let mut state_lock = bot.state.write();

        let State::LoggedIn { http } = &*state_lock else {
            if matches!(*state_lock, State::Started { .. }) {
                return Err(eyre!("Bot is already started."));
            } else {
                return Err(eyre!("Bot is not logged in. An error may have occurred when logging in - please check the console."));
            }
        };

        // In case there are any packets left over
        bot.discord_to_mc_buffer.received_audio_rx.drain();


        let channel_id = *bot.channel_id.lock();
        let channel_id = channel_id.ok_or_else(|| eyre!("No channel_id set for this bot instance"))?;
        let channel = match RUNTIME
            .block_on(http.get_channel(channel_id))
            .wrap_err("Couldn't get voice channel")?
        {
            Channel::Guild(c) if c.kind == ChannelType::Voice => c,
            _ => return Err(eyre!("The specified channel is not a voice channel.")),
        };

        let songbird = bot.songbird.clone();
        let guild_id = channel.guild_id;
        let player_to_discord_buffers = Arc::clone(&bot.player_to_discord_buffers);
        let audio_shutdown = Arc::clone(&bot.audio_shutdown);
        audio_shutdown.store(false, Ordering::SeqCst);
        let bot_for_async = Arc::clone(&bot);
        {
            if let Err(e) = RUNTIME.block_on(async move {
                // Defensively clear any stale call (and its accumulated event
                // handlers) left behind by a previous session that didn't
                // disconnect cleanly. Reusing a stale Call would stack duplicate
                // VoiceTick handlers and double every received audio packet.
                if let Err(e) = songbird.remove(guild_id).await {
                    if !matches!(e, songbird::error::JoinError::NoCall) {
                        tracing::warn!(?e, "Failed to clear stale call before joining");
                    }
                }

                // Register receive handlers BEFORE joining: Discord announces
                // the SSRCs of users already in the channel during the join
                // handshake, and events fired before registration are dropped -
                // those users would show as "Unknown User" until they re-toggled
                // their voice state.
                let call_lock = songbird.get_or_insert(guild_id);
                {
                    let mut call = call_lock.lock().await;
                    let handler = VoiceHandler {
                        vc_id: channel_id,
                        bot: Arc::clone(&bot_for_async),
                        ssrc_username_map: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
                        ssrc_user_id_map: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
                        last_ssrc_order: Arc::new(std::sync::Mutex::new(std::collections::VecDeque::new())),
                    };
                    call.add_global_event(CoreEvent::VoiceTick.into(), handler.clone());
                    call.add_global_event(CoreEvent::SpeakingStateUpdate.into(), handler);
                }

                // Two-stage join (mirrors Songbird::join): initiate while holding
                // the lock, then await the connection without it.
                let join = {
                    let mut call = call_lock.lock().await;
                    call.join(channel_id)
                        .await
                        .wrap_err("Unable to begin joining call")?
                };
                join.await.wrap_err("Unable to join call")?;

                let mut call = call_lock.lock().await;

                // Check connection state
                if let Some(conn) = call.current_connection() {
                    if conn.session_id.is_empty() || conn.endpoint.is_empty() {
                        tracing::warn!("Songbird call may not be fully connected (missing session_id or endpoint); audio bridging may not work");
                    } else {
                        tracing::info!("Songbird call has session_id and endpoint; likely connected");
                    }
                } else {
                    tracing::warn!("Songbird call has no active connection after join; audio bridging will not work");
                }

                let input = create_playable_input(player_to_discord_buffers, audio_shutdown)?;
                let (input, audio_source_uuid) = input;
                *bot_for_async.audio_source_uuid.lock().unwrap() = Some(audio_source_uuid);
                call.play_only_input(input);

                eyre::Ok(())
            }) {
                RUNTIME.block_on(bot.disconnect(guild_id));
                return Err(e);
            }
        }

        let http_for_sync = http.clone();
        *state_lock = State::Started {
            http: http.clone(),
            guild_id,
        };

        // Fresh session: reset the watchdog's staleness clock so a just-started
        // bot isn't immediately flagged as "not receiving".
        bot.mark_audio_received();

        // Bridge users who are already sitting in the voice channel (they would
        // otherwise stay untracked until their next voice state change).
        let bot_for_sync = Arc::clone(&bot);
        RUNTIME.spawn(async move {
            tokio::time::sleep(std::time::Duration::from_millis(500)).await;
            bot_for_sync
                .sync_channel_members(channel_id, guild_id, http_for_sync)
                .await;
        });

        // Make sure the voice receive watchdog is running now that a bot is in a call
        super::watchdog::ensure_started();

        Ok(channel.name)
    }
}
