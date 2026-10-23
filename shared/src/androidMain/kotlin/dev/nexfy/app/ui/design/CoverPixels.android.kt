package dev.nexfy.app.ui.design

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

/** Hardware bitmaps live on the GPU and cannot be read back, so not those. */
actual suspend fun coverPixels(context: PlatformContext, url: String, size: Int): IntArray? {
    val request = ImageRequest.Builder(context).data(url).size(size).allowHardware(false).build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    val bitmap = result.image.toBitmap()
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    for (i in pixels.indices) pixels[i] = pixels[i] and 0xFFFFFF
    return pixels
}
