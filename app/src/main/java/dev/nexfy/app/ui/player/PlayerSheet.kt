package dev.nexfy.app.ui.player

import androidx.activity.compose.BackHandler
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
import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import dev.nexfy.app.ui.design.GlassBox
import dev.nexfy.app.ui.design.TextBone
import dev.nexfy.app.ui.design.boneWidth
import dev.nexfy.app.ui.design.LivingArtwork
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.ThinSlider
import dev.nexfy.app.ui.design.kenBurns
import dev.nexfy.app.ui.design.snapNow
import dev.nexfy.app.ui.design.liquidGlass
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.design.springPress
import dev.nexfy.app.ui.theme.isDark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.nexfy_core.Device
import uniffi.nexfy_core.Lyrics
import uniffi.nexfy_core.QueueEntry
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

    /** Show the devices sheet. */
    fun openDevices()

    /** Metadata for queue entries, in order. */
    suspend fun tracksInfo(uris: List<String>): List<Track>
}

private val settle = Motion.settle<Float>(247f)

/** Song change: the new title rises in as the old one fades up and out. */
private fun AnimatedContentTransitionScope<TrackInfo>.songChange(): ContentTransform =
    if (initialState.uri == targetState.uri) {
        EnterTransition.None togetherWith ExitTransition.None
    } else {
        (fadeIn(tween(320, delayMillis = 60)) + slideInVertically(Motion.settle()) { it / 3 }) togetherWith
            (fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it / 4 }) using SizeTransform(clip = false)
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
    val library = (androidx.compose.ui.platform.LocalContext.current.applicationContext as dev.nexfy.app.NexfyApp).library
    var showLyrics by rememberSaveable { mutableStateOf(library.lyricsOpen) }
    var lyricsFull by rememberSaveable { mutableStateOf(false) }
    var playerOpen by remember { mutableStateOf(false) }
    dev.nexfy.app.ui.MetricsState("player", if (!playerOpen) null else if (lyricsFull) "lyrics-full" else if (showLyrics) "lyrics" else "open")
    val track = s.track ?: lastTrack[0] ?: return
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val f = remember { Animatable(0f).apply { updateBounds(0f, 1f) } }
    var back by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val haptics = rememberHaptics()
    val position = rememberPosition(s)
    val duration = track.durationMs.toLong().coerceAtLeast(1)
    // Spotify continues with similar songs (autoplay) after a context ends,
    // so next is only off for a lone song with repeat off.
    val hasNext = s.queue.next.isNotEmpty() || s.queue.contextUri != null || s.queue.repeat == RepeatMode.ALL
    var showQueue by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    if (showActions) {
        TrackActionsSheet(track, controls, onNavigate = { scope.launch { f.animateTo(0f, settle) } }) { showActions = false }
    }
    if (showQueue) QueueSheet(s, controls) { showQueue = false }

    val pf = { f.value.coerceIn(0f, 1f) }
    val expanded by remember { derivedStateOf { f.value > 0.5f } }
    LaunchedEffect(expanded) { playerOpen = expanded }
    dev.nexfy.app.ui.MetricsState("sheet", if (f.isRunning) "moving" else null)
    val collapsed by remember { derivedStateOf { f.value < .05f } }
    val showMini by remember { derivedStateOf { f.value < .6f } }
    val showGlass by remember { derivedStateOf { f.value < .99f } }
    val showFull by remember { derivedStateOf { f.value > .4f } }
    val showBg by remember { derivedStateOf { f.value > 0.01f } }
    val settled by remember { derivedStateOf { f.value > .9f } }

    PredictiveBackHandler(enabled = expanded) { progress ->
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
        val dragState = rememberDraggableState { d ->
            scope.snapNow(f, (f.value - d / (fullH - miniH)).coerceIn(0f, 1f))
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
                    shape = SheetShape(lerp(margin, 0f, p), lerp(miniH, fullH, p).coerceIn(miniH, fullH), (lerp(34f, 0f, p) + back * 36f) * density.density)
                    clip = true
                }
                .drawBehind { drawRect(surface.copy(alpha = (pf() * 1.6f).coerceIn(0f, 1f))) }
                .draggable(
                    dragState,
                    Orientation.Vertical,
                    onDragStarted = { haptics.tick() },
                    onDragStopped = { v ->
                        val target = when {
                            v < -Motion.FlingVelocity -> 1f
                            v > Motion.FlingVelocity -> 0f
                            else -> if (f.value > .5f) 1f else 0f
                        }
                        f.animateTo(target, settle, initialVelocity = -v / (fullH - miniH))
                    },
                ),
        ) {
            // Glass mini player: blurs what is behind it, only in the mini band.
            if (showGlass) {
                Box(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(68.dp)
                        .graphicsLayer { alpha = (1f - pf()).coerceIn(0f, 1f) }
                        .liquidGlass(RoundedCornerShape(34.dp), c.isDark),
                )
            }
            // Living backdrop: a slow mesh gradient in the cover's colours.
            if (showBg) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = pf() }) {
                    LivingArtwork(track.coverUrl, Modifier.fillMaxSize(), dim = .2f)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.15f), Color.Black.copy(.55f)))))
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
            val currentUri by rememberUpdatedState(track.uri)
            // Plain holder: set by a throw, read once by the cover transition.
            val thrown = remember { booleanArrayOf(false) }
            var tapHint by remember { mutableStateOf<Pair<Long, Int>?>(null) }

            var lyrics by remember(track.uri) { mutableStateOf<LyricsState>(LyricsState.Loading) }
            LaunchedEffect(track.uri, showLyrics) {
                if (!showLyrics || lyrics != LyricsState.Loading) return@LaunchedEffect
                lyrics = try {
                    controls.lyrics(track)?.let { LyricsState.Found(it) } ?: LyricsState.None
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    LyricsState.Failed
                }
            }
            // 0 to 1 as lyrics come in: the cover blurs and darkens behind them.
            val lyricsIn = animateFloatAsState(if (showLyrics && settled) 1f else 0f, Motion.settle(169f), label = "lyrics")
            // 0 to 1 into full-screen lyrics.
            val fullIn = animateFloatAsState(if (showLyrics && lyricsFull && settled) 1f else 0f, Motion.settle(169f), label = "full")

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
                        val k = lerp(miniArt / artFullPx, 1f, p)
                        artMove(lerp(1f, breath.value, p) * (1f - kotlin.math.abs(throwX.value) / fullW * .15f))
                        rotationZ = throwX.value / fullW * 14f
                        alpha = 1f - fullIn.value
                        // Corners and shadow compensate the scale: 12 dp mini, 28 dp full.
                        shape = RoundedCornerShape((lerp(12f, 28f, p) * density.density / k.coerceAtLeast(.05f)).coerceAtLeast(0f))
                        clip = true
                        shadowElevation = lerp(0f, 24f, p) * density.density
                    }
                    .then(
                        if (settled && !showLyrics) {
                            Modifier.pointerInput(track.uri) {
                                detectTapGestures(onDoubleTap = { o ->
                                    val dir = if (o.x < size.width / 2) -1 else 1
                                    controls.seek((position + dir * 10_000).coerceIn(0, duration))
                                    tapHint = System.nanoTime() to dir
                                })
                            }
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (settled && !showLyrics) {
                            Modifier.draggable(
                                rememberDraggableState { d -> scope.snapNow(throwX, throwX.value + d) },
                                Orientation.Horizontal,
                                onDragStopped = { v ->
                                    val go = when {
                                        throwX.value < -fullW / 3 || v < -1800f -> 1
                                        throwX.value > fullW / 3 || v > 1800f -> -1
                                        else -> 0
                                    }
                                    if (go != 0 && (go == -1 || hasNext)) {
                                        haptics.press()
                                        // Skip first: the next song loads while the cover flies out.
                                        val before = currentUri
                                        thrown[0] = true
                                        if (go == 1) controls.next() else controls.previous()
                                        throwX.animateTo(-go * fullW * 1.2f, tween(170, easing = Motion.EmphasizedAccelerate))
                                        // Come back in with the new song's cover, not the old one.
                                        withTimeoutOrNull(700) { snapshotFlow { currentUri }.first { it != before } }
                                            ?: run { thrown[0] = false }
                                        throwX.snapTo(go * fullW * .8f)
                                    }
                                    throwX.animateTo(0f, Motion.settle(520f))
                                },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .background(c.outline),
                contentAlignment = Alignment.Center,
            ) {
                if (!track.coverUrl.isNullOrBlank()) {
                    // Cross-fade covers: the old one stays until the new one is in.
                    AnimatedContent(
                        track.coverUrl,
                        Modifier.fillMaxSize().graphicsLayer {
                            val r = lyricsIn.value * 70f
                            renderEffect = if (r > .5f && Build.VERSION.SDK_INT >= 31) BlurEffect(r, r, TileMode.Clamp) else null
                        },
                        // A thrown cover comes back already showing the new art; other changes cross-fade.
                        transitionSpec = {
                            if (thrown[0]) {
                                thrown[0] = false
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                fadeIn(tween(420)) togetherWith fadeOut(tween(420))
                            }
                        },
                        label = "cover",
                    ) { url ->
                        AsyncImage(url, null, Modifier.fillMaxSize().kenBurns(s.playing && settled && !showLyrics), contentScale = ContentScale.Crop)
                    }
                } else {
                    Icon(Icons.Rounded.MusicNote, null, tint = c.onSurfaceVariant, modifier = Modifier.fillMaxSize(.4f))
                }
                // Darkens the blurred cover so the lyrics read on any art.
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = lyricsIn.value }.background(Color.Black.copy(.58f)))
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
                        }
                        .padding(horizontal = 28.dp),
                ) {
                    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ scope.launch { f.animateTo(0f, settle) } }) {
                            Icon(Icons.Rounded.KeyboardArrowDown, "Recolher", tint = Color.White)
                        }
                        // Balances the two buttons on the right, keeping the handle centred.
                        Spacer(Modifier.width(48.dp))
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(width = 36.dp, height = 5.dp).clip(CircleShape).background(Color.White.copy(.35f)))
                        Spacer(Modifier.weight(1f))
                        IconButton({ controls.openDevices() }) {
                            Icon(Icons.Rounded.Devices, "Dispositivos", tint = if (s.remote != null) accentColor() else Color.White)
                        }
                        IconButton({ showActions = true }) {
                            Icon(Icons.Rounded.MoreVert, "Mais opções", tint = Color.White)
                        }
                    }
                    // Lyrics sit inside the cover's square, over the blurred art.
                    Box(Modifier.fillMaxWidth().height(artSpace), contentAlignment = Alignment.Center) {
                        if (showLyrics) {
                            Box(Modifier.size(artFull).graphicsLayer { alpha = lyricsIn.value * (1f - fullIn.value) }) {
                                LyricsView(lyrics, position, controls, Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp))
                                IconButton({ lyricsFull = true }, Modifier.align(Alignment.TopEnd)) {
                                    Icon(Icons.Rounded.OpenInFull, "Letra em tela cheia", tint = Color.White.copy(.8f), modifier = Modifier.size(20.dp))
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
                        AnimatedContent(track, transitionSpec = { songChange() }, label = "title") { t ->
                            Column {
                                Text(
                                    t.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp,
                                    letterSpacing = (-.5f).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    t.artists, color = Color.White.copy(.62f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        var drag by remember { mutableStateOf<Float?>(null) }
                        ThinSlider(
                            drag ?: (position.toFloat() / duration),
                            { drag = it },
                            {
                                drag?.let { controls.seek((it * duration).toLong()) }
                                drag = null
                            },
                        )
                        val shown = drag?.let { (it * duration).toLong() } ?: position
                        Row {
                            Text(fmt(shown), color = Color.White.copy(.6f), fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            Text("-" + fmt(duration - shown), color = Color.White.copy(.6f), fontSize = 12.sp)
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
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SideButton(Icons.Rounded.SkipPrevious, "Anterior", Modifier.weight(1f), true, controls::previous)
                            PlayButton(s, controls, 84.dp, Color.White, Color.Black, Modifier.weight(2f), pill = true)
                            SideButton(Icons.Rounded.SkipNext, "Próxima", Modifier.weight(1f), hasNext, controls::next)
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            ToggleIcon(Icons.Rounded.Shuffle, "Aleatório", s.queue.shuffle) { controls.setShuffle(!s.queue.shuffle) }
                            Spacer(Modifier.width(8.dp))
                            ToggleIcon(
                                if (s.queue.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                when (s.queue.repeat) {
                                    RepeatMode.ONE -> "Repetir faixa"
                                    RepeatMode.ALL -> "Repetir fila"
                                    RepeatMode.OFF -> "Repetição desligada"
                                },
                                s.queue.repeat != RepeatMode.OFF,
                            ) {
                                controls.setRepeat(
                                    when (s.queue.repeat) {
                                        RepeatMode.OFF -> RepeatMode.ALL
                                        RepeatMode.ALL -> RepeatMode.ONE
                                        RepeatMode.ONE -> RepeatMode.OFF
                                    },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            ToggleIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Fila", false) { showQueue = true }
                            Spacer(Modifier.weight(1f))
                            ToggleIcon(Icons.Rounded.Lyrics, "Letra", showLyrics) {
                                showLyrics = !showLyrics
                                library.lyricsOpen = showLyrics
                                if (!showLyrics) lyricsFull = false
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }

            // Full-screen lyrics: the cover and controls fade away; the song and
            // play/pause stay at the bottom.
            if (showFull && showLyrics && lyricsFull) {
                BackHandler { lyricsFull = false }
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding().padding(top = 56.dp)
                        .graphicsLayer { alpha = fullIn.value }
                        // Taps between lines must not reach the faded controls underneath.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(horizontal = 28.dp),
                ) {
                    LyricsView(lyrics, position, controls, Modifier.weight(1f).fillMaxWidth(), large = true)
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(track.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artists, color = Color.White.copy(.62f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        PlayButton(s, controls, 52.dp, Color.White, Color.Black)
                        IconButton({ lyricsFull = false }) {
                            Icon(Icons.Rounded.CloseFullscreen, "Sair da tela cheia", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayButton(
    s: PlaybackSnapshot,
    controls: PlayerControls,
    size: Dp,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    pill: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Box(
        modifier.then(if (pill) Modifier.height(size) else Modifier.size(size)).springPress(source).clip(CircleShape).background(bg)
            .clickable(source, null) {
                haptics.press()
                controls.playPause()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (s.loading) {
            CircularProgressIndicator(Modifier.size(size * .4f), color = fg, strokeWidth = 2.dp)
        } else {
            AnimatedContent(s.playing, label = "pp") { playing ->
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (playing) "Pausar" else "Tocar",
                    tint = fg,
                    modifier = Modifier.size(size * .45f),
                )
            }
        }
    }
}

@Composable
private fun SideButton(icon: ImageVector, description: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    GlassBox(modifier.height(84.dp).alpha(if (enabled) 1f else .35f).springPress(source), CircleShape) {
        Box(
            Modifier.matchParentSize().clickable(source, null, enabled = enabled) {
                haptics.press()
                onClick()
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, description, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun ToggleIcon(icon: ImageVector, description: String, on: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Box(
        Modifier.size(48.dp).springPress(source).clip(CircleShape)
            .background(if (on) Color.White.copy(.9f) else Color.White.copy(.08f))
            .clickable(source, null) {
                haptics.tick()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (on) Color.Black else Color.White.copy(.8f), modifier = Modifier.size(22.dp))
    }
}

/** A wash from the tapped side with "−10 s" or "+10 s". */
@Composable
private fun BoxScope.SeekRipple(id: Long, dir: Int) {
    val v = remember(id) { Animatable(0f) }
    LaunchedEffect(id) { v.animateTo(1f, Motion.enter(Motion.ExtraLong)) }
    if (v.value >= 1f) return
    Box(
        Modifier.align(if (dir < 0) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.5f)
            .graphicsLayer { alpha = 1f - v.value }
            .background(
                Brush.horizontalGradient(
                    if (dir < 0) listOf(Color.White.copy(.35f), Color.Transparent) else listOf(Color.Transparent, Color.White.copy(.35f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (dir < 0) "−10 s" else "+10 s",
            color = Color.White,
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
            s.track?.let { track ->
                item(key = "now") {
                    QueueHeading("Tocando agora")
                    QueueRow(track.coverUrl, track.title, track.artists, highlight = true) {}
                }
            }
            if (queued.isNotEmpty()) item(key = "h-queued") { QueueHeading("Na fila") }
            items(queued, key = { "q${it.index}:${it.value.uri}" }) { (i, entry) ->
                QueueEntryRow(info[entry.uri], i) {
                    controls.skipTo(entry, i)
                    onDismiss()
                }
            }
            if (upcoming.isNotEmpty()) item(key = "h-next") { QueueHeading(if (queued.isEmpty()) "A seguir" else "Depois, da lista") }
            items(upcoming, key = { "n${it.index}:${it.value.uri}" }) { (i, entry) ->
                QueueEntryRow(info[entry.uri], i) {
                    controls.skipTo(entry, i)
                    onDismiss()
                }
            }
            if (next.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nada na fila. Ao terminar, o Spotify continua com músicas parecidas.",
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
                        "Nada na fila. Ao terminar, o Spotify continua com músicas parecidas.",
                        color = c.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                else -> {
                    item(key = "h-next") { QueueHeading("A seguir") }
                    itemsIndexed(list, key = { i, t -> "r$i:${t.uri}" }) { i, t ->
                        QueueEntryRow(t, i) {
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
private fun QueueEntryRow(track: Track?, seed: Int, onClick: () -> Unit) {
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
            QueueRow(t.imageUrl, t.title, t.artists, onClick = onClick)
        }
    }
}

@Composable
private fun QueueHeading(text: String) =
    Text(text, Modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)

@Composable
private fun QueueRow(imageUrl: String?, title: String, subtitle: String, highlight: Boolean = false, onClick: () -> Unit) {
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
 * a timed line seeks to it.
 */
@Composable
private fun LyricsView(state: LyricsState, position: Long, controls: PlayerControls, modifier: Modifier, large: Boolean = false) {
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
                color = Color.White.copy(.6f),
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
    val list = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) list.animateScrollToItem(current)
    }
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
                    val blur = if (Build.VERSION.SDK_INT >= 31 && d > .05f) (d * 1.8f).dp else 0.dp
                    Text(
                        line.text,
                        Modifier.fillMaxWidth()
                            .graphicsLayer {
                                val sc = .88f + .12f * focus
                                scaleX = sc
                                scaleY = sc
                                alpha = (1f - .2f * d).coerceIn(.3f, 1f) * (.55f + .45f * focus)
                                transformOrigin = TransformOrigin(0f, .5f)
                            }
                            .then(if (blur > 0.dp) Modifier.blur(blur, BlurredEdgeTreatment.Unbounded) else Modifier)
                            .clickable(
                                enabled = line.atMs != null,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { line.atMs?.let { controls.seek(it.toLong()) } },
                        color = Color.White,
                        fontSize = when { large && lyrics.synced -> 30.sp; large -> 22.sp; lyrics.synced -> 24.sp; else -> 19.sp },
                        fontWeight = FontWeight.Bold,
                        lineHeight = when { large && lyrics.synced -> 36.sp; large -> 28.sp; lyrics.synced -> 30.sp; else -> 25.sp },
                    )
                }
            }
        }
        Text(
            "Letra: ${lyrics.source}",
            color = Color.White.copy(.4f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
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
