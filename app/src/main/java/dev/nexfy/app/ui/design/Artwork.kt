package dev.nexfy.app.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.CompositingStrategy
import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.nexfy.app.ui.theme.LocalLook
import dev.nexfy.app.ui.theme.isDark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/** Small software bitmap of [url] for colour extraction, or null. */
private suspend fun smallBitmap(context: Context, url: String, size: Int): Bitmap? {
    val request = ImageRequest.Builder(context).data(url).size(size).allowHardware(false).build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    return result.image.toBitmap()
}

private fun <K, V> lruCache(max: Int): MutableMap<K, V> = java.util.Collections.synchronizedMap(
    object : LinkedHashMap<K, V>(max, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    },
)

/** Neutral palette while a cover loads or without one. */
private val Neutral = listOf(Color(0xFF2A2A2E), Color(0xFF151517), Color(0xFF3A3A40), Color(0xFF0B0B0C))
private val PaletteCache = lruCache<String, List<Color>>(64)
private val DominantCache = lruCache<String, Color>(200)

/** Unspecified marks a cover known to have no usable colour. */
private val AccentCache = lruCache<String, Color>(64)

/**
 * The cover's most vivid colour that is also really there (a speck of red
 * on a grey cover does not count), as is: the theme makes it legible. Null
 * for grey covers or when the cover does not load. Cached per URL.
 */
suspend fun coverAccent(context: Context, url: String): Color? {
    AccentCache[url]?.let { return it.takeIf { c -> c.isSpecified } }
    return try {
        // Same size as the backdrop palette, so the decoded cover is shared.
        val bitmap = smallBitmap(context, url, 96) ?: return null
        val pick = withContext(Dispatchers.Default) {
            val swatches = Palette.from(bitmap).maximumColorCount(16).generate().swatches
            val total = swatches.sumOf { it.population }.coerceAtLeast(1).toFloat()
            swatches
                .filter { it.hsl[1] >= .3f && it.hsl[2] in .12f..0.9f && it.population / total >= .01f }
                .maxByOrNull { it.hsl[1] * kotlin.math.sqrt(it.population / total) }
                ?.let { Color(it.rgb) }
        }
        AccentCache[url] = pick ?: Color.Unspecified
        pick
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** Up to four colours from the cover, darkened enough for white text. Cached across screens. */
@Composable
fun rememberCoverPalette(url: String?): List<Color> {
    val context = LocalContext.current
    var colors by remember(url) { mutableStateOf(url?.let { PaletteCache[it] } ?: Neutral) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank() || PaletteCache.containsKey(url)) return@LaunchedEffect
        try {
            val bitmap = smallBitmap(context, url, 96) ?: return@LaunchedEffect
            val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).maximumColorCount(16).generate() }
            val picks = listOfNotNull(
                palette.dominantSwatch, palette.vibrantSwatch, palette.darkVibrantSwatch,
                palette.mutedSwatch, palette.darkMutedSwatch, palette.lightMutedSwatch,
            ).map { Color(it.rgb) }.distinct()
            if (picks.isNotEmpty()) {
                colors = List(4) { i -> lerp(picks[i % picks.size], Color.Black, .35f + i * .08f) }
                PaletteCache[url] = colors
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    return colors
}

/**
 * Soft per-cover tints, never system dynamic colour: the cover's hue at low
 * chroma, lightness clamped per theme.
 */
object Tint {
    private fun hsl(c: Color) = FloatArray(3).also { ColorUtils.colorToHSL(c.toArgb(), it) }

    fun soft(base: Color, dark: Boolean): Color {
        val (h, s) = hsl(base).let { it[0] to it[1] }
        return Color(ColorUtils.HSLToColor(floatArrayOf(h, (s * .45f).coerceIn(.06f, .26f), if (dark) .22f else .88f)))
    }

    /** A stable hue for items without art, so rows still vary. */
    fun seeded(key: String): Color =
        Color(ColorUtils.HSLToColor(floatArrayOf((key.hashCode().toLong().and(0xffff) % 360).toFloat(), .35f, .45f)))

    /** About two of three cards tinted, the rest neutral: variety without a rainbow. */
    fun tinted(key: String) = Math.floorMod(key.hashCode(), 3) != 0
}

@Composable
fun rememberDominant(url: String?, key: String): Color {
    val context = LocalContext.current
    var color by remember(url, key) { mutableStateOf(url?.let { DominantCache[it] } ?: Tint.seeded(key)) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank() || DominantCache.containsKey(url)) return@LaunchedEffect
        try {
            val bitmap = smallBitmap(context, url, 64) ?: return@LaunchedEffect
            val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).maximumColorCount(12).generate() }
            (palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch)?.let {
                color = Color(it.rgb)
                DominantCache[url] = color
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    return color
}

/** Card background: a soft tint of the cover for most cards, neutral for the rest. */
@Composable
fun tileColor(url: String?, key: String): Color {
    val dark = MaterialTheme.colorScheme.isDark
    val dominant = rememberDominant(url, key)
    return if (Tint.tinted(key)) Tint.soft(dominant, dark) else MaterialTheme.colorScheme.surfaceVariant
}

/** Readable text colour over [background]. */
fun contentFor(background: Color): Color =
    if (background.luminance() > .45f) Color(0xFF151515) else Color(0xFFF5F5F5)

/**
 * Living artwork: four soft colour blobs from the cover drift on slow,
 * out-of-phase orbits and blend into a mesh-like gradient. Redraws each
 * frame without recomposing (states are read in the draw lambda). With
 * "Fundo animado" off it is drawn once, at a fixed phase. [pale] washes it
 * toward white instead of darkening it, for dark text on a light theme.
 */
@Composable
fun LivingArtwork(art: String?, modifier: Modifier = Modifier, dim: Float = .25f, pale: Boolean = false) {
    val colors = rememberCoverPalette(art)
    val animated = colors.map { animateColorAsState(it, Motion.standard(1200), label = "mesh") }
    val context = LocalContext.current
    val allowed = remember { motionAllowed(context) }
    val moving = allowed && LocalLook.current.animatedBackdrop
    // A 36 s orbit, on the ambient clock: 30 frames a second, not 120.
    val clock = if (moving) ambientClock() else null
    // Drawn into a quarter-size layer and scaled up: the soft gradients look
    // the same and each frame fills a sixteenth of the pixels.
    Box(modifier.fillMaxSize()) {
    Canvas(
        Modifier.fillMaxSize(.25f).graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = 4f
            scaleY = 4f
        },
    ) {
        val phase = clock?.let { (it.value % 36_000) / 36_000f } ?: .3f
        drawRect(animated[3].value)
        val w = size.width
        val h = size.height
        val r = maxOf(w, h) * .75f
        val orbits = listOf(Triple(.25f, .2f, 0f), Triple(.8f, .3f, 1.7f), Triple(.3f, .8f, 3.4f), Triple(.75f, .85f, 5.1f))
        orbits.forEachIndexed { i, (x, y, offset) ->
            val a = (phase * 2 * Math.PI + offset).toFloat()
            val center = Offset(w * (x + .18f * cos(a * (1 + i % 2))), h * (y + .12f * sin(a)))
            drawCircle(
                Brush.radialGradient(listOf(animated[i].value.copy(alpha = .9f), animated[i].value.copy(alpha = 0f)), center, r),
                r,
                center,
            )
        }
        drawRect(if (pale) Color.White.copy(alpha = .62f) else Color.Black.copy(alpha = dim.coerceIn(0f, 1f)))
    }
    }
}

/** Off with battery saver or with system animations removed. */
fun motionAllowed(context: Context): Boolean {
    val saver = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    return !systemAnimationsOff(context) && !saver
}

/**
 * Slow Ken Burns drift of a still cover (24 s loop). The cover is always
 * zoomed past the pan, so the drift never uncovers the edges. Ambient
 * motion: it stops with reduced motion.
 */
@Composable
fun Modifier.kenBurns(enabled: Boolean): Modifier {
    val context = LocalContext.current
    val on = enabled && !Motion.reduced && remember { motionAllowed(context) }
    if (!on) return this
    // On the ambient clock: a drift this slow looks the same at 30 fps.
    val clock = ambientClock()
    return graphicsLayer {
        val t = (clock.value % 24_000) / 24_000f
        val e = (1 - cos(t * 2 * Math.PI).toFloat()) / 2
        // Pan reaches 2.5 % per side; 1.06 leaves 3 % of margin on each side.
        val s = 1.06f + .06f * e
        scaleX = s
        scaleY = s
        translationX = .025f * sin(t * 2 * Math.PI).toFloat() * size.width
        translationY = -.02f * e * size.height
    }
}
