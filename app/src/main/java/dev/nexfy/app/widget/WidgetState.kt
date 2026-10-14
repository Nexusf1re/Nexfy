package dev.nexfy.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit
import androidx.core.graphics.scale
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import dev.nexfy.app.NexfyApp
import dev.nexfy.app.ui.design.coverAccent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the widgets show: the song, where it plays, its cover and colour. */
internal data class Shown(
    val uri: String?,
    val title: String,
    val artists: String,
    val coverUrl: String?,
    val playing: Boolean,
    /** The device playing elsewhere, if any. */
    val device: String?,
    val durationMs: Long,
    val positionMs: Long,
    /** [SystemClock.elapsedRealtime] when [positionMs] was read. */
    val positionAt: Long,
    /** The cover for the large widgets, and a small one for the bars. */
    val art: Bitmap?,
    val thumb: Bitmap?,
    /** The cover's colour (ARGB), or null for the neutral look. */
    val accent: Int?,
) {
    fun progress(now: Long = SystemClock.elapsedRealtime()): Float {
        if (durationMs <= 0) return 0f
        val at = if (playing) positionMs + (now - positionAt) else positionMs
        return (at.toFloat() / durationMs).coerceIn(0f, 1f)
    }
}

/** A loaded cover, kept so redraws of the same song reuse it instead of decoding again. */
private class Art(val url: String, val art: Bitmap?, val thumb: Bitmap?, val accent: Int?)

/**
 * The state every Nexfy widget draws from. Widgets collect [shown] in their
 * composition, so a running widget session redraws as soon as it changes,
 * and a new one starts from the current value with the cover already
 * loaded (no blank frame).
 */
internal object NexfyWidgets {
    private const val PREFS = "widget"

    /** Cover sizes in pixels: enough for a 2x2 or 4x2 widget, small for the bars. */
    private const val ART_PX = 360
    private const val THUMB_PX = 160

    private val _shown = MutableStateFlow<Shown?>(null)

    /** Null when nothing has played yet. */
    val shown: StateFlow<Shown?> = _shown

    /** Bumped while playing so the progress bar of the player widget moves. */
    val tick = MutableStateFlow(0L)

    @Volatile private var loaded = false
    private val lock = Mutex()
    @Volatile private var lastArt: Art? = null

    /** Every widget of the family; [updateAll] on one without instances costs nothing. */
    private fun family(): List<GlanceAppWidget> = listOf(NexfyWidget(), CoverWidget(), PlayerWidget(), PillWidget())

    /** Load what to show before a widget draws: the app may have just started for it. */
    suspend fun ensure(context: Context) {
        if (!loaded) refresh(context)
    }

    /** Read the playing song (or the last one, paused, when the app has none) and its cover. */
    private suspend fun refresh(context: Context) = lock.withLock {
        val app = context.applicationContext as NexfyApp
        val s = app.bridge.state.value
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val track = s.track
        val base = if (track != null) {
            prefs.edit {
                putString("uri", track.uri)
                putString("title", track.title)
                putString("artists", track.artists)
                putString("cover", track.coverUrl)
                putLong("duration", track.durationMs.toLong())
                putLong("position", s.currentPositionMs())
            }
            Shown(
                track.uri, track.title, track.artists, track.coverUrl, s.playing, s.remote?.name,
                track.durationMs.toLong(), s.positionMs, s.positionAt, null, null, null,
            )
        } else {
            // The device is not kept: after a restart it may well have stopped.
            prefs.getString("title", null)?.let { title ->
                Shown(
                    prefs.getString("uri", null), title, prefs.getString("artists", "").orEmpty(),
                    prefs.getString("cover", null), false, null,
                    prefs.getLong("duration", 0), prefs.getLong("position", 0), 0, null, null, null,
                )
            }
        }
        val art = base?.coverUrl?.let { art(context, it) }
        val accent = art?.accent?.takeIf { app.library.coverAccent.value }
        _shown.value = base?.copy(art = art?.art, thumb = art?.thumb, accent = accent)
        loaded = true
    }

    private suspend fun art(context: Context, url: String): Art {
        lastArt?.takeIf { it.url == url && it.art != null }?.let { return it }
        val art = try {
            val request = ImageRequest.Builder(context).data(url).size(ART_PX).allowHardware(false).build()
            SingletonImageLoader.get(context).execute(request).image?.toBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val thumb = art?.let { withContext(Dispatchers.Default) { it.scale(THUMB_PX, THUMB_PX * it.height / it.width.coerceAtLeast(1)) } }
        val accent = art?.let { coverAccent(context, url)?.toArgb() }
        return Art(url, art, thumb, accent).also { lastArt = it }
    }

    private suspend fun placed(context: Context, widget: Class<out GlanceAppWidget>? = null): Boolean {
        val manager = GlanceAppWidgetManager(context)
        val kinds = widget?.let(::listOf) ?: family().map { it.javaClass }
        return kinds.any { runCatching { manager.getGlanceIds(it).isNotEmpty() }.getOrDefault(false) }
    }

    /**
     * Keep the widgets in step with what plays, here or elsewhere: they are
     * redrawn when the song, play state or device changes, never on
     * position ticks. Nothing is loaded while no widget is placed.
     */
    fun follow(app: NexfyApp, scope: CoroutineScope) {
        scope.launch {
            val playback = app.bridge.state
                .map { s -> listOf(s.track?.uri, s.track?.coverUrl, s.playing, s.remote?.name) }
                .distinctUntilChanged()
            combine(playback, app.library.coverAccent) { key, on -> key to on }.collectLatest {
                delay(250)
                if (!placed(app)) {
                    loaded = false
                    return@collectLatest
                }
                runCatching { refresh(app) }
                family().forEach { runCatching { it.updateAll(app) } }
            }
        }
        // The player's progress bar, sparingly: every 15 s while playing, and
        // only when a player widget is on the home screen.
        scope.launch {
            app.bridge.state.map { it.playing && it.track != null }.distinctUntilChanged().collectLatest { playing ->
                while (playing) {
                    delay(15_000)
                    if (!placed(app, PlayerWidget::class.java)) continue
                    val s = app.bridge.state.value
                    // A seek does not redraw the widgets: take the position again.
                    _shown.update { now ->
                        if (now == null || now.uri != s.track?.uri) now
                        else now.copy(positionMs = s.positionMs, positionAt = s.positionAt, playing = s.playing)
                    }
                    tick.value = SystemClock.elapsedRealtime()
                    runCatching { PlayerWidget().updateAll(app) }
                }
            }
        }
    }
}
