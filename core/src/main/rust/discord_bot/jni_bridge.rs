use std::any::Any;
use std::sync::Arc;

use jni::{
    objects::{JByteArray, JObject, JObjectArray, JString, JValue},
    sys::{jboolean, jlong, jobject},
    JNIEnv,
};
use serenity::all::{ChannelId, Http, MessageId};

use super::{DiscordBot, State};
use crate::{DisplayDebugThrow, ResultExt};

fn log_jni_panic(function: &str, ptr: jlong, payload: &Box<dyn Any + Send>) {
    let panic_msg = if let Some(s) = payload.downcast_ref::<&str>() {
        *s
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.as_str()
    } else {
        "<unknown panic payload>"
    };
    tracing::error!(
        function = function,
        ptr = ptr,
        thread = ?std::thread::current().id(),
        panic = %panic_msg,
        "Rust panic in {function} JNI call"
    );
}

/// Runs a JNI entry point body, turning a panic into a log line plus `on_panic`
/// (unwinding across the FFI boundary would abort the JVM).
fn guarded<T>(function: &str, ptr: jlong, on_panic: T, body: impl FnOnce() -> T) -> T {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(body)) {
        Ok(value) => value,
        Err(payload) => {
            log_jni_panic(function, ptr, &payload);
            on_panic
        }
    }
}

/// A new strong reference to the bot behind a Java-held pointer. Java keeps its
/// own reference (released only by `_free`), so dropping this one on any return
/// path, including a caught panic, can never free the bot.
///
/// # Safety
/// `ptr` must come from `_new` and not have been passed to `_free` yet.
unsafe fn bot_ref(ptr: jlong) -> Arc<DiscordBot> {
    let raw = ptr as *const DiscordBot;
    Arc::increment_strong_count(raw);
    Arc::from_raw(raw)
}

fn logged_in_http(bot: &DiscordBot) -> Option<Arc<Http>> {
    match &*bot.state.read() {
        State::LoggedIn { http } | State::Started { http, .. } => Some(http.clone()),
        _ => None,
    }
}

fn started_guild_id(bot: &DiscordBot) -> Option<serenity::all::GuildId> {
    match &*bot.state.read() {
        State::Started { guild_id, .. } => Some(*guild_id),
        _ => None,
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1new<'local>(
    mut env: JNIEnv<'local>,
    this: JObject<'local>,
    token: JString<'local>,
    category_id: jlong,
) -> jlong {
    guarded("DiscordBot__1new", 0, 0, || {
        let token = env
            .get_string(&token)
            .expect("Couldn't get java string")
            .into();
        let java_vm = env.get_java_vm().expect("Couldn't get JavaVM");
        let java_bot_obj = env.new_global_ref(this).expect("Couldn't create global ref");

        let discord_bot = Arc::new(DiscordBot::new(
            token,
            ChannelId::new(category_id as u64),
            Arc::new(java_vm),
            java_bot_obj,
        ));
        let weak = Arc::downgrade(&discord_bot);
        let ptr = Arc::into_raw(discord_bot) as jlong;
        super::BOT_REGISTRY.insert(ptr as usize, weak);
        ptr
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1isStarted(
    _env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) -> jboolean {
    guarded("DiscordBot__1isStarted", ptr, 0, || {
        let bot = unsafe { bot_ref(ptr) };
        let started = bot
            .state
            .try_read()
            .map(|state| matches!(*state, State::Started { .. }))
            .unwrap_or(false);
        started as jboolean
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1logIn(
    mut env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) {
    guarded("DiscordBot__1logIn", ptr, (), || {
        let bot = unsafe { bot_ref(ptr) };
        bot.log_in().discard_or_throw(&mut env);
    })
}

/// Returns the voice channel name, or null on failure (Java treats null as failure).
#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1start<'local>(
    mut env: JNIEnv<'local>,
    _obj: jobject,
    ptr: jlong,
) -> JString<'local> {
    guarded("DiscordBot__1start", ptr, JString::default(), || {
        if ptr == 0 {
            tracing::error!("Received null pointer in DiscordBot__1start");
            return JString::default();
        }
        let bot = unsafe { bot_ref(ptr) };
        let result = match DiscordBot::start(Arc::clone(&bot)) {
            Ok(name) => env.new_string(name).unwrap_or_default(),
            Err(e) => {
                e.throw(&mut env);
                JString::default()
            }
        };
        std::thread::spawn(|| {
            std::thread::sleep(std::time::Duration::from_millis(1000));
            crate::discord_bot::discord_speak::poke_all_audio_sources();
        });
        result
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1stop(
    mut env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) {
    guarded("DiscordBot__1stop", ptr, (), || {
        let bot = unsafe { bot_ref(ptr) };
        bot.stop().discard_or_throw(&mut env);
    })
}

/// Releases Java's reference. The bot is dropped once in-flight tasks let go of theirs.
#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1free(
    _env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) {
    guarded("DiscordBot__1free", ptr, (), || {
        // Takes over Java's reference: dropped at the end of this closure.
        let bot = unsafe { Arc::from_raw(ptr as *const DiscordBot) };
        super::BOT_REGISTRY.remove(&(ptr as usize));
        if let Some(uuid) = bot.get_audio_source_uuid() {
            crate::discord_bot::discord_speak::remove_audio_source(&uuid);
        }
        // Defensively leave any active voice call so a freed bot can never
        // linger connected to a channel (stop() is a no-op if not started).
        if let Err(e) = bot.stop() {
            tracing::warn!(?e, "Failed to stop bot while freeing it");
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1createDiscordVoiceChannel(
    mut env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
    group_name: JString<'_>,
) -> jlong {
    guarded("DiscordBot__1createDiscordVoiceChannel", ptr, 0, || {
        let Ok(group_name) = env.get_string(&group_name).map(String::from) else {
            tracing::error!("JNI: Could not get group_name string");
            return 0;
        };
        let bot = unsafe { bot_ref(ptr) };
        let Some(http) = logged_in_http(&bot) else {
            tracing::error!("JNI: Bot not logged in, cannot create channel");
            return 0;
        };
        match crate::runtime::RUNTIME.block_on(bot.create_voice_channel(&http, &group_name)) {
            Ok(channel_id) => channel_id.get() as jlong,
            Err(e) => {
                tracing::error!(?e, "JNI: Failed to create Discord voice channel");
                0
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1deleteDiscordVoiceChannel(
    _env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) {
    guarded("DiscordBot__1deleteDiscordVoiceChannel", ptr, (), || {
        let bot = unsafe { bot_ref(ptr) };
        let Some(http) = logged_in_http(&bot) else {
            tracing::error!("JNI: Bot not logged in, cannot delete channel");
            return;
        };
        let guild_id = started_guild_id(&bot);
        let _ = crate::runtime::RUNTIME.block_on(bot.delete_voice_channel(&http, guild_id));
    })
}

/// Points the bot at a pre-existing Discord voice channel (0 clears it).
#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1setManagedDiscordVoiceChannel(
    _env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
    channel_id: jlong,
) {
    guarded("DiscordBot__1setManagedDiscordVoiceChannel", ptr, (), || {
        let bot = unsafe { bot_ref(ptr) };
        *bot.channel_id.lock() = (channel_id > 0).then(|| ChannelId::new(channel_id as u64));
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1updateDiscordVoiceChannelName(
    mut env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
    new_name: JString<'_>,
) {
    guarded("DiscordBot__1updateDiscordVoiceChannelName", ptr, (), || {
        let Ok(new_name) = env.get_string(&new_name).map(String::from) else {
            tracing::error!("JNI: Could not get new_name string");
            return;
        };
        let bot = unsafe { bot_ref(ptr) };
        let Some(http) = logged_in_http(&bot) else {
            tracing::error!("JNI: Bot not logged in, cannot update channel name");
            return;
        };
        let _ = crate::runtime::RUNTIME.block_on(bot.update_voice_channel_name(&http, &new_name));
    })
}

/// Sends a text message to the voice channel's chat; returns its message ID, or 0 on failure.
#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1sendDiscordTextMessageWithId(
    mut env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
    message: JString<'_>,
) -> jlong {
    guarded("DiscordBot__1sendDiscordTextMessageWithId", ptr, 0, || {
        let Ok(message) = env.get_string(&message).map(String::from) else {
            tracing::error!("JNI: Could not get message string");
            return 0;
        };
        let bot = unsafe { bot_ref(ptr) };
        match crate::runtime::RUNTIME.block_on(bot.send_text_message_with_id(&message)) {
            Ok(id) => id.get() as jlong,
            Err(e) => {
                tracing::error!(?e, "Failed to send Discord text message with ID");
                0
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1editDiscordTextMessageAppend(
    mut env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
    message_id: jlong,
    content_to_append: JString<'_>,
) {
    guarded("DiscordBot__1editDiscordTextMessageAppend", ptr, (), || {
        let Ok(content_to_append) = env.get_string(&content_to_append).map(String::from) else {
            tracing::error!("JNI: Could not get content to append string");
            return;
        };
        let bot = unsafe { bot_ref(ptr) };
        let result = crate::runtime::RUNTIME.block_on(
            bot.append_to_text_message(MessageId::new(message_id as u64), &content_to_append),
        );
        if let Err(e) = result {
            tracing::error!(?e, "Failed to append to Discord text message");
        }
    })
}

/// Minecraft -> Discord: queues one opus frame from a player.
#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1addAudioToHearingBuffer<'local>(
    env: JNIEnv<'local>,
    _obj: jobject,
    ptr: jlong,
    player_id_bytes: JByteArray<'local>,
    raw_opus_data: JByteArray<'local>,
    sequence_number: jlong,
) {
    guarded("DiscordBot__1addAudioToHearingBuffer", ptr, (), || {
        let player_id = match env
            .convert_byte_array(player_id_bytes)
            .map_err(|e| e.to_string())
            .and_then(|bytes| uuid::Uuid::from_slice(&bytes).map_err(|e| e.to_string()))
        {
            Ok(uuid) => uuid,
            Err(e) => {
                tracing::error!("Invalid player_id bytes: {e}");
                return;
            }
        };
        let raw_opus_data = match env.convert_byte_array(raw_opus_data) {
            Ok(data) => data,
            Err(e) => {
                tracing::error!("Unable to convert opus byte array: {e:?}");
                return;
            }
        };
        let bot = unsafe { bot_ref(ptr) };
        bot.add_opus_to_playback_buffer(player_id, raw_opus_data, sequence_number as u16);
    })
}

/// Discord -> Minecraft: blocks up to 50ms for audio. Returns `byte[][][]` of
/// `[usernameUtf8, userIdLE, opus]` tuples, or null when nobody is speaking.
#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1blockForSpeakingBufferOpusData(
    mut env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) -> jobject {
    guarded("DiscordBot__1blockForSpeakingBufferOpusData", ptr, std::ptr::null_mut(), || {
        if ptr == 0 {
            tracing::error!("JNI blockForSpeakingBufferOpusData called with null pointer");
            return std::ptr::null_mut();
        }
        let bot = unsafe { bot_ref(ptr) };
        let Ok(opus_packets) = bot.block_for_speaking_opus_data() else {
            return std::ptr::null_mut();
        };
        let outer_class = env.find_class("[[B").expect("Couldn't find [[B class");
        let tuple_class = env.find_class("[B").expect("Couldn't find [B class");
        let arr = env
            .new_object_array(opus_packets.len() as i32, &outer_class, JObject::null())
            .expect("Couldn't create [[[B array");
        for (i, (username, user_id, payload)) in opus_packets.iter().enumerate() {
            let tuple = env
                .new_object_array(3, &tuple_class, JObject::null())
                .expect("Couldn't create tuple array");
            let parts: [&[u8]; 3] = [username.as_bytes(), &user_id.to_le_bytes(), payload];
            for (j, part) in parts.iter().enumerate() {
                let bytes = env.byte_array_from_slice(part).expect("Couldn't create byte array");
                env.set_object_array_element(&tuple, j as i32, &bytes)
                    .expect("Couldn't set tuple element");
                let _ = env.delete_local_ref(bytes);
            }
            env.set_object_array_element(&arr, i as i32, &tuple)
                .expect("Couldn't set tuple array element");
            let _ = env.delete_local_ref(tuple);
        }
        arr.into_raw()
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1disconnect(
    _env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) {
    guarded("DiscordBot__1disconnect", ptr, (), || {
        let bot = unsafe { bot_ref(ptr) };
        match started_guild_id(&bot) {
            Some(guild_id) => crate::runtime::RUNTIME.block_on(bot.disconnect(guild_id)),
            None => tracing::debug!("JNI: Bot is not started, nothing to disconnect"),
        }
    })
}

/// Calls a void Java method on the bot object. A Java exception is logged and
/// cleared instead of panicking the calling (gateway/runtime) thread.
pub fn call_java_void(env: &mut JNIEnv, obj: &JObject, method: &str, sig: &str, args: &[JValue]) {
    if let Err(e) = env.call_method(obj, method, sig, args) {
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
        }
        tracing::warn!(?e, "Java callback {method} failed");
    }
}

/// Notify Java when a Discord user's voice state changes (join/leave VC).
pub fn notify_java_discord_user_voice_state(
    env: &mut JNIEnv,
    java_bot_obj: &JObject,
    discord_user_id: u64,
    username: &str,
    channel_id: u64,
    joined: bool,
) {
    let Ok(username) = env.new_string(username) else {
        tracing::warn!("Failed to create Java string for username");
        return;
    };
    call_java_void(
        env,
        java_bot_obj,
        "onDiscordUserVoiceState",
        "(JLjava/lang/String;JZ)V",
        &[
            JValue::Long(discord_user_id as i64),
            JValue::Object(&username),
            JValue::Long(channel_id as i64),
            JValue::Bool(joined as u8),
        ],
    );
}

/// `String[][]` of `[filename, url]` pairs.
fn attachments_to_java<'local>(
    env: &mut JNIEnv<'local>,
    attachments: &[(String, String)],
) -> jni::errors::Result<JObjectArray<'local>> {
    let string_class = env.find_class("java/lang/String")?;
    let pair_class = env.find_class("[Ljava/lang/String;")?;
    let array = env.new_object_array(attachments.len() as i32, pair_class, JObject::null())?;
    for (i, (filename, url)) in attachments.iter().enumerate() {
        let pair = env.new_object_array(2, &string_class, JObject::null())?;
        let filename = env.new_string(filename)?;
        let url = env.new_string(url)?;
        env.set_object_array_element(&pair, 0, filename)?;
        env.set_object_array_element(&pair, 1, url)?;
        env.set_object_array_element(&array, i as i32, pair)?;
    }
    Ok(array)
}

/// Notify Java when a Discord text message is sent in a managed VC channel.
pub fn notify_java_discord_text_message<'local>(
    env: &mut JNIEnv<'local>,
    java_bot_obj: &JObject,
    author: &str,
    author_id: u64,
    message: &str,
    channel_id: u64,
    attachments: &[(String, String)],
) {
    let args = (|| {
        Ok::<_, jni::errors::Error>((
            env.new_string(author)?,
            env.new_string(message)?,
            attachments_to_java(env, attachments)?,
        ))
    })();
    let (author, message, attachments_array) = match args {
        Ok(values) => values,
        Err(e) => {
            let _ = env.exception_clear();
            tracing::warn!(?e, "Failed to build Java arguments for onDiscordTextMessage");
            return;
        }
    };
    call_java_void(
        env,
        java_bot_obj,
        "onDiscordTextMessage",
        "(Ljava/lang/String;JLjava/lang/String;J[[Ljava/lang/String;)V",
        &[
            JValue::Object(&author),
            JValue::Long(author_id as i64),
            JValue::Object(&message),
            JValue::Long(channel_id as i64),
            JValue::Object(&attachments_array),
        ],
    );
}
