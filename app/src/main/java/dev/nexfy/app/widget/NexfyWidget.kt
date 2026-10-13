package dev.nexfy.app.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import dev.nexfy.app.MainActivity
import dev.nexfy.app.NexfyApp
import dev.nexfy.app.R

/** What the widget shows; kept so it survives the app process being gone. */
private data class Shown(
    val title: String,
    val artists: String,
    val coverUrl: String?,
    val playing: Boolean,
    /** The device playing elsewhere, if any. */
    val device: String?,
)

private const val PREFS = "widget"

/** The song playing now, or the last one shown (paused) when the app is not running. */
private fun shown(context: Context): Shown? {
    val app = context.applicationContext as NexfyApp
    val s = app.bridge.state.value
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val track = s.track
    if (track != null) {
        val now = Shown(track.title, track.artists, track.coverUrl, s.playing, s.remote?.name)
        prefs.edit {
            putString("title", now.title)
            putString("artists", now.artists)
            putString("cover", now.coverUrl)
            putString("device", now.device)
        }
        return now
    }
    val title = prefs.getString("title", null) ?: return null
    return Shown(title, prefs.getString("artists", "").orEmpty(), prefs.getString("cover", null), false, prefs.getString("device", null))
}

private suspend fun cover(context: Context, url: String): Bitmap? = runCatching {
    val request = ImageRequest.Builder(context).data(url).size(320).allowHardware(false).build()
    SingletonImageLoader.get(context).execute(request).image?.toBitmap()
}.getOrNull()

/** A dark tint of the cover, like the player's backdrop. */
private fun tint(art: Bitmap?): Color {
    val base = art?.let { Palette.from(it).generate().getDominantColor(0xFF181818.toInt()) } ?: 0xFF181818.toInt()
    return Color(ColorUtils.blendARGB(base, 0xFF000000.toInt(), .55f))
}

/**
 * Home-screen widget: the song's cover, title and artist (or where it
 * plays), with previous, play/pause and next. Narrow, it is the cover with
 * a play button; tapping it opens the app.
 */
class NexfyWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(Small, Wide))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val now = shown(context)
        val art = now?.coverUrl?.let { cover(context, it) }
        val bg = tint(art)
        provideContent { Content(now, art, bg) }
    }

    private companion object {
        val Small = DpSize(110.dp, 50.dp)
        val Wide = DpSize(220.dp, 50.dp)
    }
}

@Composable
private fun Content(now: Shown?, art: Bitmap?, bg: Color) {
    val size = LocalSize.current
    val open = actionStartActivity<MainActivity>()
    Box(
        GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(ColorProvider(bg)).clickable(open)
            .padding(10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (now == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.ic_launcher_foreground), "Nexfy", GlanceModifier.size(44.dp))
                Spacer(GlanceModifier.width(8.dp))
                Column {
                    Text("Nexfy", style = TextStyle(color = ColorProvider(Color.White), fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    Text("Toque para abrir", style = TextStyle(color = ColorProvider(Color.White.copy(alpha = .7f)), fontSize = 12.sp))
                }
            }
            return@Box
        }
        val coverSize = (size.height - 20.dp).coerceIn(36.dp, 120.dp)
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Cover(art, coverSize)
            if (size.width >= 220.dp) {
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(
                        now.title, maxLines = 1,
                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    )
                    Text(
                        now.device?.let { "Em $it" } ?: now.artists, maxLines = 1,
                        style = TextStyle(
                            color = ColorProvider(if (now.device != null) Color(0xFF1ED760) else Color.White.copy(alpha = .7f)),
                            fontSize = 13.sp,
                        ),
                    )
                }
                Control(R.drawable.ic_widget_previous, "Anterior", actionRunCallback<PreviousAction>())
            } else {
                Spacer(GlanceModifier.defaultWeight())
            }
            PlayButton(now.playing)
            Control(R.drawable.ic_widget_next, "Próxima", actionRunCallback<NextAction>())
        }
    }
}

@Composable
private fun Cover(art: Bitmap?, size: androidx.compose.ui.unit.Dp) {
    val modifier = GlanceModifier.size(size).cornerRadius(14.dp)
    if (art != null) {
        Image(ImageProvider(art), null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(ColorProvider(Color(0xFF2A2A2A))), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.ic_launcher_foreground), null, GlanceModifier.size(size))
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean) {
    Box(
        GlanceModifier.size(44.dp).cornerRadius(22.dp).background(ColorProvider(Color.White))
            .clickable(actionRunCallback<PlayPauseAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            ImageProvider(if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play),
            if (playing) "Pausar" else "Tocar",
            GlanceModifier.size(24.dp),
        )
    }
}

@Composable
private fun Control(icon: Int, label: String, action: androidx.glance.action.Action) {
    Box(GlanceModifier.size(40.dp).fillMaxHeight().clickable(action), contentAlignment = Alignment.Center) {
        Image(ImageProvider(icon), label, GlanceModifier.size(24.dp))
    }
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        (context.applicationContext as NexfyApp).commands.playPause()
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        (context.applicationContext as NexfyApp).commands.next()
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        (context.applicationContext as NexfyApp).commands.previous()
}

class NexfyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NexfyWidget()
}
