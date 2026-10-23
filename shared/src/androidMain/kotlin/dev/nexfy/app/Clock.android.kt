package dev.nexfy.app

import android.os.SystemClock

actual fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
