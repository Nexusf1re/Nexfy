package dev.nexfy.app.ui.theme

import androidx.compose.animation.Animatable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.coverAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Neutral black/white scheme; colour comes from the artwork, never from
 * dynamic system colours. Every role Material controls read is set.
 */
private fun neutral(dark: Boolean): ColorScheme {
    val bg = if (dark) Color.Black else Color(0xFFFAFAFA)
    val fg = if (dark) Color(0xFFF5F5F5) else Color(0xFF151515)
    val surface = if (dark) Color(0xFF0D0D0F) else Color.White
    val raised = if (dark) Color(0xFF19191C) else Color(0xFFEFEFF1)
    val quiet = if (dark) Color(0xFFA0A0A6) else Color(0xFF616168)
    val edge = if (dark) Color(0xFF353539) else Color(0xFFD5D5DA)
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = fg, onPrimary = bg, primaryContainer = raised, onPrimaryContainer = fg,
        inversePrimary = bg, secondary = fg, onSecondary = bg, secondaryContainer = raised, onSecondaryContainer = fg,
        tertiary = fg, onTertiary = bg, tertiaryContainer = raised, onTertiaryContainer = fg,
        background = bg, onBackground = fg, surface = surface, onSurface = fg, surfaceVariant = raised, onSurfaceVariant = quiet,
        surfaceTint = Color.Transparent, inverseSurface = fg, inverseOnSurface = bg,
        error = Color(0xFFFF6B6B), onError = bg, errorContainer = raised, onErrorContainer = fg,
        outline = edge, outlineVariant = edge.copy(alpha = .5f),
        scrim = Color.Black, surfaceDim = surface, surfaceBright = raised, surfaceContainerLowest = bg,
        surfaceContainerLow = surface, surfaceContainer = surface, surfaceContainerHigh = raised, surfaceContainerHighest = raised,
    )
}

private val Type = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-1).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-.5f).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)

/**
 * Appearance and behaviour settings that drawing code reads. Motion has its
 * own switch ([Motion.reduced]) since animation specs are built outside
 * composition too.
 */
@Immutable
data class Look(
    /** The cover backdrop drifts; off, it is drawn once and holds still. */
    val animatedBackdrop: Boolean = true,
    /** Multiplies the lyrics' text sizes. */
    val lyricsScale: Float = 1f,
    /** Faces of who added each song, where more than one person adds. */
    val showAddedBy: Boolean = true,
    /** Vibration on touches; [dev.nexfy.app.ui.design.Haptics] stays silent when off. */
    val haptics: Boolean = true,
)

/** Dynamic, not static: a change recomposes only what reads it. */
val LocalLook = compositionLocalOf { Look() }

/**
 * [accentArt] is the cover the accent follows (null: the brand green). The
 * scheme is remembered: a new instance would recompose every screen.
 */
@Composable
fun NexfyTheme(
    dark: Boolean = isSystemInDarkTheme(),
    accentArt: Flow<String?>? = null,
    look: Look = Look(),
    content: @Composable () -> Unit,
) {
    val scheme = remember(dark) { neutral(dark) }
    val accent = rememberAccent(accentArt, dark)
    MaterialTheme(colorScheme = scheme, typography = Type) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, LocalAccent provides accent, LocalLook provides look) { content() }
    }
}

private fun brand(dark: Boolean) = if (dark) Color(0xFF1ED760) else Color(0xFF14A44A)

/** A State, never a Color: only composables that read it follow its changes. */
private val LocalAccent = staticCompositionLocalOf<State<Color>> { mutableStateOf(brand(true)) }

/**
 * The accent: the playing cover's colour made legible, or the brand green.
 * It glides to each new colour; nothing here reads it, so the theme itself
 * never recomposes for it.
 */
@Composable
private fun rememberAccent(art: Flow<String?>?, dark: Boolean): State<Color> {
    val context = LocalContext.current
    val accent = remember { Animatable(brand(dark)) }
    LaunchedEffect(art, dark) {
        (art ?: flowOf(null)).collectLatest { url ->
            // Between songs the track can be missing for a moment: keep the colour.
            if (url == null) delay(1_500)
            val target = url?.let { coverAccent(context, it) }?.let { legible(it, dark) } ?: brand(dark)
            if (accent.targetValue != target) accent.animateTo(target, Motion.standard(600))
        }
    }
    return remember(accent) { accent.asState() }
}

/**
 * [raw] as an accent on this theme: saturation and lightness clamped, then
 * lightness pushed until text in it reads on the background (4.5:1).
 */
private fun legible(raw: Color, dark: Boolean): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(raw.toArgb(), hsl)
    hsl[1] = hsl[1].coerceIn(.45f, .85f)
    hsl[2] = if (dark) hsl[2].coerceIn(.55f, .7f) else hsl[2].coerceIn(.3f, .45f)
    val background = if (dark) Color.Black.toArgb() else Color(0xFFFAFAFA).toArgb()
    var argb = ColorUtils.HSLToColor(hsl)
    while (ColorUtils.calculateContrast(argb, background) < 4.5 && hsl[2] in .15f..0.85f) {
        hsl[2] += if (dark) .03f else -.03f
        argb = ColorUtils.HSLToColor(hsl)
    }
    return Color(argb)
}

/**
 * The app accent, used sparingly (active tab, playing song, small
 * highlights): the playing song's cover colour when "Cor da música" is on.
 */
@Composable
fun accentColor(): Color = LocalAccent.current.value

/** The accent unread, for reading at draw time or only on some branches. */
@Composable
fun accentState(): State<Color> = LocalAccent.current

val ColorScheme.isDark get() = background.red < .5f
