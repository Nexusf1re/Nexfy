package dev.nexfy.app.ui.design

import coil3.PlatformContext

/** No desktop has an animator duration scale; motion follows the app's own setting. */
actual fun systemAnimationsOff(context: PlatformContext): Boolean = false

/** Laptops have battery savers, but none a desktop JVM can read portably. */
actual fun powerSaveMode(context: PlatformContext): Boolean = false
