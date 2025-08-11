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
                let call_lock = songbird
                    .join(guild_id, channel_id)
                    .await
                    .wrap_err("Unable to join call")?;
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

                let handler = VoiceHandler {
                    vc_id: channel_id,
                    bot: Arc::clone(&bot_for_async),
                };
                call.add_global_event(CoreEvent::VoiceTick.into(), handler.clone());
                call.add_global_event(CoreEvent::SpeakingStateUpdate.into(), handler);

                call.play_only_input(create_playable_input(player_to_discord_buffers, audio_shutdown)?);

                eyre::Ok(())
            }) {
                RUNTIME.block_on(bot.disconnect(guild_id));
                return Err(e);
            }
        }

        *state_lock = State::Started {
            http: http.clone(),
            guild_id,
        };

        Ok(channel.name)
    }
}
