use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jboolean, jdouble, jint, jlong, jobject, JNI_TRUE},
    JNIEnv,
};
use serenity::all::ChannelId;
use std::sync::Arc;
use tracing::info;

use crate::ResultExt;

use super::DiscordBot;

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1new<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    token: JString<'local>,
    vc_id: jlong,
) -> jlong {
    let token = env
        .get_string(&token)
        .expect("Couldn't get java string! Please file a GitHub issue")
        .into();

    let discord_bot = Arc::new(std::sync::Mutex::new(DiscordBot::new(token, ChannelId::new(vc_id as u64))));
    Arc::into_raw(discord_bot) as jlong
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1isStarted(
    mut _env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) -> jboolean {
    let discord_bot = unsafe { Arc::from_raw(ptr as *const std::sync::Mutex<DiscordBot>) };
    let result = discord_bot.lock().unwrap().is_started() as jboolean;
    let _ = Arc::into_raw(discord_bot);
    result
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1logIn(
    mut env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) {
    let discord_bot = unsafe { Arc::from_raw(ptr as *const std::sync::Mutex<DiscordBot>) };
    discord_bot.lock().unwrap().log_in().discard_or_throw(&mut env);
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
    let discord_bot = unsafe { Arc::from_raw(ptr as *const std::sync::Mutex<DiscordBot>) };
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
    let discord_bot = unsafe { Arc::from_raw(ptr as *const std::sync::Mutex<DiscordBot>) };
    discord_bot.lock().unwrap().stop().discard_or_throw(&mut env);
    let _ = Arc::into_raw(discord_bot);
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1free(
    mut _env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) {
    let _ = unsafe { Arc::from_raw(ptr as *const std::sync::Mutex<DiscordBot>) };
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1addAudioToHearingBuffer<
    'local,
>(
    env: JNIEnv<'local>,
    _obj: jobject,
    ptr: jlong,
    sender_id: jint,
    raw_opus_data: JByteArray<'local>,
    adjust_based_on_distance: jboolean,
    distance: jdouble,
    max_distance: jdouble,
) {
    let discord_bot = unsafe { Arc::from_raw(ptr as *const std::sync::Mutex<DiscordBot>) };

    let raw_opus_data = env
        .convert_byte_array(raw_opus_data)
        .expect("Unable to convert byte array. Please file a GitHub issue");

    if let Err(e) = discord_bot.lock().unwrap().add_audio_to_hearing_buffer(
        sender_id,
        raw_opus_data,
        adjust_based_on_distance == JNI_TRUE,
        distance,
        max_distance,
    ) {
        info!(
            "Error when adding audio for bot with vc_id {}: {e:#}",
            discord_bot.lock().unwrap().vc_id
        );
    }
    let _ = Arc::into_raw(discord_bot);
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1blockForSpeakingBufferOpusData(
    env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) -> JByteArray<'_> {
    let discord_bot = unsafe { Arc::from_raw(ptr as *const std::sync::Mutex<DiscordBot>) };

    let opus_data = {
        let guard = discord_bot.lock().unwrap();
        match guard.block_for_speaking_opus_data() {
            Ok(d) => d,
            Err(e) => {
                info!(
                    "Failed to get speaking opus data for bot with vc_id {}: {e:#}",
                    guard.vc_id
                );
                return env
                    .byte_array_from_slice(&[])
                    .expect("Couldn't create byte array from slice. Please file a GitHub issue");
            }
        }
    };

    let result = env.byte_array_from_slice(&opus_data)
        .expect("Couldn't create byte array from slice. Please file a GitHub issue");
    let _ = Arc::into_raw(discord_bot);
    result
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1resetSenders(
    _env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) {
    let discord_bot = unsafe { Arc::from_raw(ptr as *const std::sync::Mutex<DiscordBot>) };

    if let Err(e) = discord_bot.lock().unwrap().reset_senders() {
        info!(
            "Error for bot with vc_id {} when resetting senders: {e:#}",
            discord_bot.lock().unwrap().vc_id
        );
    }
    let _ = Arc::into_raw(discord_bot);
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1addPlayerToGroup(
    _env: JNIEnv,
    _obj: jobject,
    ptr: jlong,
    sender_id: jint,
) {
    let discord_bot = unsafe { Arc::from_raw(ptr as *const std::sync::Mutex<super::DiscordBot>) };
    discord_bot.lock().unwrap().add_player_to_group(sender_id);
    let _ = Arc::into_raw(discord_bot);
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1removePlayerFromGroup(
    _env: JNIEnv,
    _obj: jobject,
    ptr: jlong,
    sender_id: jint,
) {
    let discord_bot = unsafe { Arc::from_raw(ptr as *const std::sync::Mutex<super::DiscordBot>) };
    discord_bot.lock().unwrap().remove_player_from_group(sender_id);
    let _ = Arc::into_raw(discord_bot);
}
