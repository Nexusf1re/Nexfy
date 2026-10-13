package dev.sonata.core

import android.content.Context

/** One-time native setup: logcat output and the Context cpal's AAudio backend needs. */
object NativeInit {
    init {
        System.loadLibrary("sonata_core")
    }

    @JvmStatic
    external fun init(context: Context)
}
