use std::fmt::{Debug, Display};

use jni::{objects::JClass, JNIEnv};
use once_cell::sync::Lazy;
use runtime::RUNTIME;
use tracing::{info, warn};

mod audio_util;
mod discord_bot;
mod logging;
mod runtime;

trait DisplayDebugThrow: Display + Debug {
    fn throw(&self, env: &mut JNIEnv<'_>) {
        let _ = env.throw(("java/lang/Exception", format!("{self:#}")));
    }
}

impl<T: Display + Debug> DisplayDebugThrow for T {}

trait ResultExt {
    /// Discards the result if it is `Ok`, otherwise throws the error into Java.
    fn discard_or_throw(self, env: &mut JNIEnv<'_>);
}

impl<T, E: Display + Debug> ResultExt for Result<T, E> {
    fn discard_or_throw(self, env: &mut JNIEnv<'_>) {
        if let Err(e) = self {
            e.throw(env);
        }
    }
}

/// Windows counterpart of build.rs's `-z nodelete`: pin this DLL so FreeLibrary
/// (run when the old plugin classloader is collected after a reload) never unloads it.
#[cfg(windows)]
fn pin_library() {
    #[link(name = "kernel32")]
    extern "system" {
        fn GetModuleHandleExW(flags: u32, module_name: *const u16, module: *mut *mut std::ffi::c_void) -> i32;
    }
    const GET_MODULE_HANDLE_EX_FLAG_PIN: u32 = 0x1;
    const GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS: u32 = 0x4;
    let mut module = std::ptr::null_mut();
    let ok = unsafe {
        GetModuleHandleExW(
            GET_MODULE_HANDLE_EX_FLAG_PIN | GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,
            pin_library as *const u16,
            &mut module,
        )
    };
    if ok == 0 {
        warn!("Failed to pin the native library; a plugin reload could crash the JVM");
    }
}

#[cfg(not(windows))]
fn pin_library() {}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_Core_initializeNatives<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    logging::ensure_init();
    pin_library();
    info!("Initializing rustls");
    if rustls::crypto::ring::default_provider()
        .install_default()
        .is_err()
    {
        warn!("rustls already has a default provider. This is probably fine");
    }
    // don't initialize the runtime here, in case it is never used
}

#[no_mangle]
pub extern "system" fn Java_dev_amsam0_voicechatdiscord_Core_shutdownNatives<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    if Lazy::get(&RUNTIME).is_some() {
        RUNTIME.shutdown();
    } else {
        info!("Runtime not yet initialized, so no need to shutdown");
    }
}
