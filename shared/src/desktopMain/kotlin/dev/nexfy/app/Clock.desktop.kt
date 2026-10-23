package dev.nexfy.app

/** A PC does not deep-sleep behind the app's back the way a phone does. */
actual fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000
