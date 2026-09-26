//! fkcore — JNI bridge exposing the leaf proxy engine to the Android app.
//!
//! Kotlin declares matching `external fun`s in `FkNative`, and the JVM resolves
//! them to the `Java_com_example_fkvpn_FkNative_*` symbols below via
//! `System.loadLibrary("fkcore")`.
//!
//! Flow on Android:
//!   1. `FkVpnService` builds the VPN and gets a TUN file descriptor from
//!      `VpnService.establish()`.
//!   2. It writes a leaf config containing `tun-fd = <that fd>`.
//!   3. It calls `runLeaf(configPath, rtId)` here, which blocks on the engine.
//!   4. `shutdown(rtId)` stops it.

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use leaf::{Config, RuntimeOption, StartOptions};

/// Initialise logcat forwarding once when the library is loaded.
#[no_mangle]
pub extern "system" fn JNI_OnLoad(
    _vm: jni::sys::JavaVM,
    _reserved: *mut std::os::raw::c_void,
) -> jint {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("fkcore"),
    );
    log::info!("fkcore loaded");
    jni::sys::JNI_VERSION_1_6
}

/// Runs the engine with the given config file. Blocks until shutdown, so the
/// caller must invoke it on a dedicated (background) thread.
///
/// Returns 0 on clean finish, non-zero on error.
#[no_mangle]
pub extern "system" fn Java_com_example_fkvpn_FkNative_runLeaf(
    mut env: JNIEnv,
    _class: JClass,
    config_path: JString,
    rt_id: jint,
) -> jint {
    let path: String = match env.get_string(&config_path) {
        Ok(s) => s.into(),
        Err(_) => {
            log::error!("runLeaf: invalid config path string");
            return 1;
        }
    };

    log::info!("runLeaf: starting rt_id={} config={}", rt_id, path);

    let opts = StartOptions {
        config: Config::File(path),
        runtime_opt: RuntimeOption::SingleThread,
    };

    match leaf::start(rt_id as u16, opts) {
        Ok(()) => {
            log::info!("runLeaf: rt_id={} finished", rt_id);
            0
        }
        Err(e) => {
            log::error!("runLeaf: rt_id={} error: {}", rt_id, e);
            2
        }
    }
}

/// Stops the engine identified by `rt_id`. Returns true on success.
#[no_mangle]
pub extern "system" fn Java_com_example_fkvpn_FkNative_shutdown(
    _env: JNIEnv,
    _class: JClass,
    rt_id: jint,
) -> jboolean {
    log::info!("shutdown: rt_id={}", rt_id);
    if leaf::shutdown(rt_id as u16) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Reloads outbounds/routing from the config file without restarting.
#[no_mangle]
pub extern "system" fn Java_com_example_fkvpn_FkNative_reload(
    _env: JNIEnv,
    _class: JClass,
    rt_id: jint,
) -> jint {
    match leaf::reload(rt_id as u16) {
        Ok(()) => 0,
        Err(e) => {
            log::error!("reload: rt_id={} error: {}", rt_id, e);
            2
        }
    }
}

/// Returns true if the engine with `rt_id` is currently running.
#[no_mangle]
pub extern "system" fn Java_com_example_fkvpn_FkNative_isRunning(
    _env: JNIEnv,
    _class: JClass,
    rt_id: jint,
) -> jboolean {
    if leaf::is_running(rt_id as u16) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
