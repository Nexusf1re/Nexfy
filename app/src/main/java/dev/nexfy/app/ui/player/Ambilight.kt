package dev.nexfy.app.ui.player

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.motionAllowed
import dev.nexfy.app.ui.design.smallBitmap
import dev.nexfy.app.ui.theme.LocalLook
import dev.nexfy.app.ui.theme.isDark
import kotlinx.coroutines.CancellationException
import uniffi.nexfy_core.AudioPulse
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

const val AMBILIGHT_SPREAD = .5f

private const val QUARTER = .25f
private const val SILENCE = 1e-4f
private val Quiet = AudioPulse(0f, 0f, 0f)

private fun follow(value: Float, target: Float, dt: Float, tau: Float) = value + (target - value) * (1f - exp(-dt / tau))

internal class Groove {
    var kick = 0f
        private set
    var lift = 0f
        private set
    var sparkle = 0f
        private set
    var live = 0f
        private set
    private var bassAvg = 0f
    private var airAvg = 0f
    private var levelShort = 0f
    private var levelLong = 0f

    val resting get() = kick < .002f && lift < .002f && sparkle < .002f && live < .002f

    fun step(pulse: AudioPulse, dt: Float) {
        val heard = pulse.level > SILENCE
        live = follow(live, if (heard) 1f else 0f, dt, if (heard) .25f else .8f)
        if (!heard) {
            kick = follow(kick, 0f, dt, .3f)
            sparkle = follow(sparkle, 0f, dt, .2f)
            lift = follow(lift, 0f, dt, 1.2f)
            return
        }
        bassAvg = if (bassAvg == 0f) pulse.bass else follow(bassAvg, pulse.bass, dt, 1.2f)
        airAvg = if (airAvg == 0f) pulse.air else follow(airAvg, pulse.air, dt, 1.2f)
        levelShort = if (levelShort == 0f) pulse.level else follow(levelShort, pulse.level, dt, .4f)
        levelLong = if (levelLong == 0f) pulse.level else follow(levelLong, pulse.level, dt, 10f)

        val hit = ((pulse.bass / bassAvg.coerceAtLeast(SILENCE) - 1.15f) / 1.2f).coerceIn(0f, 1f)
        kick = follow(kick, hit, dt, if (hit > kick) .02f else .28f)
        val tick = ((pulse.air / airAvg.coerceAtLeast(SILENCE) - 1.3f) / 1.5f).coerceIn(0f, 1f)
        sparkle = follow(sparkle, tick, dt, if (tick > sparkle) .015f else .12f)
        val swell = ((levelShort / levelLong.coerceAtLeast(SILENCE) - .8f) / .6f).coerceIn(0f, 1f)
        lift = follow(lift, swell, dt, .9f)
    }
}

private class Projection(val image: ImageBitmap) {
    private val shader = BitmapShader(image.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val matrix = Matrix()

    val exposure: Float = run {
        val bitmap = image.asAndroidBitmap()
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val rim = maxOf(1, minOf(w, h) / 8)
        val light = pixels.indices
            .filter { i -> i % w < rim || i % w >= w - rim || i / w < rim || i / w >= h - rim }
            .map { Color(pixels[it]).luminance() }
            .average().toFloat()
        (.28f / light.coerceAtLeast(.05f)).coerceIn(.3f, 1.15f)
    }

    fun brush(at: Rect): ShaderBrush {
        matrix.setScale(at.width / image.width, at.height / image.height)
        matrix.postTranslate(at.left, at.top)
        shader.setLocalMatrix(matrix)
        return ShaderBrush(shader)
    }
}

private fun tone(saturation: Float, gain: Float) = ColorFilter.colorMatrix(
    ColorMatrix().apply {
        setToSaturation(saturation)
        timesAssign(ColorMatrix().apply { setToScale(gain, gain, gain, 1f) })
    },
)

private fun falloff(center: Offset, radius: Float, from: Float, to: Float, curve: Float): Brush {
    val steps = 10
    val stops = Array(steps + 2) { i ->
        if (i == 0) {
            0f to Color.Black
        } else {
            val k = (i - 1) / steps.toFloat()
            (from + (to - from) * k) to Color.Black.copy(alpha = (1f - k).pow(curve))
        }
    }
    return Brush.radialGradient(*stops, center = center, radius = radius)
}

private fun grain(): ImageBitmap {
    val size = 64
    val random = Random(7)
    val pixels = IntArray(size * size) {
        val v = random.nextInt(256)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    return android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun DrawScope.light(shown: Projection?, leaving: Projection?, swap: Float, at: Rect, saturation: Float, gain: Float) {
    leaving?.let { drawRect(it.brush(at), alpha = 1f - swap, colorFilter = tone(saturation, gain * it.exposure)) }
    shown?.let { drawRect(it.brush(at), alpha = if (leaving == null) 1f else swap, colorFilter = tone(saturation, gain * it.exposure)) }
}

@Composable
fun Ambilight(
    coverUrl: String?,
    playing: Boolean,
    pulse: () -> AudioPulse,
    cover: Rect,
    modifier: Modifier = Modifier,
    recede: () -> Float = { 1f },
) {
    val context = LocalContext.current
    val moving = LocalLook.current.animatedBackdrop && !Motion.reduced && remember { motionAllowed(context) }
    val dark = MaterialTheme.colorScheme.isDark

    var shown by remember { mutableStateOf<Projection?>(null) }
    var leaving by remember { mutableStateOf<Projection?>(null) }
    val swap = remember { Animatable(1f) }
    LaunchedEffect(coverUrl) {
        val next = try {
            coverUrl?.takeIf { it.isNotBlank() }?.let { smallBitmap(context, it, 32) }?.let { Projection(it.asImageBitmap()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (next == null && shown == null) return@LaunchedEffect
        leaving = shown
        shown = next
        swap.snapTo(0f)
        swap.animateTo(1f, tween(Motion.scaled(900)))
        leaving = null
    }

    val groove = remember { Groove() }
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(moving, playing) {
        if (!moving) return@LaunchedEffect
        var last = 0L
        while (playing || !groove.resting) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(.1f)
                last = now
                groove.step(if (playing) pulse() else Quiet, dt)
                frame.longValue = now
            }
        }
    }
    val presence by animateFloatAsState(if (playing) 1f else .45f, Motion.standard(700), label = "ambilight")
    val noise = remember { grain() }

    fun breath(): Float {
        val t = frame.longValue / 1e9f
        return (1f - cos(2f * PI.toFloat() * t / 7f)) / 2f * (1f - groove.live)
    }

    fun coverAt(grow: Float): Rect {
        val side = cover.width * QUARTER * grow * recede()
        val center = cover.center * QUARTER
        return Rect(center.x - side / 2, center.y - side / 2, center.x + side / 2, center.y + side / 2)
    }

    Box(
        modifier.fillMaxSize().graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
            alpha = presence * if (dark) 1f else .7f
        },
    ) {
        Spacer(
            Modifier.fillMaxSize(QUARTER)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = 1f / QUARTER
                    scaleY = 1f / QUARTER
                    val r = cover.width * QUARTER * .2f
                    renderEffect = BlurEffect(r, r, TileMode.Decal)
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithCache {
                    val reach = cover.width * QUARTER * (.5f + AMBILIGHT_SPREAD)
                    val mask = falloff(cover.center * QUARTER, reach, .5f / (.5f + AMBILIGHT_SPREAD), 1f, 2.4f)
                    onDrawBehind {
                        frame.longValue
                        val glow = 1f + .05f * groove.lift + .09f * groove.kick + .035f * breath()
                        light(shown, leaving, swap.value, coverAt(glow), 1.3f + .25f * groove.lift, .8f + .3f * groove.kick + .08f * breath())
                        drawRect(mask, blendMode = BlendMode.DstIn)
                    }
                },
        )
        Spacer(
            Modifier.fillMaxSize(QUARTER)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = 1f / QUARTER
                    scaleY = 1f / QUARTER
                    val r = cover.width * QUARTER * .07f
                    renderEffect = BlurEffect(r, r, TileMode.Decal)
                    compositingStrategy = CompositingStrategy.Offscreen
                    frame.longValue
                    alpha = (.22f + .55f * groove.kick + .12f * groove.sparkle).coerceIn(0f, 1f)
                }
                .drawWithCache {
                    val reach = cover.width * QUARTER * .78f
                    val mask = falloff(cover.center * QUARTER, reach, .5f / .78f, 1f, 2.4f)
                    onDrawBehind {
                        frame.longValue
                        val glow = 1f + .025f * groove.kick
                        light(shown, leaving, swap.value, coverAt(glow), 1.5f, 1.2f + .45f * groove.kick + .25f * groove.sparkle)
                        drawRect(mask, blendMode = BlendMode.DstIn)
                    }
                },
        )
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val brush = ShaderBrush(ImageShader(noise, TileMode.Repeated, TileMode.Repeated))
                onDrawBehind { drawRect(brush, alpha = .045f, blendMode = BlendMode.SrcAtop) }
            },
        )
    }
}
