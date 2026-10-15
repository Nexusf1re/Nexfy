package dev.nexfy.app.ui.design

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
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

    /**
     * Shorter, simpler motion: "Reduzir animações" or the system's
     * animations off. Every token below follows it, so screens never check
     * it themselves. Snapshot state: composables that build specs follow it.
     */
    var reduced by mutableStateOf(false)

    /** [ms] as this motion setting wants it: halved and capped when reduced. */
    fun scaled(ms: Int): Int = if (reduced) minOf(ms / 2, 150) else ms

    fun <T> enter(ms: Int = Medium) = tween<T>(scaled(ms), easing = EmphasizedDecelerate)
    fun <T> exit(ms: Int = Short) = tween<T>(scaled(ms), easing = EmphasizedAccelerate)
    fun <T> standard(ms: Int = Medium) = tween<T>(scaled(ms), easing = Standard)

    /** Settling after a gesture: critically damped, carries velocity, never overshoots. */
    fun <T> settle(stiffness: Float = 260f) =
        spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = if (reduced) maxOf(stiffness, Spring.StiffnessMedium) else stiffness)

    /** Small presses only: stiff with a hint of bounce (none when reduced). */
    fun <T> press() = spring<T>(dampingRatio = if (reduced) 1f else 0.8f, stiffness = Spring.StiffnessHigh)

    /** A short, bouncy "no" (a cover thrown with nothing after it). */
    fun <T> refuse() = if (reduced) settle<T>(700f) else spring(dampingRatio = .3f, stiffness = 700f)

    /** The player's cover turning over: a slow spring with a little sway. */
    fun <T> turn() = if (reduced) settle<T>() else spring(dampingRatio = .74f, stiffness = 150f)

    /**
     * A pushed screen comes forward out of depth: it grows from a rounded
     * card a little smaller than the screen on a spring that never
     * overshoots, fading in just behind the start of the motion.
     */
    val pageEnter: EnterTransition
        get() = if (reduced) {
            fadeIn(tween(scaled(420), easing = Standard))
        } else {
            fadeIn(tween(300, delayMillis = 40, easing = Standard)) +
                scaleIn(spring(dampingRatio = 1f, stiffness = 340f), initialScale = .94f) +
                slideInVertically(spring(dampingRatio = 1f, stiffness = 340f)) { it / 40 }
        }

    /** The screen underneath steps back past the viewer as the new one arrives. */
    val pageExit: ExitTransition
        get() = if (reduced) {
            fadeOut(tween(scaled(240), easing = EmphasizedAccelerate))
        } else {
            fadeOut(tween(220, easing = EmphasizedAccelerate)) +
                scaleOut(tween(380, easing = Standard), targetScale = 1.04f)
        }

    /**
     * Back: the page shrinks into a fading card above the one underneath,
     * which settles in from slightly smaller. Linear, so a back gesture
     * drags it evenly with the finger. Reduced, it only cross-fades.
     */
    val pageBack: ContentTransform
        get() = if (reduced) {
            ContentTransform(
                fadeIn(tween(scaled(360), easing = LinearEasing)),
                fadeOut(tween(scaled(360), easing = LinearEasing)),
                targetContentZIndex = -1f,
            )
        } else {
            ContentTransform(
                fadeIn(tween(360, easing = LinearEasing), initialAlpha = .35f) +
                    scaleIn(tween(360, easing = LinearEasing), initialScale = .92f),
                scaleOut(tween(360, easing = LinearEasing), targetScale = .8f) +
                    // The card stays solid while it shrinks and only fades at the end.
                    fadeOut(tween(360, easing = CubicBezierEasing(.8f, 0f, 1f, 1f))),
                targetContentZIndex = -1f,
            )
        }

    /** Content taking its skeleton's place: it slides in a little from the right as the skeleton fades. */
    val arrive: ContentTransform
        get() = if (reduced) {
            fadeIn(tween(scaled(240), easing = Standard)) togetherWith fadeOut(tween(scaled(160)))
        } else {
            (fadeIn(tween(320, 40, Standard)) + slideInHorizontally(spring(1f, 300f)) { it / 10 }) togetherWith
                fadeOut(tween(160, easing = EmphasizedAccelerate))
        }

    /** Tab switch: the new tab slides in from its side; the old one just fades. */
    fun tabSwitch(direction: Int): ContentTransform {
        if (reduced) return fadeIn(tween(scaled(240), easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(scaled(140)))
        val d = if (direction >= 0) 1 else -1
        return (fadeIn(tween(240, 60, FastOutSlowInEasing)) +
            slideInHorizontally(spring(.9f, 380f, IntOffset.VisibilityThreshold)) { (it * .06f).toInt() * d }) togetherWith
            fadeOut(tween(140, easing = FastOutSlowInEasing))
    }
}

/** The system's "remove animations" (animator duration scale 0). */
fun systemAnimationsOff(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

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
    if (LocalInspectionMode.current || Motion.reduced) return this
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

/**
 * Depth as a row scrolls: a card coming in at the right edge grows to full
 * size and brightens as it arrives, one leaving at the left recedes a
 * little. Its place is noted at layout and read while drawing only.
 */
@Composable
fun Modifier.carouselDepth(): Modifier {
    if (Motion.reduced) return this
    val enter = remember { mutableFloatStateOf(0f) }
    val leave = remember { mutableFloatStateOf(0f) }
    return onPlaced { coords ->
        val row = coords.parentLayoutCoordinates ?: return@onPlaced
        val x = coords.positionInParent().x
        val w = coords.size.width.toFloat().coerceAtLeast(1f)
        enter.floatValue = ((x + w - row.size.width) / w).coerceIn(0f, 1f)
        leave.floatValue = (-x / w).coerceIn(0f, 1f)
    }.graphicsLayer {
        val e = enter.floatValue
        val l = leave.floatValue
        val s = 1f - .1f * e - .06f * l
        scaleX = s
        scaleY = s
        alpha = 1f - .5f * e - .35f * l
        // Alpha on each drawing, not through an offscreen copy of the card every frame.
        compositingStrategy = CompositingStrategy.ModulateAlpha
        transformOrigin = TransformOrigin(if (e > 0f) 0f else 1f, .5f)
    }
}
