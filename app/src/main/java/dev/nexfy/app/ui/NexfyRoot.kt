package dev.nexfy.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.nexfy.app.ui.design.LocalScreenScope
import dev.nexfy.app.ui.design.LocalSharedScope
import kotlinx.coroutines.CancellationException
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.SeekableTransitionState
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import dev.chrisbanes.haze.HazeState
import dev.nexfy.app.NexfyApp
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.LocalHaze
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.ShimmerDriver
import dev.nexfy.app.ui.design.glassSource
import dev.nexfy.app.ui.design.liquidGlass
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.design.springPress
import dev.nexfy.app.ui.player.PlayerControls
import dev.nexfy.app.ui.player.DevicesSheet
import dev.nexfy.app.ui.player.PlayerSheet
import dev.nexfy.app.ui.screens.AlbumScreen
import dev.nexfy.app.ui.screens.FriendsScreen
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
    data object Stats : Route { override val key get() = "stats" }
    data object Friends : Route { override val key get() = "friends" }
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
    val app: NexfyApp,
    val nav: NavState,
    val openBrowser: (String) -> Unit,
    private val scope: CoroutineScope,
) : PlayerControls {
    val core get() = app.core

    /**
     * Open [route]. [peek] is what the tapped card showed; [origin] is the
     * card cover's shared-element key, so the cover travels to the header.
     */
    fun open(route: Route, peek: Peek? = null, origin: String? = null) = nav.push(route, peek, origin)
    fun back() = nav.pop()

    /** Run a playback command off the main thread, reporting failures. */
    private fun command(action: suspend () -> Unit) = app.commands.run(action)

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
        source?.let(app.library::recordPlay)
        val start = index.coerceIn(0, tracks.lastIndex)
        if (remote != null) {
            command {
                if (shuffle) {
                    // Another device keeps the shuffled order it drew for a playlist;
                    // a list shuffled here, starting on the chosen song, is new each time.
                    val order = listOf(tracks[start]) + (tracks - tracks[start]).shuffled()
                    core.remoteCommand(RemoteCommand.Shuffle(false))
                    core.remoteCommand(RemoteCommand.PlayTracks(order.map { it.uri }, 0u))
                } else if (contextUri != null) {
                    core.remoteCommand(RemoteCommand.PlayContext(contextUri, tracks[start].uri))
                } else {
                    core.remoteCommand(RemoteCommand.PlayTracks(tracks.map { it.uri }, start.toUInt()))
                }
            }
            app.refreshRemoteSoon()
            return
        }
        // Shuffled, the start is the random song the caller picked (a new one
        // each time) and librespot shuffles the rest with a fresh seed.
        command {
            if (contextUri != null) {
                core.playContext(contextUri, tracks[start].uri, shuffle)
            } else {
                core.playTracks(tracks.map { it.uri }, start.toUInt(), shuffle)
            }
        }
    }

    /** Add a song to Spotify's queue; it plays after the current one. */
    fun addToQueue(track: Track) {
        if (remote != null) remoteCommand(RemoteCommand.Queue(track.uri)) else command { core.addToQueue(track.uri) }
        android.widget.Toast.makeText(app, "Adicionada à fila: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Play the radio of a song or artist, wherever playback is. */
    /** Plays a song's or artist's radio and opens its playlist, as Spotify does. */
    override fun startRadio(seedUri: String) =
        app.commands.startRadio(seedUri) { id -> open(Route.Playlist(id), Peek("Rádio")) }

    /** The device playing elsewhere, if any: playback commands go to it. */
    private val remote get() = app.commands.remote

    /** Send a command to the remote device, showing its effect at once. */
    private fun remoteCommand(cmd: RemoteCommand, show: ((RemoteState) -> RemoteState)? = null) = app.commands.remote(cmd, show)

    override fun playPause() = app.commands.playPause()

    override fun next() = app.commands.next()

    override fun previous() = app.commands.previous()

    override fun seek(positionMs: Long) {
        val at = positionMs.coerceAtLeast(0).toUInt()
        if (remote != null) return remoteCommand(RemoteCommand.Seek(at)) { it.copy(progressMs = at) }
        command { core.seek(at) }
    }

    override fun setShuffle(on: Boolean) {
        // One shuffle mode: the player's toggle and the pages' shuffle buttons agree.
        app.library.setShuffle(on)
        if (remote != null) return remoteCommand(RemoteCommand.Shuffle(on)) { it.copy(shuffle = on) }
        command { core.setShuffle(on) }
    }

    override fun setRepeat(mode: RepeatMode) {
        if (remote != null) return remoteCommand(RemoteCommand.Repeat(mode)) { it.copy(repeat = mode) }
        command { core.setRepeat(mode) }
    }

    override suspend fun devices(): List<Device> = core.devices()

    override suspend fun remoteQueue(): List<Track> = core.remoteQueue()

    override fun openArtist(id: String, name: String) = open(Route.Artist(id), Peek(name))

    override fun openAlbum(id: String, name: String, artists: String, imageUrl: String?) =
        open(Route.Album(id), Peek(name, artists, imageUrl))

    /** Whether the devices sheet is open; any screen can open it. */
    val showDevices = mutableStateOf(false)

    override fun openDevices() {
        showDevices.value = true
    }

    /** Move playback to [device]; the phone's own entry brings it back here. */
    override fun transfer(device: Device) {
        if (device.isSelf) app.bridge.movedHere()
        command { core.transfer(device.id, true) }
        app.refreshRemoteSoon()
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
    app: NexfyApp,
    controller: MediaController?,
    openBrowser: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val nav = remember { NavState() }
    val scope = rememberCoroutineScope()
    val actions = remember { AppActions(app, nav, openBrowser, scope) }
    val snapshot by app.bridge.state.collectAsState()
    val haze = remember { HazeState() }

    ShimmerDriver()
    // Screens move through a seekable transition: navigation animates it,
    // the back gesture drags it (the page shrinks into a card over the one
    // underneath) and letting go finishes or cancels it.
    val target = nav.screen
    val screens = remember { SeekableTransitionState(target) }
    LaunchedEffect(target) { screens.animateTo(target) }
    MetricsState("screen", target.route?.key?.substringBefore(':') ?: "tab-${target.tab.name.lowercase()}")
    MetricsState("nav", if (screens.currentState != screens.targetState) "moving" else null)
    PredictiveBackHandler(enabled = nav.stack().isNotEmpty()) { events ->
        val parent = nav.parentScreen()
        try {
            events.collect { screens.seekTo(it.progress, parent) }
            nav.pop()
        } catch (e: CancellationException) {
            scope.launch { screens.animateTo(screens.currentState) }
            throw e
        }
    }
    BackHandler(enabled = nav.stack().isEmpty() && nav.tab != Tab.Home) { nav.tab = Tab.Home }

    val bottomSpace = if (snapshot.track != null) 176.dp else 104.dp
    CompositionLocalProvider(LocalHaze provides haze, LocalBottomSpace provides bottomSpace, dev.nexfy.app.ui.design.LocalPlaying provides snapshot.playing) {
        Box(Modifier.fillMaxSize().background(c.background)) {
            // Everything behind the dock and the mini player is what their glass blurs.
            Box(Modifier.fillMaxSize().glassSource(haze)) {
                SharedTransitionLayout(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalSharedScope provides this) {
                        rememberTransition(screens, label = "screen").AnimatedContent(
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                when {
                                    initialState.tab != targetState.tab ->
                                        Motion.tabSwitch(targetState.tab.ordinal - initialState.tab.ordinal)
                                    targetState.depth < initialState.depth -> Motion.pageBack
                                    else -> Motion.pageEnter togetherWith Motion.pageExit using SizeTransform(clip = false)
                                }
                            },
                        ) { screen ->
                            // Pages leaving or arriving show as rounded cards.
                            val corner by transition.animateDp(label = "corner") { if (it == EnterExitState.Visible) 0.dp else 32.dp }
                            CompositionLocalProvider(LocalScreenScope provides this) {
                                Box(
                                    Modifier.fillMaxSize().graphicsLayer {
                                        shape = RoundedCornerShape(corner)
                                        clip = corner > 0.5.dp
                                    }.background(c.background),
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
            NoticeBar(
                app.edits,
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 24.dp, end = 24.dp, bottom = bottomSpace - 16.dp),
            )
            if (actions.showDevices.value) DevicesSheet(snapshot, actions) { actions.showDevices.value = false }
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
        Route.Stats -> StatsScreen(actions, nav.list(route.key), modifier.safeDrawingPadding())
        Route.Friends -> FriendsScreen(actions, nav.list(route.key), modifier.safeDrawingPadding())
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
 * Floating glass dock. The highlight is a liquid pill: its leading edge
 * runs on a firm spring and the trailing one follows on a soft spring, so it
 * stretches on the way and settles with a slight bounce.
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
    Box(modifier) {
        // The blur node carries no shadow layer; the shadow is a sibling behind it.
        Box(Modifier.matchParentSize().shadow(18.dp, CircleShape, ambientColor = Color.Black.copy(.25f), spotColor = Color.Black.copy(.25f)))
        Row(
            Modifier.fillMaxWidth().liquidGlass(CircleShape, c.isDark).padding(5.dp)
                .drawBehind {
                    val w = size.width / tabs.size
                    val n = (tabs.size - 1).toFloat()
                    val l = minOf(lead.value, trail.value).coerceIn(0f, n)
                    val r = maxOf(lead.value, trail.value).coerceIn(0f, n)
                    val stretch = (r - l).coerceIn(0f, 1f)
                    val inset = 2.dp.toPx() * (1f - stretch)
                    val squash = size.height * .06f * stretch
                    drawRoundRect(
                        pill,
                        Offset(l * w + inset, squash),
                        Size((r - l + 1f) * w - 2 * inset, size.height - 2 * squash),
                        CornerRadius(size.height / 2f),
                    )
                },
        ) {
            tabs.forEach { t ->
                val on = t == tab
                val source = remember { MutableInteractionSource() }
                val tint by animateColorAsState(if (on) accent else c.onSurfaceVariant, Motion.standard(), label = "tint")
                val scale = animateFloatAsState(if (on) 1.1f else 1f, spring(.55f, 500f), label = "icon")
                Column(
                    Modifier.weight(1f).springPress(source).clip(CircleShape)
                        .clickable(source, null) {
                            if (!on) haptics.tick()
                            onSelect(t)
                        }
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        t.icon, null, tint = tint,
                        modifier = Modifier.size(23.dp).graphicsLayer {
                            val k = scale.value.coerceIn(.8f, 1.25f)
                            scaleX = k
                            scaleY = k
                        },
                    )
                    Text(t.label, color = tint, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Opens the Spotify link the app was started with (or handed while open):
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
                    android.widget.Toast.makeText(actions.app, "Não deu para abrir essa música", android.widget.Toast.LENGTH_SHORT).show()
                    return@LaunchedEffect
                }
                track.albumId?.let { actions.open(Route.Album(it), Peek(track.album, track.artists, track.imageUrl)) }
                actions.play(listOf(track))
            }
            is LinkTarget.Jam -> {
                runCatching { actions.core.jamJoin(target.link) }
                    .onFailure { android.widget.Toast.makeText(actions.app, "Não deu para entrar nessa Jam", android.widget.Toast.LENGTH_SHORT).show() }
                jamOpen = true
            }
            null -> android.widget.Toast.makeText(actions.app, "Esse link do Spotify não abre no Nexfy", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
