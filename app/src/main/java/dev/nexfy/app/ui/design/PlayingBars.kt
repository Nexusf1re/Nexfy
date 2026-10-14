package dev.nexfy.app.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Whether the current song is playing (not paused), for the bars on its row. */
val LocalPlaying = compositionLocalOf { true }

/**
 * Three bars that bounce while the song plays and rest low when paused,
 * shown on the row of the playing song.
 */
@Composable
fun PlayingBars(color: Color, modifier: Modifier) {
    val playing = LocalPlaying.current
    val bars = remember { List(3) { Animatable(.3f) } }
    LaunchedEffect(playing) {
        coroutineScope {
            bars.forEachIndexed { i, bar ->
                launch {
                    if (!playing) {
                        bar.animateTo(.22f + i % 2 * .12f, tween(260))
                        return@launch
                    }
                    val random = Random(i)
                    while (true) {
                        bar.animateTo(.25f + random.nextFloat() * .75f, tween(220 + random.nextInt(200), easing = LinearEasing))
                    }
                }
            }
        }
    }
    Canvas(modifier) {
        val gap = size.width * .16f
        val w = (size.width - gap * 2) / 3
        bars.forEachIndexed { i, bar ->
            val h = size.height * bar.value
            drawRoundRect(color, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w / 2))
        }
    }
}
