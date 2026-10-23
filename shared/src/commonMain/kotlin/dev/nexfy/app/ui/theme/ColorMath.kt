package dev.nexfy.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * What androidx.core.graphics.ColorUtils gave the Android app, in plain
 * Kotlin so the desktop gets the same accent from the same cover. HSL here
 * is hue in degrees and saturation and lightness in 0..1, as androidx wrote
 * it, so the clamps in [NexfyTheme] keep their meaning.
 */
fun Color.toHsl(): FloatArray {
    val high = max(red, max(green, blue))
    val low = min(red, min(green, blue))
    val lightness = (high + low) / 2f
    val spread = high - low
    if (spread == 0f) return floatArrayOf(0f, 0f, lightness)

    val saturation =
        if (lightness > .5f) spread / (2f - high - low) else spread / (high + low)
    val hue = when (high) {
        red -> (green - blue) / spread + if (green < blue) 6f else 0f
        green -> (blue - red) / spread + 2f
        else -> (red - green) / spread + 4f
    } * 60f
    return floatArrayOf(hue, saturation, lightness)
}

fun hslToColor(hsl: FloatArray): Color {
    val (hue, saturation, lightness) = Triple(hsl[0], hsl[1], hsl[2])
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val second = chroma * (1f - abs((hue / 60f) % 2f - 1f))
    val lift = lightness - chroma / 2f
    val (r, g, b) = when (((hue % 360f + 360f) % 360f / 60f).toInt()) {
        0 -> Triple(chroma, second, 0f)
        1 -> Triple(second, chroma, 0f)
        2 -> Triple(0f, chroma, second)
        3 -> Triple(0f, second, chroma)
        4 -> Triple(second, 0f, chroma)
        else -> Triple(chroma, 0f, second)
    }
    return Color(
        (r + lift).coerceIn(0f, 1f),
        (g + lift).coerceIn(0f, 1f),
        (b + lift).coerceIn(0f, 1f),
    )
}

/** WCAG relative luminance: sRGB undone, then the eye's weighting. */
private fun Color.luminance(): Float {
    fun straighten(channel: Float): Float =
        if (channel <= .03928f) channel / 12.92f else ((channel + .055f) / 1.055f).pow(2.4f)
    return .2126f * straighten(red) + .7152f * straighten(green) + .0722f * straighten(blue)
}

/** 1.0 for two equal colours, 21.0 for black on white. */
fun contrastRatio(one: Color, other: Color): Float {
    val a = one.luminance()
    val b = other.luminance()
    return (max(a, b) + .05f) / (min(a, b) + .05f)
}
