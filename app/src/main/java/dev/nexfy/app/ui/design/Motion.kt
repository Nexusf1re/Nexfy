package dev.nexfy.app.ui.design

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.AnimationVector1D
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Motion tokens for the whole app: emphasized curves (enter decelerating,
 * leave accelerating), standard durations, and springs that never overshoot
 * for layout and bounce only a little for small presses.
 */
object Motion {
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val Short = 200
    const val Medium = 400
    const val Long = 540
    const val ExtraLong = 850
    const val PressDown = 130
    const val Stagger = 80

    /** px/s past which a drag counts as a fling. */
    const val FlingVelocity = 1200f

    fun <T> enter(ms: Int = Medium) = tween<T>(ms, easing = EmphasizedDecelerate)
    fun <T> exit(ms: Int = Short) = tween<T>(ms, easing = EmphasizedAccelerate)
    fun <T> standard(ms: Int = Medium) = tween<T>(ms, easing = Standard)

    /** Settling after a gesture: critically damped, carries velocity, never overshoots. */
    fun <T> settle(stiffness: Float = 260f) = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness)

    /** Small presses only: stiff with a hint of bounce. */
    fun <T> press() = spring<T>(dampingRatio = 0.8f, stiffness = Spring.StiffnessHigh)

    /** A pushed screen: fades in while rising a little on a soft spring. */
    val pageEnter: EnterTransition =
        fadeIn(tween(420, delayMillis = Stagger, easing = Standard)) +
            slideInVertically(spring(dampingRatio = 0.8f, stiffness = 230f)) { it / 28 }
    val pageExit: ExitTransition = fadeOut(tween(240, easing = EmphasizedAccelerate))

    /** Tab switch: the new tab slides in from its side; the old one just fades. */
    fun tabSwitch(direction: Int): ContentTransform {
        val d = if (direction >= 0) 1 else -1
        return (fadeIn(tween(240, 60, FastOutSlowInEasing)) +
            slideInHorizontally(spring(.9f, 380f, IntOffset.VisibilityThreshold)) { (it * .06f).toInt() * d }) togetherWith
            fadeOut(tween(140, easing = FastOutSlowInEasing))
    }
}

/**
 * Apply a drag step immediately and in order. A dispatched `snapTo` can
 * run after the finger lifts and cancel the settle animation, leaving the
 * element stuck where the drag ended.
 */
fun CoroutineScope.snapNow(animatable: Animatable<Float, AnimationVector1D>, value: Float) {
    launch(start = CoroutineStart.UNDISPATCHED) { animatable.snapTo(value) }
}

/** A press with weight: sinks to 95 % and darkens a touch, springs back. Read in the draw phase only. */
@Composable
fun Modifier.weightedPress(source: InteractionSource, corner: Dp = 0.dp): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(source) {
        source.interactions.collect { interaction ->
            val target = if (interaction is PressInteraction.Press) 1f else 0f
            launch { p.animateTo(target, if (target > 0f) Motion.enter(Motion.PressDown) else Motion.press()) }
        }
    }
    return graphicsLayer {
        val v = p.value.coerceIn(0f, 1f)
        scaleX = 1f - .05f * v
        scaleY = scaleX
    }.drawWithContent {
        drawContent()
        val v = p.value.coerceIn(0f, 1f)
        if (v > 0f) drawRoundRect(Color.Black.copy(alpha = .12f * v), cornerRadius = CornerRadius(corner.toPx()))
    }
}

/** Sinks while pressed and springs back. */
@Composable
fun Modifier.springPress(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) 0.94f else 1f, Motion.press(), label = "press")
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * When a list's content first appeared. Items composed later than
 * [Cascade.WINDOW_MS] after that (scrolled into view) skip the entrance, so
 * a fast fling never shows rows that are still invisible.
 */
class Cascade {
    private val start = android.os.SystemClock.uptimeMillis()
    val active get() = android.os.SystemClock.uptimeMillis() - start < WINDOW_MS

    companion object {
        const val WINDOW_MS = 600L
    }
}

/** A cascade clock that restarts whenever [key] (the list's content) changes. */
@Composable
fun rememberCascade(key: Any?): Cascade = remember(key) { Cascade() }

/**
 * List entrance on first appearance: each item arrives a little after the
 * one above and settles. Items scrolled into view later appear at once.
 */
@Composable
fun Modifier.cascade(index: Int, clock: Cascade): Modifier {
    if (LocalInspectionMode.current) return this
    val animate = remember { clock.active }
    if (!animate) return this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(12) * 45L)
        a.animateTo(1f, Motion.enter(Motion.Long))
    }
    return graphicsLayer {
        val v = a.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 60f
        scaleX = 0.94f + 0.06f * v
        scaleY = scaleX
    }
}
