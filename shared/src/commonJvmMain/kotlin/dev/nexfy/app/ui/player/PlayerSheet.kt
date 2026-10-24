package dev.nexfy.app.ui.player

import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.compose.LocalPlatformContext
import dev.nexfy.app.elapsedRealtimeMs
import dev.nexfy.app.ui.BackGesture
import dev.nexfy.app.ui.ProgressiveBackGesture
import dev.nexfy.app.ui.SystemBarAppearance
import dev.nexfy.app.ui.blurEffectSupported
import dev.nexfy.app.ui.design.GlassBackdrop
import dev.nexfy.app.ui.design.artInk
import dev.nexfy.app.ui.design.artWash
import dev.nexfy.app.ui.design.awaitShown

import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.foundation.lazy.itemsIndexed
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.rounded.Close
import dev.nexfy.app.ui.design.GlassSheet
import dev.nexfy.app.ui.design.ambientClock
import dev.nexfy.app.ui.design.refusalShake
import dev.nexfy.app.ui.design.PlayingBars
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.theme.accentColor
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.pow
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import dev.nexfy.app.playback.PlaybackSnapshot
import dev.nexfy.app.ui.design.Bone
import dev.nexfy.app.ui.design.TextBone
import dev.nexfy.app.ui.design.boneWidth
import dev.nexfy.app.ui.design.LivingArtwork
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.ThinSlider
import dev.nexfy.app.ui.design.snapNow
import dev.nexfy.app.ui.design.liquidGlass
import androidx.compose.runtime.CompositionLocalProvider
import dev.nexfy.app.ui.design.LocalHaze
import dev.nexfy.app.ui.design.glassSource
import dev.nexfy.app.ui.design.GlassLevel
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.design.springPress
import dev.nexfy.app.ui.theme.isDark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.nexfy_core.Device
import uniffi.nexfy_core.Lyrics
import uniffi.nexfy_core.LyricsTranslation
import uniffi.nexfy_core.LyricsLine
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import uniffi.nexfy_core.AlbumDetail
import uniffi.nexfy_core.QueueEntry
import uniffi.nexfy_core.UserFace
import androidx.compose.runtime.produceState
import uniffi.nexfy_core.RepeatMode
import uniffi.nexfy_core.Track
import uniffi.nexfy_core.TrackInfo

/** What the player can ask playback to do. */
interface PlayerControls {
    fun playPause()
    fun next()
    fun previous()
    fun seek(positionMs: Long)
    fun setShuffle(on: Boolean)
    fun setRepeat(mode: RepeatMode)

    /** The lyric lines in [target], or null when the song is already in it. */
    suspend fun translateLyrics(trackUri: String, lines: List<String>, target: String, email: String?): LyricsTranslation?

    /** Lyrics for [track], or null when nobody has them. */
    suspend fun lyrics(track: TrackInfo): Lyrics?

    /** Jump to a queue entry [ahead] positions after the current song. */
    fun skipTo(entry: QueueEntry, ahead: Int)

    /** The account's Spotify Connect devices, this phone included. */
    suspend fun devices(): List<Device>

    /** Move playback to [device]. */
    fun transfer(device: Device)

    /** Volume of the device playing elsewhere, 0 to 100. */
    fun setVolume(percent: Int)

    /** Open an artist page (from the player's song menu). */
    fun openArtist(id: String, name: String)

    /** Open an album page (from the player's song menu). */
    fun openAlbum(id: String, name: String, artists: String, imageUrl: String?)

    /** What plays next on the device playing elsewhere. */
    suspend fun remoteQueue(): List<Track>

    /** Open the equalizer page (the player folds down first). */
    fun openEqualizer()

    /** Show the devices sheet. */
    fun openDevices()

    /** Metadata for queue entries, in order. */
    suspend fun tracksInfo(uris: List<String>): List<Track>

    /** The album [trackUri] is on, for the back of the cover. */
    suspend fun albumOf(trackUri: String): AlbumDetail?

    /** Play [album] from its track at [index]. */
    fun playAlbum(album: AlbumDetail, index: Int)

    /** Play the radio of [seedUri] and open its playlist. */
    fun startRadio(seedUri: String)
}

/** A getter, so it follows [Motion.reduced]. */
private val settle get() = Motion.settle<Float>(247f)

/** Letting go below this closes an open player; above [OPEN_AT], the mini one opens. */
private const val CLOSE_AT = .8f
private const val OPEN_AT = .2f

/**
 * How far the full player is open, 0 to 1, for the pages under it: they
 * step back while it covers them and come forward as it is pulled down.
 * Read only while drawing, so following it recomposes nothing.
 */
object PlayerReveal {
    var open by mutableFloatStateOf(0f)
        internal set
}

/** Requests from outside the composition (a widget tap) to open the full player. */
object OpenPlayer {
    /** The intent extra that asks for it. */
    const val EXTRA = "dev.nexfy.app.OPEN_PLAYER"

    /** When it was asked ([elapsedRealtimeMs]); 0 when nothing is pending. */
    internal val requested = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Open the full player as soon as a window is up. */
    fun request() {
        requested.value = elapsedRealtimeMs()
    }
}

/** Song change: the new title rises in as the old one fades up and out. */
private fun AnimatedContentTransitionScope<TrackInfo>.songChange(): ContentTransform =
    if (initialState.uri == targetState.uri) {
        EnterTransition.None togetherWith ExitTransition.None
    } else {
        (fadeIn(tween(Motion.scaled(320), delayMillis = 60)) + slideInVertically(Motion.settle()) { it / 3 }) togetherWith
            (fadeOut(tween(Motion.scaled(160))) + slideOutVertically(tween(Motion.scaled(200))) { -it / 4 }) using SizeTransform(clip = false)
    }
private fun fmt(ms: Long) = (ms.coerceAtLeast(0) / 1000).let { "%d:%02d".format(it / 60, it % 60) }

/** Current position, ticking while playing. */
@Composable
private fun rememberPosition(s: PlaybackSnapshot): Long {
    var position by remember { mutableLongStateOf(s.currentPositionMs()) }
    LaunchedEffect(s) {
        while (true) {
            position = s.currentPositionMs()
            if (!s.playing) break
            delay(250)
            awaitShown()
        }
    }
    return position
}

/**
 * The mini player that becomes the full-screen player. `f` runs from 0
 * (mini) to 1 (full) and drives everything: cover size and position,
 * backdrop, corners, controls. Dragging moves `f` with the finger; letting go
 * settles on a spring. Predictive back shrinks it along the gesture.
 *
 * The layout is fixed at full screen and never changes while expanding:
 * everything that animates is read in graphicsLayer/draw, so animation
 * frames neither recompose nor remeasure.
 */
@Composable
fun PlayerSheet(s: PlaybackSnapshot, controls: PlayerControls) {
    // Song changes can report no song for a moment (another device between
    // songs): keep the last one so the player and its lyrics stay open.
    val lastTrack = remember { arrayOfNulls<TrackInfo>(1) }
    s.track?.let { lastTrack[0] = it }
    // Lyrics open as they were left, across launches.
    val library = dev.nexfy.app.LocalServices.current.library
    var showLyrics by rememberSaveable { mutableStateOf(library.lyricsOpen) }
    var lyricsFull by rememberSaveable { mutableStateOf(false) }
    // Tapping the cover turns it over to the album it is from.
    var flipped by rememberSaveable { mutableStateOf(false) }
    var playerOpen by remember { mutableStateOf(false) }
    dev.nexfy.app.ui.MetricsState("player", if (!playerOpen) null else if (lyricsFull) "lyrics-full" else if (showLyrics) "lyrics" else "open")
    val track = s.track ?: lastTrack[0] ?: return
    val c = MaterialTheme.colorScheme
    // The player follows the theme, as the collection headers do: white over
    // the dark art, the theme's ink over the pale wash of a light theme.
    val ink = artInk()
    val wash = artWash()
    // A photo under words needs more covering on a pale wash than on a dark one.
    val veilAt = if (c.isDark) .46f else .60f
    val scope = rememberCoroutineScope()
    val f = remember { Animatable(0f).apply { updateBounds(0f, 1f) } }
    var back by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val position = rememberPosition(s)
    val duration = track.durationMs.toLong().coerceAtLeast(1)
    // Next only when something follows in the queue: the rest of the context
    // is in it, and with repeat on it goes round to the first song again (a
    // lone song has nothing after it). Autoplay does not run here. Another
    // device keeps its own queue: assume it has one.
    val hasNext = s.remote != null || s.queue.next.isNotEmpty()
    var showQueue by remember { mutableStateOf(false) }
    // The living backdrop is the glass source for the player's own controls.
    val playerGlass = remember { GlassBackdrop() }
    var showActions by remember { mutableStateOf(false) }
    if (showActions) {
        TrackActionsSheet(track, controls, onNavigate = { scope.launch { f.animateTo(0f, settle) } }) { showActions = false }
    }
    if (showQueue) QueueSheet(s, controls) { showQueue = false }

    val pf = { f.value.coerceIn(0f, 1f) }
    val expanded by remember { derivedStateOf { f.value > 0.5f } }
    LaunchedEffect(expanded) {
        playerOpen = expanded
        if (!expanded) flipped = false
    }
    // The player follows the theme now, so the system bar icons are the
    // theme's throughout, open or collapsed.
    SystemBarAppearance(light = !c.isDark)
    LaunchedEffect(Unit) {
        OpenPlayer.requested.collect { at ->
            if (at == 0L) return@collect
            OpenPlayer.requested.value = 0L
            // A stale request (no song came for a while) would open the player out of nowhere.
            if (elapsedRealtimeMs() - at < 10_000) f.animateTo(1f, settle)
        }
    }
    LaunchedEffect(Unit) { snapshotFlow { f.value }.collect { PlayerReveal.open = it } }
    dev.nexfy.app.ui.MetricsState("sheet", if (f.isRunning) "moving" else null)
    val collapsed by remember { derivedStateOf { f.value < .05f } }
    val showMini by remember { derivedStateOf { f.value < .6f } }
    val showGlass by remember { derivedStateOf { f.value < .99f } }
    val showFull by remember { derivedStateOf { f.value > .4f } }
    val showBg by remember { derivedStateOf { f.value > 0.01f } }
    val settled by remember { derivedStateOf { f.value > .9f } }

    ProgressiveBackGesture(enabled = expanded) { progress ->
        try {
            progress.collect { back = it.progress }
            scope.launch { f.animateTo(0f, settle) }
            back = 0f
        } catch (e: CancellationException) {
            animate(back, 0f, animationSpec = settle) { v, _ -> back = v }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullH = constraints.maxHeight.toFloat()
        val fullW = constraints.maxWidth.toFloat()
        val miniH = with(density) { 68.dp.toPx() }
        val margin = with(density) { 16.dp.toPx() }
        // Where a drag began, and whether it has passed the point where letting
        // go closes (from open) or opens (from the mini player): a tick says so.
        val dragFrom = remember { floatArrayOf(0f) }
        val committed = remember { booleanArrayOf(false) }
        fun commits(v: Float) = if (dragFrom[0] > .5f) v < CLOSE_AT else v > OPEN_AT
        val dragState = rememberDraggableState { d ->
            val v = (f.value - d / (fullH - miniH)).coerceIn(0f, 1f)
            scope.snapNow(f, v)
            if (commits(v) != committed[0]) {
                committed[0] = !committed[0]
                haptics.tick()
            }
        }
        // Starting and ending a vertical drag on the player: the cover has a
        // vertical gesture of its own and hands the ones it does not want here.
        fun startSheetDrag() {
            dragFrom[0] = f.value
            committed[0] = false
            haptics.tick()
        }
        // A short pull is enough either way; a flick wins over distance.
        suspend fun settleSheet(v: Float) {
            val target = when {
                v < -Motion.FlingVelocity -> 1f
                v > Motion.FlingVelocity -> 0f
                commits(f.value) -> if (dragFrom[0] > .5f) 0f else 1f
                else -> if (dragFrom[0] > .5f) 1f else 0f
            }
            f.animateTo(target, settle, initialVelocity = -v / (fullH - miniH))
        }
        // Mini: a compact card with equal side margins, just above the dock and the gesture bar.
        val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
        val dockH = with(density) { 82.dp.toPx() }
        val miniTop = fullH - miniH - margin - navBottom - dockH
        val surface = c.surface

        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val p = pf()
                    translationY = lerp(miniTop, 0f, p)
                    val sc = 1f - back * 0.12f
                    scaleX = sc
                    scaleY = sc
                    transformOrigin = TransformOrigin(.5f, 0f)
                    // Corners round early in a pull: the player turns into a card at once.
                    val corner = 34f * (1f - p).coerceIn(0f, 1f).pow(.45f)
                    shape = SheetShape(lerp(margin, 0f, p), lerp(miniH, fullH, p).coerceIn(miniH, fullH), (corner + back * 36f) * density.density)
                    clip = true
                }
                .drawBehind { drawRect(surface.copy(alpha = (pf() * 1.6f).coerceIn(0f, 1f))) }
                .draggable(
                    dragState,
                    Orientation.Vertical,
                    onDragStarted = { startSheetDrag() },
                    onDragStopped = { v -> settleSheet(v) },
                ),
        ) {
            // Glass mini player: blurs what is behind it, only in the mini band.
            if (showGlass) {
                Box(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(68.dp)
                        .graphicsLayer { alpha = (1f - pf()).coerceIn(0f, 1f) }
                        .liquidGlass(RoundedCornerShape(34.dp), c.isDark, GlassLevel.Strong),
                )
            }
            // Living backdrop: a slow mesh gradient in the cover's colours.
            if (showBg) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = pf() }.glassSource(playerGlass)) {
                    LivingArtwork(track.coverUrl, Modifier.fillMaxSize(), dim = .2f, pale = !c.isDark)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, wash.copy(.15f), wash.copy(.55f)))))
                }
            }

            val maxW = this@BoxWithConstraints.maxWidth
            val maxH = this@BoxWithConstraints.maxHeight
            val insetTop = with(density) { WindowInsets.safeDrawing.getTop(density).toDp() }
            val insetBottom = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }
            var controlsH by remember { mutableStateOf(300.dp) }
            val artSpace = (maxH - insetTop - 56.dp - controlsH - insetBottom - 12.dp).coerceAtLeast(120.dp)
            val artFull = minOf(maxW - 56.dp, artSpace - 16.dp, 420.dp).coerceAtLeast(120.dp)
            val fullX = (maxW - artFull) / 2
            val fullY = insetTop + 56.dp + (artSpace - artFull) / 2
            val artFullPx = with(density) { artFull.toPx() }
            val fullXPx = with(density) { fullX.toPx() }
            val fullYPx = with(density) { fullY.toPx() }
            val miniArt = with(density) { 48.dp.toPx() }
            val miniX = margin + with(density) { 10.dp.toPx() }
            val miniY = with(density) { 10.dp.toPx() }

            // The cover breathes: full while playing, recedes when paused (not under lyrics).
            val breath = animateFloatAsState(if (s.playing || !expanded || showLyrics) 1f else 0.84f, Motion.settle(169f), label = "breath")
            // Throwable cover: a strong horizontal drag skips, a weak one springs back.
            val throwX = remember { Animatable(0f) }
            val throwable = settled && !showLyrics && !flipped
            // Whatever interrupts a throw (the lyrics, a flip, the player closing),
            // the cover comes home: left out there it is simply gone from the screen.
            LaunchedEffect(throwable) {
                if (!throwable && throwX.value != 0f) throwX.animateTo(0f, Motion.settle(520f))
            }
            val currentUri by rememberUpdatedState(track.uri)
            // Plain holder: set by a throw, read once by the cover transition.
            val thrown = remember { booleanArrayOf(false) }
            var tapHint by remember { mutableStateOf<Pair<Long, Int>?>(null) }

            // Halfway in, at the finger: on screen already, though letting go
            // now would take them away again.
            var lyricsPeek by remember { mutableStateOf(false) }
            // On screen, whether they are in or still coming.
            val lyricsShown = showLyrics || lyricsPeek

            var lyrics by remember(track.uri) { mutableStateOf<LyricsState>(LyricsState.Loading) }
            LaunchedEffect(track.uri, lyricsShown) {
                if (!lyricsShown || lyrics != LyricsState.Loading) return@LaunchedEffect
                lyrics = try {
                    controls.lyrics(track)?.let { LyricsState.Found(it) } ?: LyricsState.None
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    LyricsState.Failed
                }
            }
            // The lyric in the phone's language, fetched once a song and kept
            // on disk. Off by default: it spends a free daily allowance.
            var translating by rememberSaveable { mutableStateOf(false) }
            var translation by remember(track.uri) { mutableStateOf<TranslationState>(TranslationState.Off) }
            val email by library.translateEmail.collectAsState()
            LaunchedEffect(track.uri, translating, lyrics) {
                val found = (lyrics as? LyricsState.Found)?.lyrics
                if (!translating || found == null || found.instrumental) return@LaunchedEffect
                if (translation is TranslationState.Done || translation is TranslationState.Same) return@LaunchedEffect
                translation = TranslationState.Working
                translation = try {
                    // The app speaks Brazilian Portuguese, whatever the phone is set to.
                    val got = controls.translateLyrics(track.uri, found.lines.map { it.text }, "pt-BR", email.takeIf { it.isNotBlank() })
                    if (got == null) TranslationState.Same else TranslationState.Done(got.lines)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TranslationState.Failed(e.message ?: "A tradução não veio")
                }
            }
            val meaning = (translation as? TranslationState.Done)?.lines.takeIf { translating }

            // 0 to 1 as lyrics come in: the cover blurs and darkens behind them.
            // Animated here rather than read from [showLyrics]: a pull up the
            // cover brings them in with the finger, and only letting go settles it.
            val lyricsIn = remember { Animatable(0f) }
            // Set while the finger has them: the toggle's animation keeps away
            // until the gesture has put the value where it belongs.
            val lyricsDrag = remember { booleanArrayOf(false) }
            // 0 undecided, 1 the lyrics, 2 the player: what a vertical drag on
            // the cover turned out to be, decided by the first move.
            val lift = remember { intArrayOf(0) }
            LaunchedEffect(showLyrics, settled) {
                if (lyricsDrag[0]) return@LaunchedEffect
                lyricsIn.animateTo(if (showLyrics && settled) 1f else 0f, Motion.reveal())
                // A drag of theirs that ended in something else (the player
                // closing) leaves them on screen at nothing: take them off.
                if (!showLyrics) lyricsPeek = false
            }
            // 0 to 1 into full-screen lyrics.
            val fullIn = animateFloatAsState(if (showLyrics && lyricsFull && settled) 1f else 0f, Motion.settle(169f), label = "full")
            // 0 to 180 degrees as the cover turns over; past 90 the back shows.
            val turned = flipped && settled && !showLyrics
            val flip = animateFloatAsState(if (turned) 180f else 0f, Motion.turn(), label = "flip")
            val backSide by remember { derivedStateOf { flip.value > 90f } }
            if (turned) BackGesture { flipped = false }

            fun GraphicsLayerScope.artMove(extra: Float) {
                val p = pf()
                val k = lerp(miniArt / artFullPx, 1f, p)
                transformOrigin = TransformOrigin(0f, 0f)
                val sc = k * extra
                val shift = artFullPx * k * (1f - extra) / 2f
                translationX = lerp(miniX, fullXPx, p) - fullXPx + shift + throwX.value
                translationY = lerp(miniY, fullYPx, p) - fullYPx + shift
                scaleX = sc
                scaleY = sc
            }

            Box(
                Modifier.offset(fullX, fullY).size(artFull)
                    .graphicsLayer {
                        val p = pf()
                        artMove(lerp(1f, breath.value, p) * (1f - kotlin.math.abs(throwX.value) / fullW * .15f))
                        rotationZ = throwX.value / fullW * 14f
                        alpha = 1f - fullIn.value
                    }
                    // Its own layer so the turn pivots on the centre, not the corner the move scales from.
                    .graphicsLayer {
                        val p = pf()
                        val k = lerp(miniArt / artFullPx, 1f, p)
                        rotationY = flip.value
                        cameraDistance = 14f * density.density
                        // Corners and shadow compensate the scale: 12 dp mini, 28 dp full.
                        shape = RoundedCornerShape((lerp(12f, 28f, p) * density.density / k.coerceAtLeast(.05f)).coerceAtLeast(0f))
                        clip = true
                        shadowElevation = lerp(0f, 24f, p) * density.density
                    }
                    .then(
                        if (throwable) {
                            Modifier.pointerInput(track.uri) {
                                // An episode has no album to turn to.
                                detectTapGestures(onTap = if (track.uri.startsWith("spotify:episode:")) null else { _ ->
                                    haptics.tick()
                                    flipped = true
                                }, onDoubleTap = { o ->
                                    val dir = if (o.x < size.width / 2) -1 else 1
                                    controls.seek((position + dir * 10_000).coerceIn(0, duration))
                                    tapHint = System.nanoTime() to dir
                                })
                            }
                        } else {
                            Modifier
                        },
                    )
                    .draggable(
                        rememberDraggableState { d ->
                            // Nothing comes next: the cover gives way less and less, like
                            // a rubber band, instead of acting as if a song would follow.
                            val step = if (!hasNext && throwX.value + d < 0f) {
                                d * .35f / (1f + kotlin.math.abs(throwX.value) / (fullW * .12f))
                            } else {
                                d
                            }
                            scope.snapNow(throwX, throwX.value + step)
                        },
                        Orientation.Horizontal,
                        enabled = throwable,
                        // In the screen's own scope, not the drag's: a throw that ends
                        // while the player closes still brings the cover back.
                        onDragStopped = { v ->
                            scope.launch {
                                val go = when {
                                    throwX.value < -fullW / 3 || v < -1800f -> 1
                                    throwX.value > fullW / 3 || v > 1800f -> -1
                                    else -> 0
                                }
                                if (!hasNext && throwX.value < 0f) {
                                    // A short, bouncy "no" on the way back.
                                    if (go == 1 || throwX.value < -fullW * .06f) haptics.tick()
                                    throwX.animateTo(0f, Motion.refuse(), initialVelocity = v * .3f)
                                    return@launch
                                }
                                if (go != 0 && (go == -1 || hasNext)) {
                                    haptics.press()
                                    // Skip first: the next song loads while the cover flies out.
                                    val before = currentUri
                                    thrown[0] = true
                                    if (go == 1) controls.next() else controls.previous()
                                    throwX.animateTo(-go * fullW * 1.2f, Motion.exit(170))
                                    // Come back in with the new song's cover, not the old one
                                    // (another device confirms a skip in a second or more).
                                    withTimeoutOrNull(if (s.remote != null) 2500L else 700L) { snapshotFlow { currentUri }.first { it != before } }
                                        ?: run { thrown[0] = false }
                                    throwX.snapTo(go * fullW * .8f)
                                }
                                throwX.animateTo(0f, Motion.settle(520f))
                            }
                        },
                    )
                    // Pulled up, the cover hands the song over to its lyrics,
                    // which come in with the finger; pulled down, they go back.
                    // With no lyrics in, a pull down is what it has always been:
                    // the player collapsing, so that drag goes on to the sheet.
                    .draggable(
                        rememberDraggableState { d ->
                            if (lift[0] == 0 && d != 0f) {
                                lift[0] = if (settled && (if (showLyrics) d > 0f else d < 0f)) 1 else 2
                                if (lift[0] == 1) {
                                    lyricsDrag[0] = true
                                    lyricsPeek = true
                                }
                            }
                            if (lift[0] == 1) {
                                scope.snapNow(lyricsIn, (lyricsIn.value - d / artFullPx).coerceIn(0f, 1f))
                            } else {
                                dragState.dispatchRawDelta(d)
                            }
                        },
                        Orientation.Vertical,
                        // Not tied to the player being open: a drag that collapses
                        // it would be cancelled halfway, and let go of midway.
                        enabled = !flipped && !lyricsFull,
                        onDragStarted = {
                            lift[0] = 0
                            startSheetDrag()
                        },
                        // In the screen's own scope: whatever the drag decided
                        // still lands even if this gesture's own scope ends with it.
                        onDragStopped = { v ->
                            scope.launch {
                                if (lift[0] != 1) {
                                    settleSheet(v)
                                    return@launch
                                }
                                // A third of the cover, or a flick, is enough either way.
                                val open = when {
                                    v < -Motion.FlingVelocity / 2 -> true
                                    v > Motion.FlingVelocity / 2 -> false
                                    else -> lyricsIn.value > .35f
                                }
                                if (open != showLyrics) {
                                    haptics.press()
                                    showLyrics = open
                                    library.lyricsOpen = open
                                    if (open) flipped = false else lyricsFull = false
                                }
                                // Cleared before settling: the effect above may
                                // animate the same landing, never a stale one.
                                lyricsDrag[0] = false
                                lyricsIn.animateTo(if (open) 1f else 0f, Motion.reveal(), initialVelocity = -v / artFullPx)
                                lyricsPeek = false
                            }
                        },
                    )
                    .background(c.outline),
                contentAlignment = Alignment.Center,
            ) {
                if (backSide) {
                    // Counter-turned so the back reads the right way round.
                    AlbumBack(track, controls, Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
                        haptics.tick()
                        flipped = false
                    }
                } else if (!track.coverUrl.isNullOrBlank()) {
                    // Cross-fade covers: the old one stays until the new one is in.
                    AnimatedContent(
                        track.coverUrl,
                        Modifier.fillMaxSize().graphicsLayer {
                            // The blur leads and the veil follows: the art goes
                            // soft before it goes dark, so the two read as depth
                            // rather than as one curtain coming down.
                            val r = lyricsIn.value.coerceIn(0f, 1f).pow(.7f) * 38f
                            renderEffect = if (r > .5f && blurEffectSupported) BlurEffect(r, r, TileMode.Clamp) else null
                        },
                        // A thrown cover comes back already showing the new art; other changes cross-fade.
                        transitionSpec = {
                            if (thrown[0]) {
                                thrown[0] = false
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                fadeIn(tween(Motion.scaled(420))) togetherWith fadeOut(tween(Motion.scaled(420)))
                            }
                        },
                        label = "cover",
                    ) { url ->
                        // No slow drift on the player's cover: it turned on and
                        // off with no way back, so the art jumped as the lyrics
                        // came in and again as they left.
                        AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                } else {
                    Icon(Icons.Rounded.MusicNote, null, tint = c.onSurfaceVariant, modifier = Modifier.fillMaxSize(.4f))
                }
                // Darkens the blurred cover so the lyrics read on any art.
                // The dark falls down the cover like a curtain, ahead of the
                // words rising to meet it, instead of the whole square dimming
                // at once. It reads on bright art and on dark art alike, which
                // a light sweep over the lyrics did not.
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        val p = lyricsIn.value.coerceIn(0f, 1f)
                        if (p <= 0f) return@drawBehind
                        val veil = wash.copy(alpha = veilAt * (.35f + .65f * p))
                        val edge = (p * 1.6f).coerceIn(.001f, 1f)
                        val solid = (edge - .55f).coerceIn(0f, edge - .001f)
                        drawRect(
                            if (edge >= 1f) {
                                Brush.verticalGradient(0f to veil, solid to veil, 1f to veil)
                            } else {
                                Brush.verticalGradient(0f to veil, solid to veil, edge to Color.Transparent, 1f to Color.Transparent)
                            },
                        )
                    },
                )
                tapHint?.let { (id, dir) -> SeekRipple(id, dir) }
            }

            // Mini player row (fades out while expanding). Only its band opens the player.
            if (showMini) {
                Row(
                    Modifier.fillMaxWidth().height(68.dp)
                        .clickable(enabled = collapsed, indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            scope.launch { f.animateTo(1f, Motion.enter(Motion.Medium)) }
                        }
                        .graphicsLayer { alpha = (1f - pf() / .6f).coerceIn(0f, 1f) }
                        .padding(start = 86.dp, end = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        AnimatedContent(track, transitionSpec = { songChange() }, label = "mini") { t ->
                            Column {
                                Text(t.title, color = c.onSurface, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val d = s.remote
                                if (d == null) {
                                    Text(t.artists, color = c.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else {
                                    // Playing elsewhere: where it plays takes the artist's line.
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(deviceIcon(d.kind), null, tint = accentColor(), modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(d.name, color = accentColor(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                    PlayButton(s, controls, 44.dp, c.primary, c.onPrimary)
                    if (hasNext) {
                        IconButton(controls::next) { Icon(Icons.Rounded.SkipNext, "Próxima", tint = c.onSurface) }
                    }
                }
            }

            // Full player content (appears while expanding).
            val rise = with(density) { 40.dp.toPx() }
            if (showFull) {
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding()
                        .graphicsLayer {
                            val p = pf()
                            alpha = ((p - .4f) / .6f).coerceIn(0f, 1f)
                            translationY = lerp(rise, 0f, p)
                            // Pulled down, the controls recede a touch with the card.
                            val sc = lerp(.94f, 1f, p)
                            scaleX = sc
                            scaleY = sc
                            transformOrigin = TransformOrigin(.5f, 0f)
                        }
                        .padding(horizontal = 28.dp),
                ) {
                    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ scope.launch { f.animateTo(0f, settle) } }) {
                            Icon(Icons.Rounded.KeyboardArrowDown, "Recolher", tint = ink)
                        }
                        // One button a side keeps the handle centred; devices moved to the row below.
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(width = 36.dp, height = 5.dp).clip(CircleShape).background(ink.copy(.35f)))
                        Spacer(Modifier.weight(1f))
                        IconButton({ showActions = true }) {
                            Icon(Icons.Rounded.MoreVert, "Mais opções", tint = ink)
                        }
                    }
                    // Lyrics sit inside the cover's square, over the blurred art.
                    Box(Modifier.fillMaxWidth().height(artSpace), contentAlignment = Alignment.Center) {
                        if (lyricsShown) {
                            Box(
                                Modifier.size(artFull)
                                    .graphicsLayer {
                                        // They rise into place, grow the last of the
                                        // way, and come into focus as they land: the
                                        // words resolve out of the blur the cover is
                                        // sinking into, instead of fading in sharp.
                                        val p = lyricsIn.value.coerceIn(0f, 1f)
                                        // A step behind the cover going soft: the
                                        // ground is already quiet when the words
                                        // land on it, never both at once in a blur.
                                        val w = ((p - .12f) / .88f).coerceIn(0f, 1f)
                                        alpha = (w * 1.35f).coerceAtMost(1f) * (1f - fullIn.value)
                                        translationY = (1f - w) * artFullPx * .07f
                                        // Barely a growth: enough to feel it settle,
                                        // not enough to read as the cover zooming.
                                        val sc = .975f + .025f * lyricsIn.value
                                        scaleX = sc
                                        scaleY = sc
                                        val soft = (1f - w) * 7f
                                        renderEffect =
                                            if (soft > .5f && blurEffectSupported) BlurEffect(soft, soft, TileMode.Decal) else null
                                    },
                            ) {
                                // Hidden under full-screen lyrics: its sweep holds still.
                                LyricsView(
                                    lyrics, position, controls,
                                    Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
                                    playback = s.takeUnless { lyricsFull }, translated = meaning,
                                    // The lines land one after the other, out from the
                                    // one playing, as the lyrics come in.
                                    enter = { lyricsIn.value },
                                )
                                Row(Modifier.align(Alignment.TopEnd)) {
                                    TranslateButton(translation, translating) { translating = !translating }
                                    IconButton({ lyricsFull = true }) {
                                        Icon(Icons.Rounded.OpenInFull, "Letra em tela cheia", tint = ink.copy(.8f), modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                    Column(
                        Modifier.graphicsLayer { alpha = 1f - fullIn.value }.wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged {
                            val h = with(density) { it.height.toDp() }
                            if (kotlin.math.abs((h - controlsH).value) > 1f) controlsH = h
                        },
                    ) {
                        // The heart sits on the artist's line, however long the title runs.
                        Row(verticalAlignment = Alignment.Bottom) {
                            AnimatedContent(track, Modifier.weight(1f), transitionSpec = { songChange() }, label = "title") { t ->
                                Column {
                                    Text(
                                        t.title, color = ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp,
                                        letterSpacing = (-.5f).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        t.artists, color = ink.copy(.62f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            LikeButton(track.uri)
                        }
                        Spacer(Modifier.height(18.dp))
                        var drag by remember { mutableStateOf<Float?>(null) }
                        // The bar is redrawn with the frame clock, so it glides
                        // instead of stepping with the four reads a second the
                        // times below are happy with.
                        val tick = ambientClock()
                        ThinSlider(
                            {
                                drag ?: run {
                                    tick.value
                                    s.currentPositionMs().toFloat() / duration
                                }
                            },
                            { drag = it },
                            {
                                drag?.let { controls.seek((it * duration).toLong()) }
                                drag = null
                            },
                            waiting = { tick.value; s.loading },
                            lit = s.playing && !s.loading,
                        )
                        val shown = drag?.let { (it * duration).toLong() } ?: position
                        Row(Modifier.padding(top = 2.dp)) {
                            val time = MaterialTheme.typography.labelMedium
                            // Dragging: the time being aimed at steps forward in the song's colour.
                            val seeking = drag != null
                            val lift by animateFloatAsState(if (seeking) 1f else 0f, Motion.settle(300f), label = "seeking")
                            Text(
                                fmt(shown),
                                Modifier.graphicsLayer {
                                    val s = 1f + .18f * lift
                                    scaleX = s
                                    scaleY = s
                                    transformOrigin = TransformOrigin(0f, .5f)
                                },
                                color = androidx.compose.ui.graphics.lerp(ink.copy(.66f), accentColor(), lift),
                                style = time,
                            )
                            Spacer(Modifier.weight(1f))
                            Text("-" + fmt(duration - shown), color = ink.copy(.66f), style = time)
                        }
                        s.remote?.let { d ->
                            // Playing on another device: say where, and tap to change it.
                            Row(
                                Modifier.padding(top = 10.dp).clip(CircleShape).clickable { controls.openDevices() }
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(deviceIcon(d.kind), null, tint = accentColor(), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Tocando em ${d.name}", color = accentColor(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        CompositionLocalProvider(LocalHaze provides playerGlass) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SideButton(Icons.Rounded.SkipPrevious, "Anterior", Modifier.weight(1f), true, -1, controls::previous)
                                PlayButton(s, controls, 84.dp, ink, ink, Modifier.weight(2f), pill = true, glass = true)
                                SideButton(Icons.Rounded.SkipNext, "Próxima", Modifier.weight(1f), hasNext, 1, controls::next)
                            }
                            Spacer(Modifier.height(18.dp))
                            // Evenly spread: how it plays (shuffle, repeat), what goes with it
                            // (lyrics, queue), where it sounds (equalizer, devices).
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                ToggleIcon(Icons.Rounded.Shuffle, "Aleatório", s.queue.shuffle, ToggleMotion.Wiggle) { controls.setShuffle(!s.queue.shuffle) }
                                ToggleIcon(
                                    if (s.queue.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                    when (s.queue.repeat) {
                                        RepeatMode.ONE -> "Repetir faixa"
                                        RepeatMode.ALL -> "Repetir fila"
                                        RepeatMode.OFF -> "Repetição desligada"
                                    },
                                    s.queue.repeat != RepeatMode.OFF,
                                    ToggleMotion.Spin,
                                ) {
                                    controls.setRepeat(
                                        when (s.queue.repeat) {
                                            RepeatMode.OFF -> RepeatMode.ALL
                                            RepeatMode.ALL -> RepeatMode.ONE
                                            RepeatMode.ONE -> RepeatMode.OFF
                                        },
                                    )
                                }
                                ToggleIcon(Icons.Rounded.Lyrics, "Letra", showLyrics) {
                                    showLyrics = !showLyrics
                                    library.lyricsOpen = showLyrics
                                    if (showLyrics) flipped = false
                                    if (!showLyrics) lyricsFull = false
                                }
                                ToggleIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Fila", false) { showQueue = true }
                                ToggleIcon(Icons.Rounded.GraphicEq, "Equalizador", false) {
                                    scope.launch { f.animateTo(0f, settle) }
                                    controls.openEqualizer()
                                }
                                ToggleIcon(Icons.Rounded.Devices, "Dispositivos", s.remote != null) { controls.openDevices() }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }

            // Full-screen lyrics: the cover and controls fade away; the song and
            // play/pause stay at the bottom.
            if (showFull && showLyrics && lyricsFull) {
                BackGesture { lyricsFull = false }
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding().padding(top = 56.dp)
                        .graphicsLayer { alpha = fullIn.value }
                        // Taps between lines must not reach the faded controls underneath.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(horizontal = 28.dp),
                ) {
                    // Translating and leaving sit up top, out of the way of the
                    // thumb; the song's own controls stay at the bottom.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TranslateButton(translation, translating) { translating = !translating }
                        IconButton({ lyricsFull = false }) {
                            Icon(Icons.Rounded.CloseFullscreen, "Sair da tela cheia", tint = ink)
                        }
                    }
                    LyricsView(lyrics, position, controls, Modifier.weight(1f).fillMaxWidth(), large = true, playback = s, translated = meaning)
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(track.title, color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artists, color = ink.copy(.62f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        LyricsSkip(Icons.Rounded.SkipPrevious, "Anterior", true, -1, controls::previous)
                        PlayButton(s, controls, 52.dp, ink, wash)
                        LyricsSkip(Icons.Rounded.SkipNext, "Próxima", hasNext, 1, controls::next)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlayButton(
    s: PlaybackSnapshot,
    controls: PlayerControls,
    size: Dp,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    pill: Boolean = false,
    /** Strong liquid glass over the player's backdrop instead of a solid [bg]. */
    glass: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    // The glass is lit for the theme, not always for a dark one.
    val dark = MaterialTheme.colorScheme.isDark
    val haptics = rememberHaptics()
    val wave = rememberKick(520)
    Box(
        modifier.then(if (pill) Modifier.height(size) else Modifier.size(size)).refusalShake().springPress(source)
            .pressWave(wave, if (glass) fg else bg)
            .then(if (glass) Modifier.liquidGlass(CircleShape, dark, GlassLevel.Strong) else Modifier.clip(CircleShape).background(bg))
            .clickable(source, null) {
                haptics.press()
                wave.play()
                controls.playPause()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (s.loading) {
            CircularProgressIndicator(Modifier.size(size * .4f), color = fg, strokeWidth = 2.dp)
        } else {
            // One shape that folds between pause and play.
            val label = if (s.playing) "Pausar" else "Tocar"
            PlayPauseGlyph(s.playing, fg, Modifier.size(size * .4f).semantics { contentDescription = label })
        }
    }
}

@Composable
internal fun SideButton(icon: ImageVector, description: String, modifier: Modifier, enabled: Boolean, dir: Int, onClick: () -> Unit) {
    val ink = artInk()
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    // The arrow pushes the way of the skip and leaves a fading trace behind it.
    val nudge = rememberKick(380)
    Box(
        modifier.height(84.dp).alpha(if (enabled) 1f else .35f).refusalShake().springPress(source)
            .liquidGlass(CircleShape, MaterialTheme.colorScheme.isDark, GlassLevel.Regular)
            .clickable(source, null, enabled = enabled) {
                haptics.press()
                nudge.play()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (nudge.running) {
            Icon(
                icon, null, tint = ink,
                modifier = Modifier.size(32.dp).graphicsLayer {
                    val k = nudge.value.value
                    alpha = .45f * (1f - k)
                    translationX = dir * (6.dp.toPx() + 18.dp.toPx() * k)
                },
            )
        }
        Icon(
            icon, description, tint = ink,
            modifier = Modifier.size(32.dp).graphicsLayer { translationX = dir * 7.dp.toPx() * nudge.bump() },
        )
    }
}

/** How a row button answers a tap, beyond filling in. */
internal enum class ToggleMotion { Bounce, Spin, Wiggle }

@Composable
internal fun ToggleIcon(icon: ImageVector, description: String, on: Boolean, motion: ToggleMotion = ToggleMotion.Bounce, onClick: () -> Unit) {
    val ink = artInk()
    val wash = artWash()
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val kick = rememberKick(if (motion == ToggleMotion.Wiggle) 420 else 340)
    // Repeat turns half a circle on every tap, landing on its next mode.
    var turns by remember { mutableFloatStateOf(0f) }
    val turn by animateFloatAsState(turns, Motion.settle(300f), label = "turn")
    // On: a solid white disc that fills in; off: just the icon, so six of them
    // read as one quiet row (and draw no glass).
    val fill by animateFloatAsState(if (on) 1f else 0f, Motion.settle(400f), label = "toggle")
    Box(
        Modifier.size(46.dp).springPress(source)
            .drawBehind { if (fill > 0f) drawCircle(ink.copy(.92f * fill), size.minDimension / 2 * (.6f + .26f * fill)) }
            .clickable(source, null) {
                haptics.tick()
                // Shuffle's arrows cross only as it turns on.
                if (motion != ToggleMotion.Wiggle || !on) kick.play()
                if (motion == ToggleMotion.Spin && !Motion.reduced) turns += 180f
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, description, tint = androidx.compose.ui.graphics.lerp(ink.copy(.78f), wash, fill),
            modifier = Modifier.size(22.dp).graphicsLayer {
                val b = kick.bump()
                when (motion) {
                    ToggleMotion.Bounce -> {
                        scaleX = 1f + .2f * b
                        scaleY = 1f + .2f * b
                    }
                    ToggleMotion.Spin -> {
                        rotationZ = turn
                        scaleX = 1f + .1f * b
                        scaleY = 1f + .1f * b
                    }
                    ToggleMotion.Wiggle -> {
                        val k = kick.value.value
                        rotationZ = if (kick.running) 16f * kotlin.math.sin(k * 2f * kotlin.math.PI.toFloat()) * (1f - k) else 0f
                        scaleX = 1f + .12f * b
                        scaleY = 1f + .12f * b
                    }
                }
            },
        )
    }
}

/** The song's heart, beside its title: fills and pops when liked. */
@Composable
internal fun LikeButton(uri: String) {
    val ink = artInk()
    val edits = dev.nexfy.app.LocalServices.current.edits
    val haptics = rememberHaptics()
    LaunchedEffect(uri) { edits.load(listOf(uri)) }
    val liked = edits.saved[uri] == true
    val pop = remember { Animatable(1f) }
    val burst = rememberKick(480)
    val accent = accentColor()
    val scope = rememberCoroutineScope()
    IconButton({
        haptics.press()
        edits.setSaved(uri, !liked, if (liked) "Removida das curtidas" else "Adicionada às curtidas")
        if (!liked) {
            burst.play()
            if (!Motion.reduced) scope.launch {
                pop.snapTo(.7f)
                pop.animateTo(1f, Motion.settle(300f))
            }
        }
    }, Modifier.likeBurst(burst, accent)) {
        Icon(
            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (liked) "Descurtir" else "Curtir",
            tint = if (liked) accentColor() else ink.copy(.8f),
            modifier = Modifier.size(28.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
        )
    }
}

/** A wash from the tapped side with "−10 s" or "+10 s". */
@Composable
private fun BoxScope.SeekRipple(id: Long, dir: Int) {
    val ink = artInk()
    val v = remember(id) { Animatable(0f) }
    LaunchedEffect(id) { v.animateTo(1f, Motion.enter(Motion.ExtraLong)) }
    if (v.value >= 1f) return
    Box(
        Modifier.align(if (dir < 0) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.5f)
            .graphicsLayer { alpha = 1f - v.value }
            .background(
                Brush.horizontalGradient(
                    if (dir < 0) listOf(ink.copy(.35f), Color.Transparent) else listOf(Color.Transparent, ink.copy(.35f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (dir < 0) "−10 s" else "+10 s",
            color = ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.graphicsLayer {
                val sc = .8f + v.value * .4f
                scaleX = sc
                scaleY = sc
                translationX = dir * v.value * 40f
            },
        )
    }
}

/**
 * Spotify's queue: the song playing, the songs the user queued ("Na
 * fila") and what follows from the playlist or album ("A seguir").
 * Tapping a row jumps to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(s: PlaybackSnapshot, controls: PlayerControls, onDismiss: () -> Unit) {
    if (s.remote != null) return RemoteQueueSheet(s, controls, onDismiss)
    val c = MaterialTheme.colorScheme
    val next = s.queue.next
    val uris = remember(next) { next.map { it.uri } }
    var info by remember { mutableStateOf<Map<String, Track>>(emptyMap()) }
    LaunchedEffect(uris) {
        // Only what is missing: rows already shown keep their details as the queue moves.
        val missing = uris.filterNot { it in info }
        if (missing.isEmpty()) return@LaunchedEffect
        try {
            info = info + controls.tracksInfo(missing).associateBy { it.uri }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    val queued = next.withIndex().filter { it.value.queued }
    val upcoming = next.withIndex().filter { !it.value.queued }

    // Who added each song: a Jam guest as Spotify reports, else who added it
    // to the playlist playing (the playlist page knows). Faces show only when
    // more than one person added, or someone other than this account, and
    // only with "Mostrar quem adicionou" on.
    val core = dev.nexfy.app.LocalServices.current.core
    val playlistId = s.queue.contextUri?.takeIf { it.startsWith("spotify:playlist:") }?.substringAfterLast(':')
    val fromPlaylist by produceState<Map<String, String>>(emptyMap(), playlistId) {
        value = playlistId?.let { id ->
            runCatching { core.playlist(id, false) }.getOrNull()?.tracks
                ?.mapNotNull { t -> t.addedBy?.let { t.uri to it } }?.toMap()
        }.orEmpty()
    }
    val adders = remember(next, fromPlaylist) { next.associate { it.uri to (it.addedBy ?: fromPlaylist[it.uri]) } }
    val me = remember { core.username() }
    val distinct = remember(adders) { adders.values.filterNotNull().toSet() }
    val showFaces = dev.nexfy.app.ui.theme.LocalLook.current.showAddedBy && (distinct.size > 1 || distinct.any { it != me })
    val faces by produceState<Map<String, UserFace>>(emptyMap(), distinct, showFaces) {
        if (showFaces) value = runCatching { core.users(distinct.toList()) }.getOrDefault(emptyList()).associateBy { it.id }
    }
    fun faceOf(entry: QueueEntry) = if (showFaces) adders[entry.uri]?.let(faces::get) else null

    GlassSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
            s.track?.let { track ->
                item(key = "now") {
                    QueueHeading("Tocando agora")
                    QueueRow(track.coverUrl, track.title, track.artists, highlight = true) {}
                }
            }
            if (queued.isNotEmpty()) item(key = "h-queued") { QueueHeading("Na fila") }
            items(queued, key = { "q${it.index}:${it.value.uri}" }) { (i, entry) ->
                QueueEntryRow(info[entry.uri], i, faceOf(entry)) {
                    controls.skipTo(entry, i)
                    onDismiss()
                }
            }
            if (upcoming.isNotEmpty()) item(key = "h-next") { QueueHeading(if (queued.isEmpty()) "A seguir" else "Depois, da lista") }
            items(upcoming, key = { "n${it.index}:${it.value.uri}" }) { (i, entry) ->
                QueueEntryRow(info[entry.uri], i, faceOf(entry)) {
                    controls.skipTo(entry, i)
                    onDismiss()
                }
            }
            if (next.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nada na fila depois desta música.",
                        color = c.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
        }
    }
}

/** Most songs ahead skipped to by sending "next" repeatedly to another device. */
private const val REMOTE_SKIP_MAX = 5

/**
 * The queue of the device playing elsewhere, read from Spotify each time
 * the song changes. Near songs can be jumped to; the Web API has no
 * direct jump, so it sends "next" that many times.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteQueueSheet(s: PlaybackSnapshot, controls: PlayerControls, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var next by remember { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(s.track?.uri) {
        next = try {
            controls.remoteQueue()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    // Who queued each song (Jam guests), as the device playing reports it.
    val core = dev.nexfy.app.LocalServices.current.core
    val adders = remember(next) { next.orEmpty().mapNotNull { it.addedBy }.toSet() }
    val showFaces = dev.nexfy.app.ui.theme.LocalLook.current.showAddedBy && adders.isNotEmpty()
    val faces by produceState<Map<String, UserFace>>(emptyMap(), adders, showFaces) {
        if (showFaces) value = runCatching { core.users(adders.toList()) }.getOrDefault(emptyList()).associateBy { it.id }
    }
    GlassSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
            s.track?.let { track ->
                item(key = "now") {
                    QueueHeading("Tocando agora em ${s.remote?.name.orEmpty()}")
                    QueueRow(track.coverUrl, track.title, track.artists, highlight = true) {}
                }
            }
            val list = next
            when {
                list == null -> items(5, key = { "sk$it" }) { QueueEntryRow(null, it) {} }
                list.isEmpty() -> item(key = "empty") {
                    Text(
                        "Nada na fila depois desta música.",
                        color = c.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                else -> {
                    item(key = "h-next") { QueueHeading("A seguir") }
                    itemsIndexed(list, key = { i, t -> "r$i:${t.uri}" }) { i, t ->
                        QueueEntryRow(t, i, if (showFaces) t.addedBy?.let(faces::get) else null) {
                            if (i < REMOTE_SKIP_MAX) {
                                repeat(i + 1) { controls.next() }
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A queue row, or its skeleton until the track's details arrive; one fades into the other. */
@Composable
private fun QueueEntryRow(track: Track?, seed: Int, face: UserFace? = null, onClick: () -> Unit) {
    Crossfade(track, animationSpec = tween(Motion.Short), label = "queue-row") { t ->
        if (t == null) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Bone(Modifier.size(48.dp), RoundedCornerShape(10.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    TextBone(boneWidth(seed), 24.dp, 14.dp)
                    TextBone(boneWidth(seed, 1) * .7f, 24.dp, 11.dp)
                }
            }
        } else {
            QueueRow(t.imageUrl, t.title, t.artists, face = face, onClick = onClick)
        }
    }
}

@Composable
private fun QueueHeading(text: String) =
    Text(text, Modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)

@Composable
private fun QueueRow(
    imageUrl: String?,
    title: String,
    subtitle: String,
    highlight: Boolean = false,
    /** Who added it, when that is worth showing (a shared playlist, a Jam). */
    face: UserFace? = null,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(imageUrl, Modifier.size(48.dp), 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                color = if (highlight) accentColor() else c.onSurface,
            )
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = c.onSurfaceVariant)
        }
        if (face != null) {
            Spacer(Modifier.width(10.dp))
            // Small and quiet: who added it is a hint, not the row's subject.
            dev.nexfy.app.ui.design.UserAvatar(face, Modifier.padding(end = 4.dp).size(20.dp).alpha(.8f))
        }
    }
}

sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data object Failed : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
}

/**
 * Scrolling lyrics: the current line is large and sharp and stays near the
 * top third; the others shrink, fade and blur with their distance. Tapping
 * a timed line seeks to it. A current line with word timings lights up word
 * by word, following [playback] frame by frame (null holds it at [position]).
 */
@Composable
internal fun LyricsView(
    state: LyricsState,
    position: Long,
    controls: PlayerControls,
    modifier: Modifier,
    large: Boolean = false,
    playback: PlaybackSnapshot? = null,
    /** The same lines in another language, under each; null while off. */
    translated: List<String>? = null,
    /**
     * How far in the lyrics are (0 to 1), read while drawing: the lines
     * arrive out from the one playing. Null for lyrics that are simply there.
     */
    enter: (() -> Float)? = null,
) {
    val ink = artInk()
    if (state == LyricsState.Loading) {
        // Lines the size of the real ones, so the lyrics fade in where they will sit.
        Column(modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(6) { i ->
                Bone(Modifier.fillMaxWidth(boneWidth(i)).height(24.dp), RoundedCornerShape(8.dp), light = true)
            }
        }
        return
    }
    val found = state as? LyricsState.Found
    if (found == null || found.lyrics.instrumental || found.lyrics.lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                when {
                    found?.lyrics?.instrumental == true -> "Instrumental"
                    state == LyricsState.Failed -> "Não deu para buscar a letra agora"
                    else -> "Letra não encontrada"
                },
                color = ink.copy(.6f),
                fontSize = 16.sp,
            )
        }
        return
    }
    val lyrics = found.lyrics
    val lines = lyrics.lines
    val current = if (lyrics.synced) {
        lines.indexOfLast { (it.atMs?.toLong() ?: Long.MAX_VALUE) <= position + 250 }
    } else {
        -1
    }
    // Song time for the word sweep, written each frame but read only while
    // drawing the current line: frames redraw one line, never recompose.
    val clock = remember { mutableLongStateOf(position) }
    val timed = current >= 0 && lines[current].words.isNotEmpty()
    LaunchedEffect(timed, playback) {
        val p = playback ?: return@LaunchedEffect
        if (!timed) return@LaunchedEffect
        do {
            withFrameMillis { clock.longValue = p.currentPositionMs() }
        } while (p.playing)
    }
    val list = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) list.animateScrollToItem(current)
    }
    // "Tamanho da letra": scales both the square and the full-screen sizes.
    val scale = dev.nexfy.app.ui.theme.LocalLook.current.lyricsScale
    Column(modifier) {
        BoxWithConstraints(Modifier.weight(1f)) {
            // The padding puts the scrolled-to line a third of the way down,
            // and leaves room for the blur and scale to spread without clipping.
            val top = maxHeight * .3f
            LazyColumn(
                Modifier.fillMaxSize()
                    // Lines fade out at the edges instead of being cut.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(0f to Color.Transparent, .12f to Color.Black, .8f to Color.Black, 1f to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    },
                state = list,
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = top, bottom = maxHeight - top),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(lines.size) { i ->
                    val line = lines[i]
                    // Distance from the current line, animated so blur, fade and
                    // size glide as the song moves on.
                    val target = when {
                        !lyrics.synced -> 0f
                        // Before the first line: it waits nearly lit, the rest behind it.
                        current < 0 -> (i + .35f).coerceAtMost(4f)
                        else -> kotlin.math.abs(i - current).coerceAtMost(4).toFloat()
                    }
                    val d by animateFloatAsState(target, Motion.settle(169f), label = "distance")
                    val focus = (1f - d).coerceIn(0f, 1f)
                    // Only the far lines soften: next to the one playing, the
                    // words stay sharp and the fading alone sets them back.
                    val blur = if (blurEffectSupported && d > 1.2f) ((d - 1.2f) * .9f).dp else 0.dp
                    val sweep = if (i == current) remember(line) { wordSweep(line, lines.getOrNull(i + 1)?.atMs?.toLong()) } else null
                    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
                    // The line and its translation fade, blur and scale as one.
                    Column(
                        Modifier.fillMaxWidth()
                            .graphicsLayer {
                                // While they come in, each line waits on the one
                                // nearer the song, then eases the last of the way
                                // up: the lyric arrives as a wave out from the
                                // words being sung, not as a block.
                                val wave = enter?.let { (((it() - d * .08f)) * 2.2f).coerceIn(0f, 1f) } ?: 1f
                                val k = 1f - (1f - wave).pow(3)
                                val sc = (.88f + .12f * focus) * (.96f + .04f * k)
                                scaleX = sc
                                scaleY = sc
                                alpha = (1f - .2f * d).coerceIn(.3f, 1f) * (.55f + .45f * focus) * k
                                translationY = (1f - k) * 30.dp.toPx()
                                transformOrigin = TransformOrigin(0f, .5f)
                            }
                            .then(if (blur > 0.dp) Modifier.blur(blur, BlurredEdgeTreatment.Unbounded) else Modifier)
                            .clickable(
                                enabled = line.atMs != null,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { line.atMs?.let { controls.seek(it.toLong()) } },
                    ) {
                        Text(
                            line.text,
                            Modifier.fillMaxWidth().then(
                                if (sweep == null) {
                                    Modifier
                                } else {
                                    Modifier.drawWithContent {
                                        val text = layout[0] ?: return@drawWithContent drawContent()
                                        // A hair early: the eye leads the ear.
                                        val t = (if (playback != null) clock.longValue else position) + 50
                                        // Unsung words settle dim as the line comes into focus.
                                        drawSweep(text, sweep.reach(t), 16.dp.toPx(), ink, ink.copy(alpha = 1f - .62f * focus))
                                    }
                                },
                            ),
                            onTextLayout = { layout[0] = it },
                            color = ink,
                            fontSize = (scale * when { large && lyrics.synced -> 30; large -> 22; lyrics.synced -> 24; else -> 19 }).sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = (scale * when { large && lyrics.synced -> 36; large -> 28; lyrics.synced -> 30; else -> 25 }).sp,
                        )
                        translated?.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { meaning ->
                            Text(
                                meaning,
                                Modifier.fillMaxWidth().padding(top = 3.dp),
                                color = ink.copy(.58f),
                                fontSize = (scale * if (large) 17 else 15).sp,
                                lineHeight = (scale * if (large) 22 else 19).sp,
                            )
                        }
                    }
                }
            }
        }
        Text(
            if (translated == null) "Letra: ${lyrics.source}" else "Letra: ${lyrics.source} · tradução: MyMemory",
            color = ink.copy(.4f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Where each timed piece of a line starts in its text, and when it is sung. */
private class WordSweep(private val starts: IntArray, private val at: LongArray, private val end: LongArray, private val length: Int) {
    /** How far the singing has reached at [t], in characters; fractional inside a word. */
    fun reach(t: Long): Float {
        if (t < at[0]) return 0f
        var k = at.size - 1
        while (k > 0 && at[k] > t) k--
        val size = (if (k + 1 < starts.size) starts[k + 1] else length) - starts[k]
        val done = ((t - at[k]).toFloat() / (end[k] - at[k]).coerceAtLeast(1)).coerceIn(0f, 1f)
        return starts[k] + done * size
    }
}

/** The sweep for [line], or null when it has no word timings that fit its text. */
private fun wordSweep(line: LyricsLine, nextAt: Long?): WordSweep? {
    val words = line.words
    if (words.isEmpty() || words.joinToString("") { it.text } != line.text) return null
    val starts = IntArray(words.size)
    var offset = 0
    words.forEachIndexed { i, w ->
        starts[i] = offset
        offset += w.text.length
    }
    val at = LongArray(words.size) { words[it].atMs.toLong() }
    // The last word ends where the source says, else after a beat, and never
    // inside the next line. A word held across a pause stops filling after
    // two seconds instead of crawling through it.
    val last = line.endMs?.toLong() ?: (at.last() + 700).let { e -> nextAt?.let { minOf(e, it) } ?: e }
    val end = LongArray(words.size) { k ->
        val next = if (k + 1 < words.size) at[k + 1] else last
        minOf(next, at[k] + 2_000).coerceAtLeast(at[k] + 1)
    }
    return WordSweep(starts, at, end, line.text.length)
}

/**
 * [text] lit up to [reach] characters and dim after, with a soft edge
 * [edge] px wide. Each visual line is drawn on its own (clipped), so a
 * wrapped line fills one row after the other.
 */
private fun DrawScope.drawSweep(text: TextLayoutResult, reach: Float, edge: Float, lit: Color, dim: Color) {
    val length = text.layoutInput.text.length
    if (reach >= length) return drawText(text, lit)
    if (reach <= 0f) return drawText(text, dim)
    for (row in 0 until text.lineCount) {
        val from = text.getLineStart(row)
        val to = text.getLineEnd(row)
        clipRect(top = text.getLineTop(row), bottom = text.getLineBottom(row)) {
            when {
                reach >= to -> drawText(text, lit)
                reach <= from -> drawText(text, dim)
                else -> {
                    val i = reach.toInt()
                    val x0 = text.getHorizontalPosition(i, true)
                    // At the row's end the next offset belongs to the row below.
                    val x1 = if (i + 1 >= to) text.getLineRight(row) else text.getHorizontalPosition(i + 1, true)
                    val x = x0 + (x1 - x0) * (reach - i)
                    drawText(text, Brush.horizontalGradient(listOf(lit, dim), startX = x - edge / 2, endX = x + edge / 2))
                }
            }
        }
    }
}

/** The card's clip: band [inset, w - inset] × [0, height] with rounded corners. */
private data class SheetShape(val inset: Float, val height: Float, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(
            RoundRect(
                inset, 0f, (size.width - inset).coerceAtLeast(inset), height.coerceAtMost(size.height),
                CornerRadius(radius.coerceAtLeast(0f)),
            ),
        )
}

/**
 * The back of the player's cover: the album the song is from, the song
 * marked. Tapping a song plays the album from it; the header turns back.
 */
@Composable
private fun AlbumBack(track: TrackInfo, controls: PlayerControls, modifier: Modifier, onClose: () -> Unit) {
    val ink = artInk()
    val wash = artWash()
    var album by remember { mutableStateOf<AlbumDetail?>(null) }
    var failed by remember { mutableStateOf(false) }
    val isCurrent = { t: Track -> t.uri == track.uri || t.title == track.title }
    LaunchedEffect(track.uri) {
        // Next song of the same album: keep the list, just move the mark.
        if (album?.tracks?.any(isCurrent) == true) return@LaunchedEffect
        failed = false
        album = try {
            controls.albumOf(track.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        failed = album == null
    }
    val accent = accentColor()
    Box(modifier.background(Color(0xFF0B0B0E))) {
        AsyncImage(
            track.coverUrl, null,
            Modifier.fillMaxSize().graphicsLayer {
                alpha = .6f
                if (blurEffectSupported) renderEffect = BlurEffect(90f, 90f, TileMode.Clamp)
            },
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxSize().background(wash.copy(.52f)))
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            val a = album
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClose).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(a?.album?.imageUrl ?: track.coverUrl, Modifier.size(46.dp), 8.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a?.album?.name ?: track.album, color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val details = listOfNotNull(a?.album?.artists ?: track.artists, a?.album?.year, a?.let { if (it.tracks.size == 1) "1 faixa" else "${it.tracks.size} faixas" })
                    Text(details.joinToString(" · "), color = ink.copy(.62f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.Close, "Virar a capa", tint = ink.copy(.7f), modifier = Modifier.padding(horizontal = 6.dp).size(20.dp))
            }
            Spacer(Modifier.height(8.dp))
            when {
                a != null -> key(a.album.id) {
                    val here = a.tracks.indexOfFirst(isCurrent)
                    val list = rememberLazyListState((here - 2).coerceAtLeast(0))
                    LaunchedEffect(here) { if (here >= 0) list.animateScrollToItem((here - 2).coerceAtLeast(0)) }
                    LazyColumn(Modifier.weight(1f), state = list) {
                        itemsIndexed(a.tracks, key = { _, t -> t.uri }) { i, t ->
                            val current = i == here
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(if (current) ink.copy(.1f) else Color.Transparent)
                                    .clickable { controls.playAlbum(a, i) }
                                    .padding(horizontal = 8.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                                    if (current) {
                                        PlayingBars(accent, Modifier.size(12.dp))
                                    } else {
                                        Text("${i + 1}", color = ink.copy(.5f), fontSize = 13.sp)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    t.title, Modifier.weight(1f), color = if (current) accent else ink, fontSize = 14.sp,
                                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(fmt(t.durationMs.toLong()), color = ink.copy(.5f), fontSize = 12.sp)
                            }
                        }
                    }
                }
                failed -> Text("Não deu para carregar o álbum.", Modifier.padding(8.dp), color = ink.copy(.6f), fontSize = 13.sp)
                else -> Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(6) { i -> Bone(Modifier.fillMaxWidth(.5f + (i * 37 % 40) / 100f).height(12.dp), light = true) }
                }
            }
        }
    }
}

/** Where the translation of the current lyric stands. */
sealed interface TranslationState {
    data object Off : TranslationState
    data object Working : TranslationState
    /** The song is already in the phone's language. */
    data object Same : TranslationState
    data class Done(val lines: List<String>) : TranslationState
    data class Failed(val why: String) : TranslationState
}

/** Turns the translation on and off, and says when it is coming or refused. */
@Composable
private fun TranslateButton(state: TranslationState, on: Boolean, onToggle: () -> Unit) {
    val ink = artInk()
    val haptics = rememberHaptics()
    val accent = accentColor()
    val tint = when {
        state is TranslationState.Failed -> ink.copy(.35f)
        on -> accent
        else -> ink.copy(.8f)
    }
    val label = when {
        state is TranslationState.Same -> "A letra já está no teu idioma"
        state is TranslationState.Failed -> state.why
        on -> "Esconder a tradução"
        else -> "Traduzir a letra"
    }
    IconButton({ haptics.tick(); onToggle() }) {
        if (on && state is TranslationState.Working) {
            CircularProgressIndicator(Modifier.size(18.dp), color = accent, strokeWidth = 2.dp)
        } else {
            Icon(Icons.Rounded.Translate, label, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/** A plain skip for the full-screen lyrics, with the same push as the player's. */
@Composable
private fun LyricsSkip(icon: ImageVector, description: String, enabled: Boolean, dir: Int, onClick: () -> Unit) {
    val ink = artInk()
    val haptics = rememberHaptics()
    val nudge = rememberKick(380)
    IconButton({ haptics.press(); nudge.play(); onClick() }, Modifier.alpha(if (enabled) 1f else .35f), enabled = enabled) {
        Icon(
            icon, description, tint = ink,
            modifier = Modifier.size(28.dp).graphicsLayer { translationX = dir * 7.dp.toPx() * nudge.bump() },
        )
    }
}
