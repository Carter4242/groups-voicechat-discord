use std::{
    sync::Arc,
    thread,
    time::{Duration, Instant},
};

use eyre::Report;
use serenity::{
    all::{Context, EventHandler, GatewayIntents, Http, Ready},
    Client,
};
use songbird::SerenityInit;
use tokio::sync::mpsc;
use tracing::{info, warn};

use crate::runtime::RUNTIME;

use super::State;

impl super::DiscordBot {
    #[tracing::instrument(skip(self), fields(self.category_id = %self.category_id, self.channel_id = ?self.channel_id))]
    pub fn log_in(self: &Arc<Self>) -> Result<(), Report> {
        let mut state_lock = self.state.write();

        if !matches!(*state_lock, State::NotLoggedIn) {
            info!("Already logged in or currently logging in");
            return Ok(());
        }

        *state_lock = State::LoggingIn;

        let (tx, mut rx) = mpsc::channel(1);

        let token = self.token.clone();
        let songbird = self.songbird.clone();
        let bot_weak = Arc::downgrade(self);
        let mut client_task = self.client_task.lock();
        // While this should never happen, it's better to catch it than leave it running
        if let Some(client_task) = &*client_task {
            info!("Aborting previous client task");
            client_task.abort();
            // Wait for it to finish or 20 seconds to pass
            let start = Instant::now();
            while !client_task.is_finished()
                && start.duration_since(Instant::now()) < Duration::from_secs(10)
            {
                info!("Sleeping for client task to finish");
                thread::sleep(Duration::from_millis(500));
            }
            if !client_task.is_finished() {
                warn!("Client task did not finish");
            }
        }
        *client_task = Some(
            RUNTIME
                .spawn(async move {
                    let intents = GatewayIntents::GUILD_VOICE_STATES
                        | GatewayIntents::GUILD_MESSAGES
                        | GatewayIntents::MESSAGE_CONTENT;

                    let mut client = match Client::builder(&token, intents)
                        .event_handler(Handler {
                            log_in_tx: tx.clone(),
                            bot: bot_weak,
                        })
                        .register_songbird_with(songbird)
                        .await
                    {
                        Ok(c) => c,
                        Err(e) => {
                            tx.send(Err(Report::new(e)))
                                .await
                                .expect("log_in rx dropped - please file a GitHub issue");
                            return;
                        }
                    };

                    if let Err(e) = client.start().await {
                        tx.send(Err(Report::new(e)))
                            .await
                            .expect("log_in rx dropped - please file a GitHub issue");
                    } else {
                        info!("Bot finished");
                    }
                })
                .abort_handle(),
        );

        match rx
            .blocking_recv()
            .expect("log_in tx dropped - please file a GitHub issue")
        {
            Ok(http) => {
                *state_lock = State::LoggedIn { http };
                Ok(())
            }
            Err(e) => {
                *state_lock = State::NotLoggedIn;
                Err(e)
            }
        }
    }
}

use std::sync::Weak;

struct Handler {
    pub log_in_tx: mpsc::Sender<Result<Arc<Http>, Report>>,
    pub bot: Weak<super::DiscordBot>,
}

impl Handler {
    /// Extract display name from PartialMember: nick -> global_name -> username  
    fn get_display_name_from_partial_member(member: &serenity::all::PartialMember) -> String {
        member.nick
            .clone()
            .or_else(|| {
                member.user.as_ref()
                    .and_then(|user| user.global_name.clone())
            })
            .unwrap_or_else(|| {
                member.user.as_ref()
                    .map(|user| user.name.clone())
                    .unwrap_or_else(|| "<unknown>".to_string())
            })
    }

    /// Extract display name from User: global_name -> username
    fn get_display_name_from_user(user: &serenity::all::User) -> String {
        user.global_name
            .clone()
            .unwrap_or_else(|| user.name.clone())
    }
}

#[serenity::async_trait]
impl EventHandler for Handler {
    async fn ready(&self, ctx: Context, _ready: Ready) {
        if let Err(e) = self.log_in_tx.send(Ok(ctx.http)).await {
            tracing::error!("log_in rx dropped - login result could not be sent: {e}");
        }
    }

    async fn voice_state_update(
        &self,
        _ctx: Context,
        _old: Option<serenity::all::VoiceState>,
        new: serenity::all::VoiceState,
    ) {
        let user_id = new.user_id.get();
        let username = new.member
            .as_ref()
            .map(|member| member.display_name().to_string())
            .unwrap_or_else(|| {
                warn!("Voice state update for user {} has no member data", user_id);
                "<unknown>".to_string()
            });

        if let Some(bot) = self.bot.upgrade() {
            // Always update the user_id -> username map
            bot.update_username_mapping(user_id, &username);

            let mut env = bot.java_vm.attach_current_thread().expect("Failed to attach thread to JVM");
            if let Some(channel_id) = new.channel_id {
                // User joined or switched channel
                crate::discord_bot::jni_bridge::notify_java_discord_user_voice_state(
                    &mut env,
                    bot.java_bot_obj.as_obj(),
                    user_id,
                    &username,
                    channel_id.get(),
                    true,
                );
            } else {
                // User left all voice channels
                crate::discord_bot::jni_bridge::notify_java_discord_user_voice_state(
                    &mut env,
                    bot.java_bot_obj.as_obj(),
                    user_id,
                    &username,
                    0,
                    false,
                );
            }
        } else {
            tracing::warn!("[voice_state_update] DiscordBot instance no longer exists");
        }
    }

    async fn message(&self, _ctx: Context, msg: serenity::all::Message) {
        if let Some(bot) = self.bot.upgrade() {
            // Get the managed channel_id (voice channel) for this bot
            let channel_id_opt = *bot.channel_id.lock();
            if let Some(managed_channel_id) = channel_id_opt {
                // Only forward messages sent in the managed channel
                if msg.channel_id == managed_channel_id {
                    let author_display_name = msg.member
                        .as_ref()
                        .map(|member| Self::get_display_name_from_partial_member(member))
                        .unwrap_or_else(|| Self::get_display_name_from_user(&msg.author));

                    let author_id = msg.author.id.get();
                    let content = msg.content.clone();

                    // Collect attachments as Vec<(String, String)>: (filename, url)
                    let attachments: Vec<(String, String)> = msg.attachments.iter()
                        .map(|a| (a.filename.clone(), a.url.clone()))
                        .collect();

                    // Attach to JVM and call Java handler
                    let mut env = bot.java_vm.attach_current_thread().expect("Failed to attach thread to JVM");
                    crate::discord_bot::jni_bridge::notify_java_discord_text_message(
                        &mut env,
                        bot.java_bot_obj.as_obj(),
                        &author_display_name,
                        author_id,
                        &content,
                        managed_channel_id.get(),
                        &attachments,
                    );
                }
            }
        }
    }
}
