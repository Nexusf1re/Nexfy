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
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
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
import androidx.glance.layout.padding
import androidx.glance.layout.width
import dev.nexfy.app.NexfyApp

/**
 * "Nexfy · Barra" (4x1): cover, title and artist (or where it plays), with
 * previous, play/pause and next. Narrower, it keeps the cover and the play
 * button. It is the original Nexfy widget, so placed ones keep working.
 */
class NexfyWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(Small, Medium, Wide, WideTall))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        NexfyWidgets.ensure(context)
        provideContent {
            val now by NexfyWidgets.shown.collectAsState()
            Bar(now)
        }
    }

    private companion object {
        val Small = DpSize(110.dp, 50.dp)
        val Medium = DpSize(180.dp, 50.dp)
        val Wide = DpSize(250.dp, 50.dp)
        val WideTall = DpSize(250.dp, 100.dp)
    }
}

@Composable
private fun Bar(now: Shown?) {
    val size = LocalSize.current
    val tones = Tones(now?.accent)
    Box(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(26.dp).background(tones.surface)
            .clickable(openApp(now)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Sheen()
        if (now == null) {
            Idle(tones)
            return@Box
        }
        val cover = (size.height - 16.dp).coerceIn(36.dp, 84.dp)
        val wide = size.width >= 250.dp
        Row(GlanceModifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Concentric with the widget's corners.
            Cover(now.thumb, cover, 18.dp, tones)
            if (size.width >= 180.dp) {
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Title(now.title, tones.text, if (size.height >= 100.dp) 16.sp else 15.sp)
                    Subtitle(now.subtitle(), if (now.device != null) tones.vivid else tones.quiet)
                }
                if (wide) Previous(tones.text)
            } else {
                Spacer(GlanceModifier.defaultWeight())
            }
            PlayButton(now.playing, 44.dp, tones)
            if (wide) Next(tones.text) else Spacer(GlanceModifier.width(2.dp))
        }
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
