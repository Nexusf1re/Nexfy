package dev.nexfy.app.ui.design

import androidx.compose.ui.graphics.Color
import dev.nexfy.app.ui.theme.toHsl
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
 * Up to [maxColors] swatches for [pixels], each 0xRRGGBB, most populous
 * first. Near-white and near-black are dropped first: they are the paper and
 * the shadow of almost every cover and would win on population alone.
 */
fun quantize(pixels: IntArray, maxColors: Int): List<Swatch> {
    val counts = IntArray(SIDE * SIDE * SIDE)
    for (pixel in pixels) {
        val r = ((pixel shr 16) and 0xFF) shr DROP
        val g = ((pixel shr 8) and 0xFF) shr DROP
        val b = (pixel and 0xFF) shr DROP
        // Keep the extremes out of the cut, the way Palette's default filter does.
        if (r >= MASK - 1 && g >= MASK - 1 && b >= MASK - 1) continue
        if (r <= 1 && g <= 1 && b <= 1) continue
        counts[key(r, g, b)]++
    }

    val keys = (counts.indices).filter { counts[it] > 0 }.toIntArray()
    if (keys.isEmpty()) return emptyList()

    val boxes = mutableListOf(Box(keys, 0, keys.lastIndex, counts))
    while (boxes.size < maxColors) {
        val next = boxes.filter { it.canSplit }.maxByOrNull { it.volume.toLong() * it.population } ?: break
        boxes.add(next.split())
    }
    return boxes.map { it.average() }.filter { it.population > 0 }.sortedByDescending { it.population }
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
 * Palette's named picks, scored the same way: each target wants a saturation
 * and a lightness, and the most populous swatch closest to both wins. Used
 * for the backdrop, which wants several colours that differ.
 */
fun List<Swatch>.pick(saturation: Float, lightness: Float): Color? {
    val top = maxOfOrNull { it.population }?.toFloat()?.coerceAtLeast(1f) ?: return null
    return maxByOrNull {
        val s = 1f - kotlin.math.abs(it.hsl[1] - saturation)
        val l = 1f - kotlin.math.abs(it.hsl[2] - lightness)
        // Weighted as Palette does: colour first, then how much of it there is.
        s * 3f + l * 6f + (it.population / top) * 1f
    }?.color
}

/** The six Palette targets the backdrop reads, in the order it read them. */
fun List<Swatch>.backdropPicks(): List<Color> = listOfNotNull(
    firstOrNull()?.color,          // dominant
    pick(1f, .5f),                 // vibrant
    pick(1f, .26f),                // dark vibrant
    pick(.3f, .5f),                // muted
    pick(.3f, .26f),               // dark muted
    pick(.3f, .74f),               // light muted
).distinct()
