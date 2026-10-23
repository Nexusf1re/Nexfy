package dev.nexfy.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import coil3.compose.LocalPlatformContext
import dev.nexfy.app.elapsedRealtimeMs
import dev.nexfy.app.ui.design.GlassBackdrop
import dev.nexfy.app.ui.design.LocalScreenScope
import dev.nexfy.app.ui.design.LocalSharedScope
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Job
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.NexfyServices
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.LocalHaze
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.ShimmerDriver
import dev.nexfy.app.ui.design.glassSource
import dev.nexfy.app.ui.design.liquidGlass
import dev.nexfy.app.ui.design.ambientGlow
import dev.nexfy.app.ui.design.GlassLevel
import dev.nexfy.app.ui.design.sheetDepth
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import dev.nexfy.app.ui.theme.accentState
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.design.springPress
import dev.nexfy.app.ui.player.PlayerControls
import dev.nexfy.app.ui.player.DevicesSheet
import dev.nexfy.app.ui.player.PlayerSheet
import dev.nexfy.app.ui.screens.AlbumScreen
import dev.nexfy.app.ui.screens.EqualizerScreen
import dev.nexfy.app.ui.screens.FriendsScreen
import dev.nexfy.app.ui.screens.DownloadsScreen
import dev.nexfy.app.ui.screens.GuideScreen
import dev.nexfy.app.ui.screens.ArtistScreen
import dev.nexfy.app.ui.screens.HomeScreen
import dev.nexfy.app.ui.screens.LibraryScreen
import dev.nexfy.app.ui.screens.LikedScreen
import dev.nexfy.app.ui.screens.PlaylistScreen
import dev.nexfy.app.ui.screens.SearchScreen
import dev.nexfy.app.ui.screens.SettingsScreen
import dev.nexfy.app.ui.screens.ShowScreen
import dev.nexfy.app.ui.screens.StatsScreen
import dev.nexfy.app.ui.theme.accentColor
import dev.nexfy.app.ui.theme.isDark
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import uniffi.nexfy_core.Lyrics
import uniffi.nexfy_core.AlbumDetail
import uniffi.nexfy_core.Device
import uniffi.nexfy_core.QueueEntry
import uniffi.nexfy_core.RemoteCommand
import uniffi.nexfy_core.RemoteState
import uniffi.nexfy_core.RepeatMode
import uniffi.nexfy_core.Track
import uniffi.nexfy_core.TrackInfo

enum class Tab(val label: String, val icon: ImageVector) {
    Search("Buscar", Icons.Rounded.Search),
    Home("Início", Icons.Rounded.Home),
    Library("Biblioteca", Icons.Rounded.LibraryMusic),
}

/** Destinations pushed on a tab's own back stack. */
sealed interface Route {
    val key: String

    data class Playlist(val id: String) : Route { override val key get() = "pl:$id" }
    data class Album(val id: String) : Route { override val key get() = "al:$id" }
    data class Artist(val id: String) : Route { override val key get() = "ar:$id" }
    data class Show(val id: String) : Route { override val key get() = "sh:$id" }
    data object Liked : Route { override val key get() = "liked" }
    data object Settings : Route { override val key get() = "settings" }
    data object Equalizer : Route { override val key get() = "equalizer" }
    data object Stats : Route { override val key get() = "stats" }
    data object Friends : Route { override val key get() = "friends" }
    data object Guide : Route { override val key get() = "guide" }
    data object Downloads : Route { override val key get() = "downloads" }
}

/** What the tapped card already showed, drawn while the full page loads. */
data class Peek(val title: String, val subtitle: String? = null, val imageUrl: String? = null)

/** What the screen shows: a tab and the top of its stack (null = the tab root). */
data class Screen(val tab: Tab, val route: Route?, val depth: Int = 0)

/** Each tab keeps its own back stack and the scroll position of every screen. */
@Stable
class NavState {
    var tab by mutableStateOf(Tab.Home)
    private val stacks = mutableStateMapOf<Tab, List<Route>>()
    private val lists = HashMap<String, LazyListState>()
    private val peeks = HashMap<String, Peek>()
    private val origins = HashMap<String, String>()

    fun stack(tab: Tab = this.tab) = stacks[tab].orEmpty()
    fun top(tab: Tab = this.tab) = stack(tab).lastOrNull()
    val screen get() = Screen(tab, top(), stack().size)

    /** The screen under the current one, where back leads. */
    fun parentScreen() = stack().let { Screen(tab, it.dropLast(1).lastOrNull(), it.size - 1) }

    fun push(route: Route, peek: Peek? = null, origin: String? = null) {
        if (peek != null) peeks[route.key] = peek
        if (origin != null) origins[route.key] = origin
        if (top() != route) stacks[tab] = stack() + route
    }

    fun peek(key: String): Peek? = peeks[key]

    /** Shared-element key of the card a page was opened from, for its header cover. */
    fun origin(key: String): String? = origins[key]

    fun pop(): Boolean {
        val s = stack()
        if (s.isEmpty()) return false
        stacks[tab] = s.dropLast(1)
        return true
    }

    fun popToRoot() {
        stacks[tab] = emptyList()
    }

    fun list(key: String) = lists.getOrPut(key) { LazyListState() }
}

/** What every screen needs: the core, playback and navigation. */
class AppActions(
    val services: NexfyServices,
    val nav: NavState,
    val openBrowser: (String) -> Unit,
    private val scope: CoroutineScope,
) : PlayerControls {
    val core get() = services.core

    /**
     * Open [route]. [peek] is what the tapped card showed; [origin] is the
     * card cover's shared-element key, so the cover travels to the header.
     */
    fun open(route: Route, peek: Peek? = null, origin: String? = null) = nav.push(route, peek, origin)
    fun back() = nav.pop()

    /** Run a playback command off the main thread, reporting failures. */
    private fun command(action: suspend () -> Unit) = services.commands.run(action = action)

    /**
     * Play [tracks] from [index]. With a [contextUri] (a whole playlist,
     * album or liked songs, unfiltered) Spotify plays the context itself:
     * its queue, shuffle and autoplay after the end. Otherwise exactly
     * these tracks play. [source] is the library key counted for the
     * "most played" order. While another device is playing, it plays there.
     */
    fun play(
        tracks: List<Track>,
        index: Int = 0,
        shuffle: Boolean = false,
        source: String? = null,
        contextUri: String? = null,
    ) {
        if (tracks.isEmpty()) return
        source?.let(services.library::recordPlay)
        val start = index.coerceIn(0, tracks.lastIndex)
        // No network: the list's songs that are on the phone play here, with no
        // session to start first.
        if (!dev.nexfy.app.Connectivity.online.value) {
            val undo = services.bridge.optimistic { it.copy(loading = true, stopped = false) }
            services.commands.run(undo, connect = false) { core.playOffline(tracks.map { it.uri }, start.toUInt(), shuffle) }
            return
        }
        val inJam = dev.nexfy.app.ui.player.JamState.current.value != null
        if (remote != null) {
            command {
                if (shuffle) {
                    // Another device keeps the shuffled order it drew for a playlist;
                    // a list shuffled here, starting on the chosen song, is new each time.
                    val order = listOf(tracks[start]) + (tracks - tracks[start]).shuffled()
                    core.remoteCommand(RemoteCommand.Shuffle(false))
                    core.remoteCommand(RemoteCommand.PlayTracks(order.map { it.uri }, 0u))
                } else if (contextUri != null && !(inJam && contextUri.endsWith(":collection"))) {
                    core.remoteCommand(RemoteCommand.PlayContext(contextUri, tracks[start].uri))
                } else if (inJam) {
                    // A device of another account resolves a Liked Songs context against
                    // its own library (a Jam host would play its own likes), so the songs
                    // themselves go, from the tapped one on (the command takes up to 300).
                    core.remoteCommand(RemoteCommand.PlayTracks(tracks.drop(start).take(300).map { it.uri }, 0u))
                } else {
                    core.remoteCommand(RemoteCommand.PlayTracks(tracks.map { it.uri }, start.toUInt()))
                }
            }
            services.refreshRemoteSoon()
            return
        }
        // The tapped song takes the player at once, loading, while Spotify starts it.
        val t = tracks[start]
        val now = elapsedRealtimeMs()
        val undo = services.bridge.optimistic {
            it.copy(
                track = TrackInfo(t.uri, t.title, t.artists, t.album, t.imageUrl, t.durationMs),
                loading = true, stopped = false, positionMs = 0, positionAt = now,
            )
        }
        // Shuffled, the start is the random song the caller picked (a new one
        // each time) and librespot shuffles the rest with a fresh seed.
        services.commands.run(undo) {
            if (contextUri != null) {
                core.playContext(contextUri, tracks[start].uri, shuffle)
            } else {
                core.playTracks(tracks.map { it.uri }, start.toUInt(), shuffle)
            }
        }
        services.commands.watchLoading()
    }

    /** Add a song to Spotify's queue; it plays after the current one. */
    fun addToQueue(track: Track) {
        // In a Jam the queue that counts is the Jam's, wherever it plays.
        if (dev.nexfy.app.ui.player.JamState.current.value != null) {
            return dev.nexfy.app.ui.player.JamState.queue(services, track.uri, track.title)
        }
        if (remote != null) remoteCommand(RemoteCommand.Queue(track.uri)) else command { core.addToQueue(track.uri) }
        services.toast("Adicionada à fila: ${track.title}")
    }

    /** Play the radio of a song or artist, wherever playback is. */
    /** Plays a song's or artist's radio and opens its playlist, as Spotify does. */
    override fun startRadio(seedUri: String) =
        services.commands.startRadio(seedUri) { id -> open(Route.Playlist(id), Peek("Rádio")) }

    /** The device playing elsewhere, if any: playback commands go to it. */
    private val remote get() = services.commands.remote

    /** Send a command to the remote device, showing its effect at once. */
    private fun remoteCommand(cmd: RemoteCommand, show: ((RemoteState) -> RemoteState)? = null) = services.commands.remote(cmd, show = show)

    override fun playPause() = services.commands.playPause()

    override fun next() = services.commands.next()

    override fun previous() = services.commands.previous()

    override fun seek(positionMs: Long) = services.commands.seek(positionMs)

    override fun setShuffle(on: Boolean) {
        // One shuffle mode: the player's toggle and the pages' shuffle buttons agree.
        services.library.setShuffle(on)
        services.commands.setShuffle(on)
    }

    override fun setRepeat(mode: RepeatMode) = services.commands.setRepeat(mode)

    override suspend fun devices(): List<Device> = core.devices()

    override suspend fun remoteQueue(): List<Track> = core.remoteQueue()

    override fun openArtist(id: String, name: String) = open(Route.Artist(id), Peek(name))

    override fun openAlbum(id: String, name: String, artists: String, imageUrl: String?) =
        open(Route.Album(id), Peek(name, artists, imageUrl))

    /** Whether the devices sheet is open; any screen can open it. */
    val showDevices = mutableStateOf(false)

    /** Whether the new-version sheet is open (from the banner or the settings). */
    val showUpdate = mutableStateOf(false)

    override fun openEqualizer() = open(Route.Equalizer)

    override fun openDevices() {
        showDevices.value = true
    }

    /** Move playback to [device]; the phone's own entry brings it back here. */
    override fun transfer(device: Device) {
        if (device.isSelf) services.bridge.movedHere()
        command { core.transfer(device.id, true) }
        services.refreshRemoteSoon()
    }

    override fun setVolume(percent: Int) = remoteCommand(RemoteCommand.Volume(percent.coerceIn(0, 100).toUInt()))

    /**
     * Jump to a queue entry [ahead] positions after the current song.
     * Near ones are skipped to, which keeps the user's queued songs; far
     * ones restart the context at that song.
     */
    override fun skipTo(entry: QueueEntry, ahead: Int) = command {
        if (ahead < 10) repeat(ahead + 1) { core.next() } else core.skipTo(entry.uri)
    }

    override suspend fun translateLyrics(trackUri: String, lines: List<String>, target: String, email: String?) =
        core.lyricsTranslation(trackUri, lines, target, email)

    override suspend fun lyrics(track: TrackInfo): Lyrics? =
        core.lyrics(track.uri, track.title, track.artists, track.album, track.durationMs)

    override suspend fun tracksInfo(uris: List<String>): List<Track> = core.tracksInfo(uris)

    override suspend fun albumOf(trackUri: String): AlbumDetail? =
        core.tracksInfo(listOf(trackUri)).firstOrNull()?.albumId?.let { core.album(it, false) }

    override fun playAlbum(album: AlbumDetail, index: Int) =
        play(album.tracks, index, source = "al:${album.album.id}", contextUri = album.album.uri)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NexfyRoot(
    services: NexfyServices,
    openBrowser: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val context = LocalPlatformContext.current
    val nav = remember { NavState() }
    val scope = rememberCoroutineScope()
    val actions = remember { AppActions(services, nav, openBrowser, scope) }
    val snapshot by services.bridge.state.collectAsState()
    val haze = remember { GlassBackdrop() }
    val glow = accentState()

    ShimmerDriver()
    // A refused command buzzes once, whichever control sent it.
    val haptics = rememberHaptics()
    val refusal by dev.nexfy.app.playback.Refusals.last.collectAsState()
    val seenRefusal = remember { refusal?.id }
    LaunchedEffect(refusal?.id) {
        if (refusal != null && refusal?.id != seenRefusal) haptics.reject()
    }
    // Whether this account is in a Jam, so the song menus and "Adicionar à
    // fila" go to it without the Jam sheet having been opened first.
    LaunchedEffect(Unit) {
        dev.nexfy.app.ui.player.JamState.current.value = runCatching { services.core.jamCurrent() }.getOrNull()
    }
    // Screens move through a seekable transition: navigation animates it,
    // a back gesture drags it (the page slides off and uncovers the one
    // underneath) and letting go finishes or cancels it. A back that is not
    // dragged, a tap on the arrow or the button, keeps the card animation.
    val target = nav.screen
    val screens = remember { SeekableTransitionState(target) }
    // Whether the page underneath is being uncovered by a finger: the drag
    // from the edge and the drag from anywhere both say so, and both get the
    // slide. Set before the first seek, which is what starts the transition,
    // and cleared only once the pages have settled: cleared as the way back
    // of a drag let go began, it switched to the card animation halfway.
    var swiping by remember { mutableStateOf(false) }
    fun settled() {
        if (screens.currentState == screens.targetState) swiping = false
    }
    // A back drag given up: the page slides home from where it was let go,
    // the same slide seeked backwards frame by frame, and is then put there.
    // animateTo(currentState) reversed the transition instead, and Compose
    // started that one from its beginning: the page's entrance from a card,
    // over the whole page underneath, which was the jump.
    var rewinding by remember { mutableStateOf<Job?>(null) }
    suspend fun stayHere() {
        val parent = screens.targetState
        val from = screens.fraction
        if (parent != screens.currentState && from > 0f) {
            val ms = (Motion.SWIPE_MS * from).coerceAtLeast(120f)
            val start = withFrameNanos { it }
            while (true) {
                val t = ((withFrameNanos { it } - start) / 1_000_000f / ms).coerceIn(0f, 1f)
                screens.seekTo(from * (1f - FastOutSlowInEasing.transform(t)), parent)
                if (t >= 1f) break
            }
        }
        screens.snapTo(screens.currentState)
        settled()
    }
    LaunchedEffect(target) {
        screens.animateTo(target)
        settled()
    }
    MetricsState("screen", target.route?.key?.substringBefore(':') ?: "tab-${target.tab.name.lowercase()}")
    MetricsState("nav", if (screens.currentState != screens.targetState) "moving" else null)
    val windowWidth = LocalWindowInfo.current.containerSize.width.toFloat().coerceAtLeast(1f)
    ProgressiveBackGesture(enabled = nav.stack().isNotEmpty()) { events ->
        rewinding?.cancel()
        val parent = nav.parentScreen()
        try {
            var touched = false
            events.collect { event ->
                swiping = true
                touched = touched || event.touchX > 0f
                screens.seekTo(backTravel(event, windowWidth, touched), parent)
            }
            nav.pop()
        } catch (e: CancellationException) {
            rewinding = scope.launch { stayHere() }
            throw e
        }
    }
    BackGesture(enabled = nav.stack().isEmpty() && nav.tab != Tab.Home) { nav.tab = Tab.Home }

    // Back by dragging the page sideways from anywhere: the same seekable
    // transition, with a slide that follows the finger instead of the card.
    val swipeBackOn by services.library.swipeBack.collectAsState()
    var swipe by remember { mutableFloatStateOf(-1f) }
    var swipeParent by remember { mutableStateOf<Screen?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { swipe }.collect { p ->
            val parent = swipeParent
            if (p >= 0f && parent != null) screens.seekTo(p, parent)
        }
    }
    val swipeBack = Modifier.swipeToBack(
        enabled = swipeBackOn && nav.stack().isNotEmpty(),
        onStart = {
            rewinding?.cancel()
            haptics.tick()
            swipeParent = nav.parentScreen()
            swiping = true
        },
        onProgress = { swipe = it },
        onEnd = { done ->
            swipe = -1f
            if (done) {
                nav.pop()
            } else {
                rewinding = scope.launch { stayHere() }
            }
        },
    )

    val bottomSpace = if (snapshot.track != null) 176.dp else 104.dp
    CompositionLocalProvider(LocalHaze provides haze, LocalBottomSpace provides bottomSpace, dev.nexfy.app.ui.design.LocalPlaying provides snapshot.playing) {
        // The background stays put; everything on it steps back under an open sheet.
        Box(Modifier.fillMaxSize().background(c.background).sheetDepth()) {
            // Everything behind the dock and the mini player is what their glass blurs.
            Box(
                Modifier.fillMaxSize()
                    // Under the open player the pages step back and dim; pulling it down brings them forward.
                    .graphicsLayer {
                        val p = dev.nexfy.app.ui.player.PlayerReveal.open.coerceIn(0f, 1f)
                        if (p > .001f) {
                            val sc = 1f - .06f * p
                            scaleX = sc
                            scaleY = sc
                            shape = RoundedCornerShape((28f * p).dp)
                            clip = true
                        }
                    }
                    .drawWithContent {
                        drawContent()
                        val p = dev.nexfy.app.ui.player.PlayerReveal.open.coerceIn(0f, 1f)
                        if (p > .001f) drawRect(Color.Black.copy(alpha = .4f * p))
                    }
                    .glassSource(haze).then(swipeBack),
            ) {
                SharedTransitionLayout(Modifier.fillMaxSize()) {
                    // No cover morph under a back drag: the page slides off whole, its
                    // cover with it. The morph started from the card underneath, its
                    // image still loading (a blank square), and crossed the slide.
                    CompositionLocalProvider(LocalSharedScope provides this.takeUnless { swiping }) {
                        rememberTransition(screens, label = "screen").AnimatedContent(
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                when {
                                    initialState.tab != targetState.tab ->
                                        Motion.tabSwitch(targetState.tab.ordinal - initialState.tab.ordinal)
                                    targetState.depth < initialState.depth -> if (swiping) Motion.swipeBack else Motion.pageBack
                                    else -> Motion.pageEnter togetherWith Motion.pageExit using SizeTransform(clip = false)
                                }
                            },
                        ) { screen ->
                            // Pages leaving or arriving show as rounded cards.
                            // Under a back drag, as long as the slide: a longer spring here
                            // stretched the seek, and the page ran ahead of the finger (half
                            // open with the finger at a third, then sent back on release).
                            val corner by transition.animateDp(
                                transitionSpec = { if (swiping) tween(Motion.SWIPE_MS, easing = LinearEasing) else spring(visibilityThreshold = Dp.VisibilityThreshold) },
                                label = "corner",
                            ) { if (it == EnterExitState.Visible) 0.dp else 32.dp }
                            CompositionLocalProvider(LocalScreenScope provides this) {
                                Box(
                                    Modifier.fillMaxSize().graphicsLayer {
                                        shape = RoundedCornerShape(corner)
                                        clip = corner > 0.5.dp
                                    }.ambientGlow(c.background, glow, c.isDark),
                                ) {
                                    ScreenContent(screen, actions, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }

            Dock(
                nav.tab,
                onSelect = { tab ->
                    if (tab == nav.tab) {
                        // Tapping the current tab again returns to its root, then scrolls to the top.
                        if (nav.stack().isNotEmpty()) {
                            nav.popToRoot()
                        } else {
                            scope.launch { nav.list("root:${tab.name}").animateScrollToItem(0) }
                        }
                    } else {
                        nav.tab = tab
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            PlayerSheet(snapshot, actions)
            LinkOpener(actions)
            UpdateBanner(
                services.updates,
                { actions.showUpdate.value = true },
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 24.dp, end = 24.dp, bottom = bottomSpace + 6.dp),
            )
            NoticeBar(
                services.edits,
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 24.dp, end = 24.dp, bottom = bottomSpace + 6.dp),
            )
            if (actions.showDevices.value) DevicesSheet(snapshot, actions) { actions.showDevices.value = false }
            if (actions.showUpdate.value) {
                val updates = services.updates
                val r = rememberUpdateOffer(services)
                if (updates != null && r != null) {
                    // The machine's own browser, not a Custom Tab: a tab hangs
                    // on the download's redirect.
                    UpdateSheet(r, updates, onBrowser = { openInBrowser(context, it) }) {
                        actions.showUpdate.value = false
                    }
                } else {
                    LaunchedEffect(Unit) { actions.showUpdate.value = false }
                }
            }
        }
    }
}

@Composable
private fun ScreenContent(screen: Screen, actions: AppActions, modifier: Modifier) {
    val nav = actions.nav
    when (val route = screen.route) {
        is Route.Playlist -> PlaylistScreen(actions, route.id, nav.list(route.key), modifier)
        is Route.Album -> AlbumScreen(actions, route.id, nav.list(route.key), modifier)
        is Route.Artist -> ArtistScreen(actions, route.id, nav.list(route.key), modifier)
        is Route.Show -> ShowScreen(actions, route.id, nav.list(route.key), modifier)
        Route.Liked -> LikedScreen(actions, nav.list(route.key), modifier)
        Route.Settings -> SettingsScreen(actions, modifier.safeDrawingPadding())
        Route.Equalizer -> EqualizerScreen(actions, modifier.safeDrawingPadding())
        Route.Stats -> StatsScreen(actions, nav.list(route.key), modifier.safeDrawingPadding())
        Route.Friends -> FriendsScreen(actions, nav.list(route.key), modifier.safeDrawingPadding())
        Route.Guide -> GuideScreen(actions, modifier.safeDrawingPadding())
        Route.Downloads -> DownloadsScreen(actions, nav.list(route.key), modifier.safeDrawingPadding())
        null -> {
            val list = nav.list("root:${screen.tab.name}")
            Box(modifier.safeDrawingPadding()) {
                when (screen.tab) {
                    Tab.Home -> HomeScreen(actions, list)
                    Tab.Search -> SearchScreen(actions, list)
                    Tab.Library -> LibraryScreen(actions, list)
                }
            }
        }
    }
}

/**
 * Floating glass dock. The highlight is a liquid lens: its leading edge
 * runs on a firm spring and the trailing one follows on a soft spring, so it
 * stretches on the way and settles with a slight bounce. The tabs under it
 * show magnified, as through a drop of water, and more so while it moves.
 */
@Composable
private fun Dock(tab: Tab, onSelect: (Tab) -> Unit, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val accent = accentColor()
    val tabs = Tab.entries
    val index = tab.ordinal.toFloat()
    val lead = animateFloatAsState(index, spring(.62f, 620f), label = "lead")
    val trail = animateFloatAsState(index, spring(.74f, 230f), label = "trail")
    val pill = accent.copy(alpha = if (c.isDark) .16f else .12f)
    val tabsLayer = rememberGraphicsLayer()
    val lensPath = remember { Path() }
    Box(modifier) {
        // The blur node carries no shadow layer; the shadow is a sibling behind it.
        Box(Modifier.matchParentSize().shadow(18.dp, CircleShape, ambientColor = Color.Black.copy(.25f), spotColor = Color.Black.copy(.25f)))
        Row(
            Modifier.fillMaxWidth().liquidGlass(CircleShape, c.isDark, GlassLevel.Regular).padding(5.dp)
                .drawWithContent {
                    val w = size.width / tabs.size
                    val n = (tabs.size - 1).toFloat()
                    val l = minOf(lead.value, trail.value).coerceIn(0f, n)
                    val r = maxOf(lead.value, trail.value).coerceIn(0f, n)
                    val stretch = (r - l).coerceIn(0f, 1f)
                    val inset = 2.dp.toPx() * (1f - stretch)
                    val squash = size.height * .06f * stretch
                    val lens = Rect(Offset(l * w + inset, squash), Size((r - l + 1f) * w - 2 * inset, size.height - 2 * squash))
                    val corner = CornerRadius(lens.height / 2f)
                    lensPath.rewind()
                    lensPath.addRoundRect(RoundRect(lens, corner))
                    tabsLayer.record { this@drawWithContent.drawContent() }
                    drawRoundRect(pill, lens.topLeft, lens.size, corner)
                    clipPath(lensPath, ClipOp.Difference) { drawLayer(tabsLayer) }
                    clipPath(lensPath) {
                        scale(1.1f + .08f * stretch, pivot = lens.center) { drawLayer(tabsLayer) }
                    }
                    drawRoundRect(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(if (c.isDark) .42f else .9f), Color.White.copy(.04f), Color.White.copy(if (c.isDark) .18f else .5f)),
                            startY = lens.top,
                            endY = lens.bottom,
                        ),
                        lens.topLeft, lens.size, corner,
                        style = Stroke(1.dp.toPx()),
                    )
                },
        ) {
            tabs.forEach { t ->
                val on = t == tab
                val source = remember { MutableInteractionSource() }
                val tint by animateColorAsState(if (on) accent else c.onSurfaceVariant, Motion.standard(), label = "tint")
                Column(
                    Modifier.weight(1f).springPress(source).clip(CircleShape)
                        .clickable(source, null) {
                            if (!on) haptics.tick()
                            onSelect(t)
                        }
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, null, tint = tint, modifier = Modifier.size(23.dp))
                    Text(t.label, color = tint, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Opens the Spotify link the services was started with (or handed while open):
 * pages go on the stack, a song plays on its album's page, a Jam link joins
 * the Jam and shows it.
 */
@Composable
private fun LinkOpener(actions: AppActions) {
    val link by SpotifyLinks.pending.collectAsState()
    var jamOpen by remember { mutableStateOf(false) }
    if (jamOpen) dev.nexfy.app.ui.player.JamSheet { jamOpen = false }
    LaunchedEffect(link) {
        val text = link ?: return@LaunchedEffect
        SpotifyLinks.pending.value = null
        when (val target = SpotifyLinks.resolve(text)) {
            is LinkTarget.Album -> actions.open(Route.Album(target.id))
            is LinkTarget.Playlist -> actions.open(Route.Playlist(target.id))
            is LinkTarget.Artist -> actions.open(Route.Artist(target.id))
            is LinkTarget.Show -> actions.open(Route.Show(target.id))
            is LinkTarget.Track -> {
                val track = runCatching { actions.core.tracksInfo(listOf(target.uri)) }.getOrNull()?.firstOrNull()
                if (track == null) {
                    actions.services.toast("Não deu para abrir essa música")
                    return@LaunchedEffect
                }
                track.albumId?.let { actions.open(Route.Album(it), Peek(track.album, track.artists, track.imageUrl)) }
                actions.play(listOf(track))
            }
            is LinkTarget.Jam -> {
                runCatching { actions.core.jamJoin(target.link) }
                    .onSuccess { dev.nexfy.app.ui.player.JamState.current.value = it }
                    .onFailure { actions.services.toast("Não deu para entrar nessa Jam") }
                jamOpen = true
            }
            null -> actions.services.toast("Esse link do Spotify não abre no Nexfy")
        }
    }
}

/**
 * How far a back gesture from the edge has gone, 0 to 1 of the width: where
 * the finger is, not the system's progress. That one eases behind the finger
 * and, on a gesture given up, eases back from where it lagged, so the page
 * jumped out again before settling. Only a gesture that never [touched] a
 * position (some report none) goes by the progress: once one has, x = 0 is
 * the finger back at the edge, and taking the progress there brought the
 * lagging value, and the jump, right back.
 */
private fun backTravel(event: BackStep, width: Float, touched: Boolean): Float {
    if (!touched) return event.progress
    val travel = if (event.fromRightEdge) width - event.touchX else event.touchX
    return (travel / width).coerceIn(0f, 1f)
}

/**
 * A rightward drag that starts anywhere on the page and that nothing under
 * the finger took (a carousel scrolls first). [onProgress] gets 0 to 1 of
 * the width; [onEnd] says whether it went far or fast enough to go back.
 */
private fun Modifier.swipeToBack(
    enabled: Boolean,
    onStart: () -> Unit,
    onProgress: (Float) -> Unit,
    onEnd: (Boolean) -> Unit,
): Modifier = if (!enabled) this else pointerInput(Unit) {
    val width = size.width.toFloat().coerceAtLeast(1f)
    val fling = Motion.FlingVelocity * density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var dx = 0f
        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            if (over > 0f) {
                change.consume()
                dx = over
            }
        } ?: return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(start.uptimeMillis, start.position)
        onStart()
        onProgress((dx / width).coerceIn(0f, 1f))
        val ended = horizontalDrag(start.id) { change ->
            dx += change.positionChange().x
            change.consume()
            tracker.addPosition(change.uptimeMillis, change.position)
            onProgress((dx / width).coerceIn(0f, 1f))
        }
        val v = tracker.calculateVelocity().x
        onEnd(ended && (dx / width > .35f || v > fling) && v > -fling)
    }
}
