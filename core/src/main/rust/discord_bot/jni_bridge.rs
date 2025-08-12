use jni::{
    objects::{JByteArray, JString},
    sys::{jboolean, jlong, jobject},
    JNIEnv,
};
use serenity::all::ChannelId;
use std::sync::Arc;

use crate::discord_bot::State;
use crate::ResultExt;

use super::DiscordBot;

use jni::objects::JObject;

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1new<'local>(
    mut env: JNIEnv<'local>,
    this: JObject<'local>,
    token: JString<'local>,
    category_id: jlong,
) -> jlong {
    let token = env
        .get_string(&token)
        .expect("Couldn't get java string! Please file a GitHub issue")
        .into();

    let java_vm = env.get_java_vm().expect("Couldn't get JavaVM");
    let java_bot_obj = env.new_global_ref(this).expect("Couldn't create global ref");

    let discord_bot = Arc::new(DiscordBot::new(
        token,
        ChannelId::new(category_id as u64),
        Arc::new(java_vm),
        java_bot_obj,
    ));
    Arc::into_raw(discord_bot) as jlong
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1isStarted(
    mut _env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) -> jboolean {
    let discord_bot = unsafe { Arc::from_raw(ptr as *const DiscordBot) };
    let result = {
        if let Some(lock) = discord_bot.state.try_read() {
            matches!(*lock, State::Started { .. }) as jboolean
        } else {
            0 as jboolean
        }
    };
    let _ = Arc::into_raw(discord_bot);
    result
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1logIn(
    mut env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) {
    let discord_bot = unsafe { Arc::from_raw(ptr as *const DiscordBot) };
    discord_bot.log_in().discard_or_throw(&mut env);
    let _ = Arc::into_raw(discord_bot);
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1start(
    mut env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) -> JString<'_> {
    tracing::info!("JNI DiscordBot__1start called with ptr: {:#x}", ptr);
    if ptr == 0 {
        tracing::error!("Received null pointer in DiscordBot__1start");
        let value_on_throw = env
            .new_string("<null pointer error>")
            .expect("Couldn't create java string! Please file a GitHub issue");
        return value_on_throw;
    }

    // SAFETY: We temporarily wrap the pointer in an Arc, call start, then restore the raw pointer
    let discord_bot = unsafe { Arc::from_raw(ptr as *const DiscordBot) };
    tracing::info!("Arc::from_raw succeeded, calling DiscordBot::start");

    let value_on_throw = env
        .new_string("<error>")
        .expect("Couldn't create java string! Please file a GitHub issue");

    let result = super::DiscordBot::start(Arc::clone(&discord_bot))
        .map(|s| {
            tracing::info!("DiscordBot::start succeeded, channel name: {}", s);
            env.new_string(s)
                .expect("Couldn't create java string! Please file a GitHub issue")
        })
        .unwrap_or_throw(&mut env, value_on_throw);
    tracing::info!("JNI DiscordBot__1start returning result");
    let _ = Arc::into_raw(discord_bot);
    result
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1stop(
    mut env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) {
    let discord_bot = unsafe { Arc::from_raw(ptr as *const DiscordBot) };
    discord_bot.stop().discard_or_throw(&mut env);
    let _ = Arc::into_raw(discord_bot);
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1free(
    mut _env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) {
    let _ = unsafe { Arc::from_raw(ptr as *const DiscordBot) };
}

// JNI: Create Discord voice channel for this bot instance
#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1createDiscordVoiceChannel(
    mut env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
    group_name: JString<'_>,
) -> jlong {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let group_name: String = match env.get_string(&group_name) {
            Ok(s) => s.into(),
            Err(_) => {
                tracing::error!("JNI: Could not get group_name string");
                return 0;
            }
        };
        let discord_bot = unsafe { Arc::from_raw(ptr as *const super::DiscordBot) };
        let result = {
            // Get HTTP client from state
            let state = discord_bot.state.read();
            let http = match &*state {
                super::State::LoggedIn { http } | super::State::Started { http, .. } => http.clone(),
                _ => {
                    tracing::error!("JNI: Bot not logged in, cannot create channel");
                    // Only safe to call into_raw after state is dropped
                    drop(state);
                    let _ = Arc::into_raw(discord_bot);
                    return 0;
                }
            };
            // SAFETY: We must not hold the lock across await
            drop(state);
            // Run async channel creation
            match crate::runtime::RUNTIME.block_on(async {
                discord_bot.create_voice_channel(&http, &group_name).await
            }) {
                Ok(channel_id) => channel_id.get() as jlong,
                Err(e) => {
                    tracing::error!(?e, "JNI: Failed to create Discord voice channel");
                    0
                }
            }
        };
        // Only call into_raw after all borrows are done
        let _ = Arc::into_raw(discord_bot);
        result
    }));
    match result {
        Ok(val) => val,
        Err(_) => {
            tracing::error!("Rust panic in createDiscordVoiceChannel JNI call");
            0
        }
    }
}

// JNI: Delete Discord voice channel for this bot instance
#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1deleteDiscordVoiceChannel(
    mut _env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) {
    tracing::info!("JNI: Java_dev_amsam0_voicechatdiscord_DiscordBot__1deleteDiscordVoiceChannel called for ptr={:#x}", ptr);
    let discord_bot = unsafe { Arc::from_raw(ptr as *const super::DiscordBot) };
    let state = discord_bot.state.read();
    let http = match &*state {
        super::State::LoggedIn { http } | super::State::Started { http, .. } => http.clone(),
        _ => {
            tracing::error!("JNI: Bot not logged in, cannot delete channel");
            // Only safe to call into_raw after state is dropped
            drop(state);
            let _ = Arc::into_raw(discord_bot);
            return;
        }
    };
    drop(state);
    // Try to get guild_id from bot state if available
    let guild_id = {
        let state = discord_bot.state.read();
        match &*state {
            super::State::Started { guild_id, .. } => Some(*guild_id),
            _ => None,
        }
    };
    tracing::info!("JNI: Calling Rust delete_voice_channel with guild_id={:?}", guild_id);
    let _ = crate::runtime::RUNTIME.block_on(async {
        discord_bot.delete_voice_channel(&http, guild_id).await
    });
    tracing::info!("JNI: Rust delete_voice_channel finished with with guild_id={:?}", guild_id);
    // Only call into_raw after all borrows are done
    let _ = Arc::into_raw(discord_bot);
}

// JNI: Update Discord voice channel name for this bot instance
#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1updateDiscordVoiceChannelName(
    mut env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
    new_name: JString<'_>,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let new_name: String = match env.get_string(&new_name) {
            Ok(s) => s.into(),
            Err(_) => {
                tracing::error!("JNI: Could not get new_name string");
                return;
            }
        };
        let discord_bot = unsafe { Arc::from_raw(ptr as *const super::DiscordBot) };
        let state = discord_bot.state.read();
        let http = match &*state {
            super::State::LoggedIn { http } | super::State::Started { http, .. } => http.clone(),
            _ => {
                tracing::error!("JNI: Bot not logged in, cannot update channel name");
                drop(state);
                let _ = Arc::into_raw(discord_bot);
                return;
            }
        };
        drop(state);
        let _ = crate::runtime::RUNTIME.block_on(async {
            let _ = discord_bot.update_voice_channel_name(&http, &new_name).await;
        });
        let _ = Arc::into_raw(discord_bot);
    }));
    if result.is_err() {
        tracing::error!("Rust panic in updateDiscordVoiceChannelName JNI call");
    }
}

// JNI: Send a text message to the Discord voice channel's text chat
#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1sendDiscordTextMessage(
    mut env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
    message: JString<'_>,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let message: String = match env.get_string(&message) {
            Ok(s) => s.into(),
            Err(_) => {
                tracing::error!("JNI: Could not get message string");
                return;
            }
        };
        let discord_bot = unsafe { Arc::from_raw(ptr as *const super::DiscordBot) };
        let send_result = crate::runtime::RUNTIME.block_on(async {
            discord_bot.send_text_message(&message).await
        });
        if let Err(e) = send_result {
            tracing::error!(?e, "Failed to send Discord text message");
        }
        let _ = Arc::into_raw(discord_bot);
    }));
    if result.is_err() {
        tracing::error!("Rust panic in sendDiscordTextMessage JNI call");
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1addAudioToHearingBuffer<'local>(
    env: JNIEnv<'local>,
    _obj: jobject,
    ptr: jlong,
    player_id_bytes: JByteArray<'local>,
    raw_opus_data: JByteArray<'local>,
    sequence_number: jlong,
) {
    let discord_bot = unsafe { Arc::from_raw(ptr as *const DiscordBot) };

    let player_id = match env.convert_byte_array(player_id_bytes) {
        Ok(bytes) => match uuid::Uuid::from_slice(&bytes) {
            Ok(uuid) => uuid,
            Err(e) => {
                tracing::error!("Invalid player_id bytes: {:?}", e);
                let _ = Arc::into_raw(discord_bot);
                return;
            }
        },
        Err(e) => {
            tracing::error!("Unable to convert player_id bytes: {:?}", e);
            let _ = Arc::into_raw(discord_bot);
            return;
        }
    };

    let raw_opus_data = match env.convert_byte_array(raw_opus_data) {
        Ok(data) => data,
        Err(e) => {
            tracing::error!("Unable to convert opus byte array: {:?}", e);
            let _ = Arc::into_raw(discord_bot);
            return;
        }
    };

    // This is Minecraft -> Discord, so use player_to_discord_buffers
    let seq = sequence_number as u16;
    discord_bot.add_opus_to_playback_buffer(player_id, raw_opus_data, seq);

    let _ = Arc::into_raw(discord_bot);
}


#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1blockForSpeakingBufferOpusData(
    mut env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) -> jobject {
    use jni::objects::JObject;

    if ptr == 0 {
        tracing::error!("JNI blockForSpeakingBufferOpusData called with null pointer");
        return std::ptr::null_mut();
    }

    // Only catch panics around the Rust logic, not JNI calls
    let rust_result = std::panic::catch_unwind(|| {
        let discord_bot = unsafe { Arc::from_raw(ptr as *const DiscordBot) };
        let opus_packets = discord_bot.block_for_speaking_opus_data();
        let _ = Arc::into_raw(discord_bot);
        opus_packets
    });

    match rust_result {
        Ok(Ok(opus_packets)) => {
            // Create the main array as [[[B]] (3D byte array)
            let tuple_arr_class = env.find_class("[[B").expect("Couldn't find [[B class");
            let arr = env.new_object_array(opus_packets.len() as i32, tuple_arr_class, JObject::null()).expect("Couldn't create main [[[B array");
            for (i, (username, payload)) in opus_packets.iter().enumerate() {
                let username_bytes = username.as_bytes();
                let j_username = env.byte_array_from_slice(username_bytes).expect("Couldn't create byte array from username");
                let j_payload = env.byte_array_from_slice(payload).expect("Couldn't create byte array from payload");
                // Create a 2D byte array (byte[][]) for the tuple [usernameBytes, opusBytes]
                let tuple_arr_class = env.find_class("[B").expect("Couldn't find [B class");
                let tuple_arr = env.new_object_array(2, tuple_arr_class, JObject::null()).expect("Couldn't create tuple array");
                env.set_object_array_element(&tuple_arr, 0, JObject::from(j_username)).expect("Couldn't set username element");
                env.set_object_array_element(&tuple_arr, 1, JObject::from(j_payload)).expect("Couldn't set payload element");
                env.set_object_array_element(&arr, i as i32, JObject::from(tuple_arr)).expect("Couldn't set tuple array element");
            }
            arr.into_raw()
        },
        Ok(Err(_)) => {
            std::ptr::null_mut()
        },
        Err(_) => {
            tracing::error!("Panic in blockForSpeakingBufferOpusData for ptr: {:#x}", ptr);
            std::ptr::null_mut()
        }
    }
}

/// Notify Java when a Discord user's voice state changes (join/leave VC).
pub fn notify_java_discord_user_voice_state(
    env: &mut jni::JNIEnv,
    java_bot_obj: &jni::objects::JObject,
    discord_user_id: u64,
    username: &str,
    channel_id: u64,
    joined: bool,
) {
    let username_jstring = env.new_string(username).expect("Failed to create Java string for username");
    env.call_method(
        java_bot_obj,
        "onDiscordUserVoiceState",
        "(JLjava/lang/String;JZ)V",
        &[
            jni::objects::JValue::Long(discord_user_id as i64),
            jni::objects::JValue::Object(&jni::objects::JObject::from(username_jstring)),
            jni::objects::JValue::Long(channel_id as i64),
            jni::objects::JValue::Bool(joined as u8),
        ],
    ).expect("Failed to call onDiscordUserVoiceState on Java side");
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1disconnect(
    mut _env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) {
    tracing::info!("JNI: Java_dev_amsam0_voicechatdiscord_DiscordBot__1disconnect called for ptr={:#x}", ptr);
    let discord_bot = unsafe { Arc::from_raw(ptr as *const super::DiscordBot) };
    // Try to get guild_id from bot state if available
    let guild_id = {
        let state = discord_bot.state.read();
        match &*state {
            super::State::Started { guild_id, .. } => Some(*guild_id),
            _ => None,
        }
    };
    if let Some(guild_id) = guild_id {
        let _ = crate::runtime::RUNTIME.block_on(async {
            discord_bot.disconnect(guild_id).await
        });
    } else {
        tracing::warn!("JNI: Bot is not in a started state, cannot disconnect from voice channel");
    }
    let _ = Arc::into_raw(discord_bot);
}
