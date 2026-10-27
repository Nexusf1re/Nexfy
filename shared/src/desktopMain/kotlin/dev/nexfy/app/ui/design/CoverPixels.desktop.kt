package dev.nexfy.app.ui.design

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The cover's pixels, read off the window's thread: a pixel at a time is a
 * native call each, and a shelf of covers arriving together while the page
 * scrolled cost the frames of the scroll.
 */
actual suspend fun coverPixels(context: PlatformContext, url: String, size: Int): IntArray? {
    val request = ImageRequest.Builder(context).data(url).size(size).build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    return withContext(Dispatchers.Default) {
        val bitmap = result.image.toBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        var at = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) pixels[at++] = bitmap.getColor(x, y) and 0xFFFFFF
        }
        pixels
    }
}
