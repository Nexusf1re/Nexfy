package dev.nexfy.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
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
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/**
 * "Nexfy · Player" (4x2): a larger cover on a surface tinted by it, title
 * and artist, a thin progress bar, previous, play/pause and next, and the
 * device's name when the song plays on another one.
 */
class PlayerWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(Regular, Tall, Large))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        NexfyWidgets.ensure(context)
        provideContent {
            val now by NexfyWidgets.shown.collectAsState()
            // Read so the progress bar moves on each tick of a running session.
            val tick by NexfyWidgets.tick.collectAsState()
            Player(now, tick)
        }
    }

    private companion object {
        val Regular = DpSize(250.dp, 110.dp)
        val Tall = DpSize(300.dp, 160.dp)
        val Large = DpSize(360.dp, 190.dp)
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun Player(now: Shown?, tick: Long) {
    val size = LocalSize.current
    val tones = Tones(now?.accent)
    Box(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(28.dp).background(tones.surface)
            .clickable(openApp(now)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Sheen()
        if (now == null) {
            Idle(tones, mark = 56.dp)
            return@Box
        }
        // Compact: one line under the title, a smaller button, tighter gaps.
        val roomy = size.height >= 160.dp
        val pad = if (roomy) 12.dp else 10.dp
        val cover = (size.height - pad * 2).coerceAtMost(size.width * .42f).coerceIn(72.dp, 160.dp)
        Row(GlanceModifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
            Cover(now.art, cover, 28.dp - pad, tones)
            Spacer(GlanceModifier.width(14.dp))
            Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                if (roomy) {
                    now.device?.let {
                        Text(
                            now.subtitle(), maxLines = 1,
                            style = TextStyle(color = tones.vivid, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        )
                        Spacer(GlanceModifier.height(2.dp))
                    }
                    Title(now.title, tones.text, if (size.height >= 190.dp) 18.sp else 17.sp)
                    Subtitle(now.artists, tones.quiet, 13.sp)
                } else {
                    Title(now.title, tones.text, 14.sp)
                    Subtitle(now.subtitle(), if (now.device != null) tones.vivid else tones.quiet, 12.sp)
                }
                Spacer(GlanceModifier.height(if (roomy) 12.dp else 6.dp))
                LinearProgressIndicator(
                    progress = now.progress(),
                    modifier = GlanceModifier.fillMaxWidth().height(3.dp).cornerRadius(2.dp),
                    color = tones.text,
                    backgroundColor = tones.faint,
                )
                Spacer(GlanceModifier.height(if (roomy) 8.dp else 4.dp))
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Previous(tones.text, if (roomy) 40.dp else 36.dp)
                    Spacer(GlanceModifier.defaultWeight())
                    PlayButton(now.playing, if (roomy) 50.dp else 40.dp, tones)
                    Spacer(GlanceModifier.defaultWeight())
                    Next(tones.text, if (roomy) 40.dp else 36.dp)
                }
            }
        }
    }
}

class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidget()
}
