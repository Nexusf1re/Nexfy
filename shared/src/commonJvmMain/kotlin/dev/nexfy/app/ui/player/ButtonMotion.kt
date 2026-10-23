package dev.nexfy.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.nexfy.app.ui.design.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A one-shot 0 to 1 run, started by [play]; tapping again restarts it
 * rather than stacking. Still with reduced motion.
 */
class Kick(private val scope: CoroutineScope, private val ms: Int) {
    val value = Animatable(1f)

    fun play() {
        if (Motion.reduced) return
        scope.launch {
            value.snapTo(0f)
            value.animateTo(1f, tween(ms, easing = LinearEasing))
        }
    }

    /** Running, so it is worth drawing. */
    val running get() = value.value < 1f
}

@Composable
fun rememberKick(ms: Int): Kick {
    val scope = rememberCoroutineScope()
    return remember(ms) { Kick(scope, ms) }
}

/**
 * Play and pause as one shape: the two bars of pause fold into the two
 * halves of the play triangle and back, instead of swapping icons.
 */
@Composable
fun PlayPauseGlyph(playing: Boolean, color: Color, modifier: Modifier) {
    val toPlay by animateFloatAsState(if (playing) 0f else 1f, Motion.settle(500f), label = "playPause")
    val path = remember { Path() }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = toPlay.coerceIn(0f, 1f)
        fun pt(pause: Offset, play: Offset) = Offset((pause.x + (play.x - pause.x) * p) * w, (pause.y + (play.y - pause.y) * p) * h)
        path.reset()
        // Left bar into the triangle's left half, right bar into its tip; corners in order.
        val halves = listOf(
            listOf(Offset(.25f, .17f) to Offset(.26f, .12f), Offset(.40f, .17f) to Offset(.57f, .31f), Offset(.40f, .83f) to Offset(.57f, .69f), Offset(.25f, .83f) to Offset(.26f, .88f)),
            listOf(Offset(.60f, .17f) to Offset(.57f, .31f), Offset(.75f, .17f) to Offset(.88f, .5f), Offset(.75f, .83f) to Offset(.88f, .5f), Offset(.60f, .83f) to Offset(.57f, .69f)),
        )
        for (corners in halves) {
            corners.forEachIndexed { i, (a, b) ->
                val o = pt(a, b)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            path.close()
        }
        drawPath(path, color)
        // A round stroke over the fill softens every corner, as the system icons are.
        drawPath(path, color, style = Stroke(width = w * .07f, join = StrokeJoin.Round))
    }
}

/** A ring that leaves the button's edge and fades, after a tap ([kick]). */
fun Modifier.pressWave(kick: Kick, color: Color = Color.White): Modifier = drawBehind {
    if (!kick.running) return@drawBehind
    val k = kick.value.value
    val grow = 14.dp.toPx() * k
    val r = size.height / 2 + grow
    drawRoundRect(
        color.copy(alpha = .35f * (1f - k)),
        topLeft = Offset(-grow, -grow),
        size = androidx.compose.ui.geometry.Size(size.width + grow * 2, size.height + grow * 2),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = 2.dp.toPx() * (1f - k * .5f)),
    )
}

/** A few dots that fly out of the heart when a song is liked. */
fun Modifier.likeBurst(kick: Kick, color: Color): Modifier = drawBehind {
    if (!kick.running) return@drawBehind
    val k = kick.value.value
    val center = Offset(size.width / 2, size.height / 2)
    val r = 12.dp.toPx() + 18.dp.toPx() * k
    for (i in 0 until 8) {
        val a = (i / 8f) * 2 * PI.toFloat() + .2f
        drawCircle(
            color.copy(alpha = 1f - k),
            radius = 2.6.dp.toPx() * (1f - k * .6f),
            center = Offset(center.x + cos(a) * r, center.y + sin(a) * r),
        )
    }
}

/** 0 to 1 to 0: a bump for a kick, peaking at its middle. */
fun Kick.bump(): Float = if (running) sin(value.value * PI.toFloat()) else 0f
