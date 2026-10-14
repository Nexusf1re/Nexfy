package dev.nexfy.app.ui.design

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One shimmer sweep across the screen, in milliseconds. */
private const val SWEEP_MS = 1400L

/**
 * The shared shimmer: how many bones are on screen and where the sweep is.
 * Every bone reads the same phase, in window coordinates, so the light
 * passes over the whole skeleton as one band.
 */
private object Shimmer {
    var bones by mutableIntStateOf(0)
    val phase = mutableFloatStateOf(0f)
}

/**
 * Runs the shimmer clock while any bone is on screen. Place once, near the
 * root. With reduced motion the bones stay still, without the sweep.
 */
@Composable
fun ShimmerDriver() {
    val running by remember { derivedStateOf { Shimmer.bones > 0 && !Motion.reduced } }
    if (running) {
        LaunchedEffect(Unit) {
            while (true) {
                withInfiniteAnimationFrameMillis { Shimmer.phase.floatValue = (it % SWEEP_MS) / SWEEP_MS.toFloat() }
            }
        }
    }
}

/**
 * A placeholder shape with the shimmer passing over it. [light] is for
 * bones over artwork (white tint) instead of the theme's surface.
 */
@Composable
fun Bone(modifier: Modifier, shape: Shape = RoundedCornerShape(6.dp), light: Boolean = false) {
    DisposableEffect(Unit) {
        Shimmer.bones++
        onDispose { Shimmer.bones-- }
    }
    val ink = if (light) Color.White else MaterialTheme.colorScheme.onSurface
    val base = ink.copy(alpha = if (light) .12f else .07f)
    val glow = ink.copy(alpha = if (light) .22f else .13f)
    // Plain holder: placement changes must not recompose, only move the band.
    val origin = remember { floatArrayOf(0f) }
    Box(
        modifier
            .onPlaced { origin[0] = it.positionInWindow().x }
            .clip(shape)
            .drawWithCache {
                val band = 220.dp.toPx()
                val travel = 600.dp.toPx() + band
                onDrawBehind {
                    drawRect(base)
                    if (Motion.reduced) return@onDrawBehind
                    val x = -band + Shimmer.phase.floatValue * travel - origin[0]
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, glow, Color.Transparent),
                            start = Offset(x, 0f),
                            end = Offset(x + band, band * .3f),
                        ),
                    )
                }
            },
    )
}

/** Varied but stable widths, so a column of bones reads like text. */
private val widths = floatArrayOf(.72f, .48f, .86f, .58f, .66f, .4f, .8f, .54f)

fun boneWidth(seed: Int, offset: Int = 0): Float = widths[(seed * 3 + offset).mod(widths.size)]

/** A line of text: a bone centred in a box as tall as the real line. */
@Composable
fun TextBone(fraction: Float, lineHeight: Dp, boneHeight: Dp, light: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(lineHeight), contentAlignment = Alignment.CenterStart) {
        Bone(Modifier.fillMaxWidth(fraction).height(boneHeight), light = light)
    }
}

/** Same footprint as [TrackRow]. */
@Composable
fun TrackRowSkeleton(seed: Int, modifier: Modifier = Modifier, numbered: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (numbered) {
            Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) { Bone(Modifier.size(14.dp, 12.dp)) }
        } else {
            Bone(Modifier.size(56.dp), RoundedCornerShape(12.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            TextBone(boneWidth(seed), 24.dp, 14.dp)
            TextBone(boneWidth(seed, 1) * .8f, 24.dp, 11.dp)
        }
    }
}

/** Same footprint as a library row (64 dp cover). */
@Composable
fun ListRowSkeleton(seed: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Bone(Modifier.size(64.dp), RoundedCornerShape(12.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            TextBone(boneWidth(seed), 24.dp, 14.dp)
            TextBone(boneWidth(seed, 1) * .7f, 24.dp, 11.dp)
        }
    }
}

/** Same footprint as [CoverCard]. */
@Composable
fun CoverCardSkeleton(seed: Int, circle: Boolean = false) {
    Column(Modifier.width(156.dp).padding(bottom = if (circle) 0.dp else 10.dp)) {
        Bone(Modifier.size(156.dp), if (circle) CircleShape else RoundedCornerShape(14.dp))
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.padding(horizontal = if (circle) 0.dp else 10.dp),
            horizontalAlignment = if (circle) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            TextBone(boneWidth(seed), 24.dp, 14.dp)
            if (!circle) TextBone(boneWidth(seed, 1) * .7f, 24.dp, 11.dp)
        }
    }
}

/** A row of card skeletons running off the right edge, like [Carousel]. */
@Composable
fun CarouselSkeleton(circle: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().wrapContentWidth(Alignment.Start, unbounded = true),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(3) { CoverCardSkeleton(it, circle) }
    }
}

/** Fade used when a skeleton gives way to content in a lazy list. */
fun <T> skeletonFade(): FiniteAnimationSpec<T> = tween(Motion.scaled(Motion.Medium))

