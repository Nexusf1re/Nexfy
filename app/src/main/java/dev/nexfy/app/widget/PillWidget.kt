package dev.nexfy.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import dev.nexfy.app.R

/**
 * "Nexfy · Pílula" (3x1, down to 2x1): a fully rounded pill with the
 * round cover, the song and a play/pause button. Exact sizing, so the pill
 * is as tall as the cell allows and its ends stay perfect half circles.
 */
class PillWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        NexfyWidgets.ensure(context)
        provideContent {
            val now by NexfyWidgets.shown.collectAsState()
            Pill(now)
        }
    }
}

@Composable
private fun Pill(now: Shown?) {
    val size = LocalSize.current
    val tones = Tones(now?.accent)
    val height = size.height.coerceIn(40.dp, 64.dp)
    val inner = height - 12.dp
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            GlanceModifier.fillMaxWidth().height(height).appWidgetBackground().cornerRadius(height / 2)
                .background(tones.surface).clickable(openApp(now)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Sheen()
            Row(GlanceModifier.fillMaxSize().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (now == null) {
                    Image(ImageProvider(R.drawable.ic_launcher_foreground), "Nexfy", GlanceModifier.size(inner))
                    Spacer(GlanceModifier.width(8.dp))
                    Title("Nexfy", tones.text, 14.sp)
                    return@Row
                }
                Cover(now.thumb, inner, inner / 2, tones)
                if (size.width >= 150.dp) {
                    Spacer(GlanceModifier.width(10.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Title(now.title, tones.text, 13.sp)
                        Subtitle(now.subtitle(), if (now.device != null) tones.vivid else tones.quiet, 11.sp)
                    }
                    Spacer(GlanceModifier.width(6.dp))
                } else {
                    Spacer(GlanceModifier.defaultWeight())
                }
                PlayButton(now.playing, inner, tones)
            }
        }
    }
}

class PillWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PillWidget()
}
