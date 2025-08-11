use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jboolean, jlong, jobject},
    JNIEnv,
};
use serenity::all::ChannelId;
use std::sync::Arc;

use crate::discord_bot::State;
use crate::ResultExt;

use super::DiscordBot;

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_DiscordBot__1new<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    token: JString<'local>,
    category_id: jlong,
) -> jlong {
    let token = env
        .get_string(&token)
        .expect("Couldn't get java string! Please file a GitHub issue")
        .into();

    let discord_bot = Arc::new(DiscordBot::new(token, ChannelId::new(category_id as u64)));
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
    _env: JNIEnv<'_>,
    _obj: jobject,
    ptr: jlong,
) {
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
    let _ = crate::runtime::RUNTIME.block_on(async {
        discord_bot.delete_voice_channel(&http).await
    });
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
        Ok(data) => {
            // --- Packet rate logging ---
            {
                use std::time::Instant;
                let now = Instant::now();
                static PACKET_TIMESTAMPS: once_cell::sync::Lazy<std::sync::Mutex<Vec<Instant>>> = once_cell::sync::Lazy::new(|| std::sync::Mutex::new(Vec::new()));
                let mut timestamps = PACKET_TIMESTAMPS.lock().unwrap();
                timestamps.push(now);
                let cutoff = now - std::time::Duration::from_millis(500);
                while !timestamps.is_empty() && timestamps[0] < cutoff {
                    timestamps.remove(0);
                }
                let rate = timestamps.len() as f64 * 2.0;
                tracing::info!("Adding new packet to player_id: {} (length: {}) | [PacketRate] {:.1} packets/sec (rolling 0.5s window)", player_id, data.len(), rate);
            }
            data
        }
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
        // Return an empty Java array of byte arrays
        let empty = env.new_byte_array(0).expect("Couldn't create empty byte array");
        let byte_array_class = env.find_class("[B").unwrap();
        let arr = env.new_object_array(0, byte_array_class, empty).unwrap();
        return arr.into_raw();
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
            tracing::info!("JNI: Got Discord audio ({} packets) for bot ptr={:#x}", opus_packets.len(), ptr);
            let byte_array_class = env.find_class("[B").unwrap();
            let empty = env.new_byte_array(0).unwrap();
            let arr = env.new_object_array(opus_packets.len() as i32, byte_array_class, empty).unwrap();
            for (i, packet) in opus_packets.iter().enumerate() {
                let jpacket = env.byte_array_from_slice(packet).expect("Couldn't create byte array from slice");
                env.set_object_array_element(&arr, i as i32, JObject::from(jpacket)).expect("Couldn't set array element");
            }
            arr.into_raw()
        },
        Ok(Err(_)) => {
            let byte_array_class = env.find_class("[B").unwrap();
            let empty = env.new_byte_array(0).unwrap();
            let arr = env.new_object_array(0, byte_array_class, empty).unwrap();
            arr.into_raw()
        },
        Err(_) => {
            tracing::error!("Panic in blockForSpeakingBufferOpusData for ptr: {:#x}", ptr);
            let byte_array_class = env.find_class("[B").unwrap();
            let empty = env.new_byte_array(0).unwrap();
            let arr = env.new_object_array(0, byte_array_class, empty).unwrap();
            arr.into_raw()
        }
    }
}
