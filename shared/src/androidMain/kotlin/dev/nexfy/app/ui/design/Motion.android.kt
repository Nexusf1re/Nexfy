package dev.nexfy.app.ui.design

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import coil3.PlatformContext

actual fun systemAnimationsOff(context: PlatformContext): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

actual fun powerSaveMode(context: PlatformContext): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
