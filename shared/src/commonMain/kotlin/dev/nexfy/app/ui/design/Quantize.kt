package dev.nexfy.app.ui.design

import androidx.compose.ui.graphics.Color
import dev.nexfy.app.ui.theme.toHsl
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What androidx.palette gave the Android app, in plain Kotlin so the desktop
 * reads the same colours out of the same cover.
 *
 * Median cut, the algorithm Palette itself uses: every pixel falls into one
 * of 32768 buckets (five bits a channel), the whole cloud starts as one box,
 * and the box with the most colour spread is split at the median of its
 * widest channel until there are enough. Each box then averages to one
 * swatch, weighted by how many pixels landed in it.
 *
 * Which colours are allowed, and which swatch each named colour comes out
 * as, follow Palette's own rules: see [allowed] for its default filter and
 * [PaletteTarget] for its six targets and their scoring.
 */
class Swatch(val color: Color, val population: Int) {
    /** Hue in degrees, saturation and lightness in 0..1, as androidx wrote it. */
    val hsl: FloatArray = color.toHsl()
}

private const val BITS = 5
private const val SIDE = 1 shl BITS
private const val MASK = SIDE - 1
private const val DROP = 8 - BITS

private fun key(r: Int, g: Int, b: Int) = (r shl (BITS * 2)) or (g shl BITS) or b

/**
 * Palette's default filter, which decides what may enter the cut at all:
 * near-white, near-black, and the band of reds that camera sensors and JPEG
 * leave along the "red I" line, which read as skin or paper rather than as a
 * colour of the cover.
 *
 * Taken from androidx.palette 1.0.0's `Palette.DEFAULT_FILTER`
 * (`isWhite`: L >= .95; `isBlack`: L <= .05; `isNearRedILine`: hue 10..37
 * with saturation <= .82). The channels are 0..255, and hue, saturation and
 * lightness are worked out in locals the way `Color.toHsl` does, so a bucket
 * and the swatch it ends up in are judged by the same numbers — and so that
 * sweeping the histogram allocates nothing.
 */
private fun allowed(r8: Int, g8: Int, b8: Int): Boolean {
    val r = r8 / 255f
    val g = g8 / 255f
    val b = b8 / 255f
    val high = max(r, max(g, b))
    val low = min(r, min(g, b))
    val lightness = (high + low) / 2f
    if (lightness >= .95f || lightness <= .05f) return false
    val spread = high - low
    // Grey has no hue to be near the red line.
    if (spread == 0f) return true
    val saturation =
        if (lightness > .5f) spread / (2f - high - low) else spread / (high + low)
    val hue = when (high) {
        r -> (g - b) / spread + if (g < b) 6f else 0f
        g -> (b - r) / spread + 2f
        else -> (r - g) / spread + 4f
    } * 60f
    return !(hue >= 10f && hue <= 37f && saturation <= .82f)
}

/** The same judgement on a swatch Palette has already built, by its HSL. */
private fun allowed(hsl: FloatArray): Boolean {
    if (hsl[2] >= .95f || hsl[2] <= .05f) return false
    return !(hsl[0] >= 10f && hsl[0] <= 37f && hsl[1] <= .82f)
}

/**
 * One of Palette's named colours: the range a swatch must be inside to be
 * considered at all, and the saturation and lightness it is aiming for.
 *
 * The weights Palette scores with are the same for all six built-in targets
 * (.24 saturation, .52 lightness, .24 population, which already sum to one,
 * so its `normalizeWeights` leaves them alone), and they live in [score].
 */
class PaletteTarget internal constructor(
    internal val minSaturation: Float,
    internal val targetSaturation: Float,
    internal val maxSaturation: Float,
    internal val minLightness: Float,
    internal val targetLightness: Float,
    internal val maxLightness: Float,
) {
    /** Whether [swatch] is inside this target's range, and so worth scoring. */
    internal fun covers(swatch: Swatch): Boolean {
        val hsl = swatch.hsl
        return hsl[1] >= minSaturation && hsl[1] <= maxSaturation &&
            hsl[2] >= minLightness && hsl[2] <= maxLightness
    }

    /**
     * How well [swatch] answers this target: how near its saturation and
     * lightness are to what is wanted, and how much of the cover it covers
     * against the most populous swatch there is.
     */
    internal fun score(swatch: Swatch, topPopulation: Float): Float {
        val hsl = swatch.hsl
        val saturation = WEIGHT_SATURATION * (1f - abs(hsl[1] - targetSaturation))
        val lightness = WEIGHT_LIGHTNESS * (1f - abs(hsl[2] - targetLightness))
        val population = WEIGHT_POPULATION * (swatch.population / topPopulation)
        return saturation + lightness + population
    }

    internal companion object {
        private const val WEIGHT_SATURATION = .24f
        private const val WEIGHT_LIGHTNESS = .52f
        private const val WEIGHT_POPULATION = .24f

        val LightVibrant = PaletteTarget(.35f, 1f, 1f, .55f, .74f, 1f)
        val Vibrant = PaletteTarget(.35f, 1f, 1f, .3f, .5f, .7f)
        val DarkVibrant = PaletteTarget(.35f, 1f, 1f, 0f, .26f, .45f)
        val LightMuted = PaletteTarget(0f, .3f, .4f, .55f, .74f, 1f)
        val Muted = PaletteTarget(0f, .3f, .4f, .3f, .5f, .7f)
        val DarkMuted = PaletteTarget(0f, .3f, .4f, 0f, .26f, .45f)

        /**
         * The order Palette's builder adds them in, which decides who claims
         * a swatch first: each target that takes one marks it used, so two
         * named colours are never the same colour.
         */
        val All = listOf(LightVibrant, Vibrant, DarkVibrant, LightMuted, Muted, DarkMuted)
    }
}

/**
 * The swatches of one cover, most populous first, with Palette's six named
 * colours already worked out over them. Still a list, so the dominant colour
 * is simply the first and [accent] reads them all.
 */
class Swatches internal constructor(
    private val swatches: List<Swatch>,
) : List<Swatch> by swatches {
    /**
     * Each target's swatch, or none where the cover holds nothing inside
     * that target's range. Scored in [PaletteTarget.All] order, each winner
     * taken out of the running for the targets after it, as Palette does.
     */
    private val named: Map<PaletteTarget, Color> = buildMap {
        val top = swatches.maxOfOrNull { it.population }?.toFloat()?.coerceAtLeast(1f)
            ?: return@buildMap
        val used = HashSet<Color>()
        for (target in PaletteTarget.All) {
            var best: Swatch? = null
            var bestScore = 0f
            for (swatch in swatches) {
                if (!target.covers(swatch) || swatch.color in used) continue
                val score = target.score(swatch, top)
                if (best == null || score > bestScore) {
                    best = swatch
                    bestScore = score
                }
            }
            val won = best ?: continue
            // Every built-in target is exclusive, so the colour is spent.
            used += won.color
            put(target, won.color)
        }
    }

    /** The colour for [target], or null when the cover has nothing in its range. */
    fun of(target: PaletteTarget): Color? = named[target]

    /** The most populous swatch, which is Palette's dominant colour. */
    val dominant: Color? get() = swatches.firstOrNull()?.color
}

/**
 * Up to [maxColors] swatches for [pixels], each 0xRRGGBB, most populous
 * first.
 *
 * One pass counts the pixels into the histogram, a second sweeps the 32768
 * buckets and empties the ones Palette's filter rejects — the same place
 * Palette applies it, so rejected colours weigh nothing in the cut either.
 * Neither pass allocates.
 */
fun quantize(pixels: IntArray, maxColors: Int): Swatches {
    val counts = IntArray(SIDE * SIDE * SIDE)
    for (pixel in pixels) {
        val r = ((pixel shr 16) and 0xFF) shr DROP
        val g = ((pixel shr 8) and 0xFF) shr DROP
        val b = (pixel and 0xFF) shr DROP
        counts[key(r, g, b)]++
    }
    for (k in counts.indices) {
        if (counts[k] == 0) continue
        // Judged on the colour the bucket stands for, as Palette judges it:
        // each five-bit channel widened to eight, low bits left at zero.
        val r = ((k shr (BITS * 2)) and MASK) shl DROP
        val g = ((k shr BITS) and MASK) shl DROP
        val b = (k and MASK) shl DROP
        if (!allowed(r, g, b)) counts[k] = 0
    }

    val keys = (counts.indices).filter { counts[it] > 0 }.toIntArray()
    if (keys.isEmpty()) return Swatches(emptyList())

    val boxes = mutableListOf(Box(keys, 0, keys.lastIndex, counts))
    while (boxes.size < maxColors) {
        val next = boxes.filter { it.canSplit }.maxByOrNull { it.volume.toLong() * it.population } ?: break
        boxes.add(next.split())
    }
    return Swatches(
        boxes.map { it.average() }
            // A box can average out to a colour none of its own pixels were,
            // and Palette drops those too rather than keep them.
            .filter { it.population > 0 && allowed(it.hsl) }
            .sortedByDescending { it.population },
    )
}

/** A box over the colour cube, holding the histogram keys inside it. */
private class Box(val keys: IntArray, var from: Int, var to: Int, val counts: IntArray) {
    var rLow = 0; var rHigh = 0
    var gLow = 0; var gHigh = 0
    var bLow = 0; var bHigh = 0
    var population = 0

    init { fit() }

    /** Shrink to what is actually inside, and count it. */
    fun fit() {
        rLow = MASK; gLow = MASK; bLow = MASK
        rHigh = 0; gHigh = 0; bHigh = 0
        population = 0
        for (i in from..to) {
            val k = keys[i]
            val r = (k shr (BITS * 2)) and MASK
            val g = (k shr BITS) and MASK
            val b = k and MASK
            rLow = min(rLow, r); rHigh = max(rHigh, r)
            gLow = min(gLow, g); gHigh = max(gHigh, g)
            bLow = min(bLow, b); bHigh = max(bHigh, b)
            population += counts[k]
        }
    }

    val canSplit get() = to > from
    private val rSpread get() = rHigh - rLow
    private val gSpread get() = gHigh - gLow
    private val bSpread get() = bHigh - bLow
    val volume get() = (rSpread + 1) * (gSpread + 1) * (bSpread + 1)

    /** Sort this box's keys along its widest channel and cut at the median. */
    fun split(): Box {
        val widest = max(rSpread, max(gSpread, bSpread))
        val shift = when (widest) {
            rSpread -> BITS * 2
            gSpread -> BITS
            else -> 0
        }
        val slice = keys.copyOfRange(from, to + 1)
        slice.sortedBy { (it shr shift) and MASK }.forEachIndexed { i, k -> keys[from + i] = k }

        // Cut where half the pixels lie, so both halves carry real weight.
        val half = population / 2
        var seen = 0
        var cut = from
        for (i in from..to) {
            seen += counts[keys[i]]
            if (seen >= half) { cut = min(i, to - 1); break }
        }
        val upper = Box(keys, cut + 1, to, counts)
        to = cut
        fit()
        return upper
    }

    fun average(): Swatch {
        var r = 0L; var g = 0L; var b = 0L; var total = 0L
        for (i in from..to) {
            val k = keys[i]
            val n = counts[k].toLong()
            total += n
            r += n * (((k shr (BITS * 2)) and MASK) shl DROP)
            g += n * (((k shr BITS) and MASK) shl DROP)
            b += n * ((k and MASK) shl DROP)
        }
        if (total == 0L) return Swatch(Color.Black, 0)
        return Swatch(
            Color((r / total).toInt() / 255f, (g / total).toInt() / 255f, (b / total).toInt() / 255f),
            total.toInt(),
        )
    }
}

/**
 * The cover's most vivid colour that is also really there: a speck of red on
 * a grey cover does not count, so saturation is weighed against the square
 * root of how much of the cover it covers. Null for a grey cover.
 */
fun List<Swatch>.accent(): Color? {
    val total = sumOf { it.population }.coerceAtLeast(1).toFloat()
    return filter { it.hsl[1] >= .3f && it.hsl[2] in .12f..0.9f && it.population / total >= .01f }
        .maxByOrNull { it.hsl[1] * sqrt(it.population / total) }
        ?.color
}

/**
 * Palette's named pick that aims for [saturation] and [lightness] — one of
 * its six targets, named here by what it aims for so the callers read as
 * they always have. Null when the cover holds no colour inside that
 * target's range, which is what lets a caller fall back to the dominant
 * colour or to a calmer target.
 */
fun Swatches.pick(saturation: Float, lightness: Float): Color? {
    val target = PaletteTarget.All.firstOrNull {
        it.targetSaturation == saturation && it.targetLightness == lightness
    } ?: return null
    return of(target)
}

/** The six Palette targets the backdrop reads, in the order it read them. */
fun Swatches.backdropPicks(): List<Color> = listOfNotNull(
    dominant,
    of(PaletteTarget.Vibrant),
    of(PaletteTarget.DarkVibrant),
    of(PaletteTarget.Muted),
    of(PaletteTarget.DarkMuted),
    of(PaletteTarget.LightMuted),
).distinct()
