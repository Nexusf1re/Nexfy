package dev.nexfy.app.ui.design

import coil3.PlatformContext

/**
 * The cover at [url], decoded small and handed over as 0xRRGGBB pixels for
 * [quantize]. Null when it does not load.
 *
 * Both sides fetch through Coil, so a cover already on screen is decoded
 * once; only reading the pixels back differs, Android's Bitmap against
 * Skia's.
 */
expect suspend fun coverPixels(context: PlatformContext, url: String, size: Int): IntArray?
