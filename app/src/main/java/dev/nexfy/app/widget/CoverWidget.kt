package dev.nexfy.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
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
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.unit.ColorProvider
import dev.nexfy.app.R

/**
 * "Nexfy · Capa" (2x2): the cover filling a rounded square, a scrim at the
 * bottom for the title and artist, and a round play/pause button. At the
 * smallest size only the button stays over the cover.
 */
class CoverWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(Small, Regular, Large))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        NexfyWidgets.ensure(context)
        provideContent {
            val now by NexfyWidgets.shown.collectAsState()
            CoverCard(now)
        }
    }

    private companion object {
        val Small = DpSize(110.dp, 110.dp)
        val Regular = DpSize(150.dp, 150.dp)
        val Large = DpSize(210.dp, 210.dp)
    }
}

private val White = ColorProvider(Color.White)
private val WhiteQuiet = ColorProvider(Color.White.copy(alpha = .78f))

@Composable
private fun CoverCard(now: Shown?) {
    val size = LocalSize.current
    val tones = Tones(now?.accent)
    val roomy = size.width >= 150.dp && size.height >= 150.dp
    val large = size.width >= 210.dp && size.height >= 210.dp
    Box(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(28.dp).background(tones.surface)
            .clickable(openApp(now)),
        contentAlignment = Alignment.Center,
    ) {
        if (now == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(ImageProvider(R.drawable.ic_nexfy_badge), "Nexfy", GlanceModifier.size(64.dp))
                if (roomy) {
                    Title("Nexfy", tones.text)
                    Subtitle("Toque para abrir", tones.quiet)
                }
            }
            return@Box
        }
        val art = now.art
        if (art != null) {
            Image(ImageProvider(art), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Image(ImageProvider(R.drawable.ic_nexfy_badge), null, GlanceModifier.size(if (roomy) 96.dp else 72.dp))
        }
        // Text and button stay readable on any cover, light ones too.
        Image(
            ImageProvider(R.drawable.widget_scrim), null, GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Column(
            GlanceModifier.fillMaxSize().padding(if (roomy) 14.dp else 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                if (roomy) {
                    Column(GlanceModifier.defaultWeight()) {
                        Title(now.title, White, if (large) 17.sp else 15.sp, lines = if (large) 2 else 1)
                        Spacer(GlanceModifier.height(1.dp))
                        Subtitle(now.subtitle(), WhiteQuiet, if (large) 13.sp else 12.sp)
                    }
                    Spacer(GlanceModifier.width(8.dp))
                } else {
                    Spacer(GlanceModifier.defaultWeight())
                }
                PlayButton(now.playing, if (large) 52.dp else if (roomy) 46.dp else 40.dp, tones, onCover = true)
            }
        }
    }
}

class CoverWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CoverWidget()
}
