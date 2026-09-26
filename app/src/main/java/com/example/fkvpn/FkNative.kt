package com.example.fkvpn

/**
 * JNI bridge to the Rust proxy core (libfkcore.so).
 *
 * Each `external fun` resolves to a `Java_com_example_fkvpn_FkNative_*`
 * symbol in the native library. See rust/src/lib.rs.
 */
object FkNative {
    init {
        System.loadLibrary("fkcore")
    }

    /**
     * Starts the engine with the given config file. BLOCKS until [shutdown],
     * so callers must run it on a dedicated background thread.
     *
     * @return 0 on clean finish, non-zero on error.
     */
    external fun runLeaf(configPath: String, rtId: Int): Int

    /** Stops the engine instance. Returns true on success. */
    external fun shutdown(rtId: Int): Boolean

    /** Reloads outbounds/routing from the config file. Returns 0 on success. */
    external fun reload(rtId: Int): Int

    /** Whether the engine instance is currently running. */
    external fun isRunning(rtId: Int): Boolean
}
