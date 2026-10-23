package dev.nexfy.app.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlin.math.sin

/** Whether the current song is playing (not paused), for the bars on its row. */
val LocalPlaying = compositionLocalOf { true }

/**
 * Milliseconds for ambient motion (the living backdrop, the Ken Burns drift,
 * the playing bars), moved on about [fps] times a second. Motion this slow
 * looks the same at 30 fps, and the app draws a quarter of the frames it
 * would at 120 Hz. Read it while drawing, so it never recomposes; it stops
 * with the frame clock while the app is hidden.
 */
@Composable
fun ambientClock(fps: Int = 30): State<Long> {
    val ms = remember { mutableLongStateOf(0L) }
    LaunchedEffect(fps) {
        val step = 1000L / fps
        while (true) {
            withFrameMillis { ms.longValue = it }
            delay(step)
        }
    }
    return ms
}

/**
 * Three bars that bounce while the song plays and rest low when paused,
 * shown on the row of the playing song.
 */
@Composable
fun PlayingBars(color: Color, modifier: Modifier) {
    val playing = LocalPlaying.current
    // How much they bounce: eases to rest on pause and back on play.
    val swing by animateFloatAsState(if (playing) 1f else 0f, tween(260), label = "bars")
    val clock = if (playing || swing > 0f) ambientClock() else null
    Canvas(modifier) {
        val t = (clock?.value ?: 0L) / 1000f
        val gap = size.width * .16f
        val w = (size.width - gap * 2) / 3
        for (i in 0 until 3) {
            val rest = .22f + i % 2 * .12f
            // Two sines a bar, at speeds of their own: a bounce that never repeats in step.
            val bounce = .25f + .75f * ((sin(t * (7.1f + i * 1.9f) + i * 2.1f) + sin(t * (4.3f + i * 2.7f) + i)) / 4f + .5f)
            val h = size.height * (rest + (bounce - rest) * swing)
            drawRoundRect(color, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w / 2))
        }
    }
}
