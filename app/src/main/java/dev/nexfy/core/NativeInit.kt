package dev.nexfy.core

import android.content.Context

/** One-time native setup: logcat output and the Context cpal's AAudio backend needs. */
object NativeInit {
    init {
        System.loadLibrary("nexfy_core")
    }

    @JvmStatic
    external fun init(context: Context)
}
