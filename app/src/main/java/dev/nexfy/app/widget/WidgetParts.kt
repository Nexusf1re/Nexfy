package dev.nexfy.app.widget

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.nexfy.app.MainActivity
import dev.nexfy.app.R
import dev.nexfy.app.ui.player.OpenPlayer

private val Brand = Color(0xFF1ED760)
private val BrandLight = Color(0xFF14A44A)

/**
 * A widget's colours for the light and the dark home screen, tinted by the
 * cover's colour like the player's backdrop. Without one, neutral glass.
 */
internal class Tones(accent: Int?) {
    /** The surface: the cover colour deepened (dark) or washed out (light). */
    val surface: ColorProvider = ColorProvider(
        day = accent?.let { Color(ColorUtils.blendARGB(it, 0xFFFFFFFF.toInt(), .84f)) } ?: Color(0xFFF1F1F4),
        night = accent?.let { Color(ColorUtils.blendARGB(it, 0xFF000000.toInt(), .64f)) } ?: Color(0xFF18181B),
    )
    val text: ColorProvider = ColorProvider(day = Color(0xFF151515), night = Color(0xFFF5F5F5))
    val quiet: ColorProvider = ColorProvider(day = Color(0xFF55555C), night = Color(0xFFB9B9C0))
    /** The progress bar's track. */
    val faint: ColorProvider = ColorProvider(day = Color(0x22000000), night = Color(0x33FFFFFF))

    /** The cover's colour made legible on [surface], for small highlights. */
    val vivid: ColorProvider = ColorProvider(
        day = accent?.let { legible(it, dark = false) } ?: BrandLight,
        night = accent?.let { legible(it, dark = true) } ?: Brand,
    )

    /** The play button: the strongest thing on the widget. */
    val button: ColorProvider = ColorProvider(day = Color(0xFF151515), night = Color.White)
    val onButton: ColorProvider = ColorProvider(day = Color.White, night = Color(0xFF121212))

    /** Behind a missing cover. */
    val placeholder: ColorProvider = ColorProvider(day = Color(0x14000000), night = Color(0x1FFFFFFF))
}

/** [raw] with saturation and lightness clamped so small text in it reads on the widget. */
private fun legible(raw: Int, dark: Boolean): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(raw, hsl)
    hsl[1] = hsl[1].coerceIn(.45f, .85f)
    hsl[2] = if (dark) hsl[2].coerceIn(.62f, .74f) else hsl[2].coerceIn(.28f, .4f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/** Opens the app: on the full player when there is a song to show in it. */
@Composable
internal fun openApp(now: Shown?): Action =
    actionStartActivity(Intent(LocalContext.current, MainActivity::class.java).putExtra(OpenPlayer.EXTRA, now != null))

/** Before anything has played: the Nexfy mark and an invitation. */
@Composable
internal fun Idle(tones: Tones, mark: Dp = 44.dp) {
    Row(GlanceModifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(ImageProvider(R.drawable.ic_launcher_foreground), "Nexfy", GlanceModifier.size(mark))
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Title("Nexfy", tones.text)
            Subtitle("Toque para abrir", tones.quiet)
        }
    }
}

@Composable
internal fun Title(text: String, color: ColorProvider, size: TextUnit = 15.sp, lines: Int = 1) {
    Text(
        text, maxLines = lines,
        style = TextStyle(color = color, fontSize = size, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif),
    )
}

@Composable
internal fun Subtitle(text: String, color: ColorProvider, size: TextUnit = 13.sp) {
    Text(text, maxLines = 1, style = TextStyle(color = color, fontSize = size, fontFamily = FontFamily.SansSerif))
}

/** Under the title: where the song plays when that is elsewhere, else its artists. */
internal fun Shown.subtitle(): String = device?.let { if (playing) "Tocando em $it" else "Em $it" } ?: artists

/** The cover with rounded corners, or the Nexfy mark on a soft tile while it is missing. */
@Composable
internal fun Cover(art: Bitmap?, size: Dp, corner: Dp, tones: Tones) {
    val modifier = GlanceModifier.size(size).cornerRadius(corner)
    if (art != null) {
        Image(ImageProvider(art), null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(tones.placeholder), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.ic_launcher_foreground), null, GlanceModifier.size(size))
        }
    }
}

/** A round play/pause button; [onCover] keeps it white over artwork in both themes. */
@Composable
internal fun PlayButton(playing: Boolean, size: Dp, tones: Tones, onCover: Boolean = false) {
    val bg = if (onCover) ColorProvider(Color.White) else tones.button
    val fg = if (onCover) ColorProvider(Color(0xFF121212)) else tones.onButton
    Box(
        GlanceModifier.size(size).cornerRadius(size / 2).background(bg).clickable(actionRunCallback<PlayPauseAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            ImageProvider(if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play),
            if (playing) "Pausar" else "Tocar",
            GlanceModifier.size(size * .52f),
            colorFilter = ColorFilter.tint(fg),
        )
    }
}

/** Previous or next: a bare icon with a generous touch target. */
@Composable
internal fun Control(icon: Int, label: String, action: Action, tint: ColorProvider, size: Dp = 40.dp) {
    Box(GlanceModifier.size(size).cornerRadius(size / 2).clickable(action), contentAlignment = Alignment.Center) {
        Image(ImageProvider(icon), label, GlanceModifier.size(size * .6f), colorFilter = ColorFilter.tint(tint))
    }
}

@Composable
internal fun Previous(tint: ColorProvider, size: Dp = 40.dp) =
    Control(R.drawable.ic_widget_previous, "Anterior", actionRunCallback<PreviousAction>(), tint, size)

@Composable
internal fun Next(tint: ColorProvider, size: Dp = 40.dp) =
    Control(R.drawable.ic_widget_next, "Próxima", actionRunCallback<NextAction>(), tint, size)

/** A soft light along the top edge, the glass look on the tinted surface. */
@Composable
internal fun Sheen() {
    Image(
        ImageProvider(R.drawable.widget_sheen), null, GlanceModifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
    )
}
