package dev.nexfy.app.ui.theme

import androidx.compose.animation.Animatable
import coil3.compose.LocalPlatformContext
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
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.coverAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Dark base: graphite, a hair off black so glass and hairlines read on it. */
val Graphite = Color(0xFF0A0B0D)

/** Light base: the icon's ivory. */
val Ivory = Color(0xFFF6F5F1)

/** Width of the thin lines that separate rows and frame glass. */
val Hairline = 0.5.dp

/** Figures of equal width, so times and counts line up and do not jitter as they change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/**
 * Graphite (dark) and ivory (light) neutrals; colour comes from the artwork,
 * never from dynamic system colours. Every role Material controls read is set.
 */
private fun neutral(dark: Boolean): ColorScheme {
    val bg = if (dark) Graphite else Ivory
    val fg = if (dark) Color(0xFFEEEFF2) else Color(0xFF141414)
    val surface = if (dark) Color(0xFF111215) else Color.White
    val raised = if (dark) Color(0xFF1A1B1F) else Color(0xFFEDEBE6)
    val quiet = if (dark) Color(0xFF8C8F98) else Color(0xFF66656A)
    val edge = if (dark) Color(0xFF2A2C31) else Color(0xFFDCD9D2)
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
    // Body text is tabular too: plain Text inherits it, so every time and count in a row lines up.
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp).tabular(),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp).tabular(),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    // Times, counts and track numbers.
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = .2.sp).tabular(),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = .3.sp).tabular(),
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
 * [accentArt] is the cover the accent follows (null: the icon's orange). The
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

/** The icon's orange: the accent while nothing plays. */
private fun brand(dark: Boolean) = if (dark) Color(0xFFFF6A47) else Color(0xFFD9431F)

/** A State, never a Color: only composables that read it follow its changes. */
private val LocalAccent = staticCompositionLocalOf<State<Color>> { mutableStateOf(brand(true)) }

/**
 * The accent: the playing cover's colour made legible, or the icon's orange.
 * It glides to each new colour; nothing here reads it, so the theme itself
 * never recomposes for it.
 */
@Composable
private fun rememberAccent(art: Flow<String?>?, dark: Boolean): State<Color> {
    val context = LocalPlatformContext.current
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
    val hsl = raw.toHsl()
    hsl[1] = hsl[1].coerceIn(.45f, .85f)
    hsl[2] = if (dark) hsl[2].coerceIn(.55f, .7f) else hsl[2].coerceIn(.3f, .45f)
    val background = if (dark) Graphite else Ivory
    var color = hslToColor(hsl)
    while (contrastRatio(color, background) < 4.5f && hsl[2] in .15f..0.85f) {
        hsl[2] += if (dark) .03f else -.03f
        color = hslToColor(hsl)
    }
    return color
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
