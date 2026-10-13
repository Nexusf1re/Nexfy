package dev.sonata.app.ui

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
import dev.sonata.app.SonataApp
import dev.sonata.app.ui.design.LocalBottomSpace
import dev.sonata.app.ui.design.LocalHaze
import dev.sonata.app.ui.design.Motion
import dev.sonata.app.ui.design.glassSource
import dev.sonata.app.ui.design.liquidGlass
import dev.sonata.app.ui.design.rememberHaptics
import dev.sonata.app.ui.design.springPress
import dev.sonata.app.ui.player.PlayerControls
import dev.sonata.app.ui.player.PlayerSheet
import dev.sonata.app.ui.screens.AlbumScreen
import dev.sonata.app.ui.screens.ArtistScreen
import dev.sonata.app.ui.screens.HomeScreen
import dev.sonata.app.ui.screens.LibraryScreen
import dev.sonata.app.ui.screens.LikedScreen
import dev.sonata.app.ui.screens.PlaylistScreen
import dev.sonata.app.ui.screens.SearchScreen
import dev.sonata.app.ui.screens.SettingsScreen
import dev.sonata.app.ui.theme.accentColor
import dev.sonata.app.ui.theme.isDark
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import uniffi.sonata_core.Lyrics
import uniffi.sonata_core.QueueEntry
import uniffi.sonata_core.RepeatMode
import uniffi.sonata_core.Track
import uniffi.sonata_core.TrackInfo

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Início", Icons.Rounded.Home),
    Search("Buscar", Icons.Rounded.Search),
    Library("Biblioteca", Icons.Rounded.LibraryMusic),
}

/** Destinations pushed on a tab's own back stack. */
sealed interface Route {
    val key: String

    data class Playlist(val id: String) : Route { override val key get() = "pl:$id" }
    data class Album(val id: String) : Route { override val key get() = "al:$id" }
    data class Artist(val id: String) : Route { override val key get() = "ar:$id" }
    data object Liked : Route { override val key get() = "liked" }
    data object Settings : Route { override val key get() = "settings" }
}

/** What the screen shows: a tab and the top of its stack (null = the tab root). */
data class Screen(val tab: Tab, val route: Route?)

/** Each tab keeps its own back stack and the scroll position of every screen. */
@Stable
class NavState {
    var tab by mutableStateOf(Tab.Home)
    private val stacks = mutableStateMapOf<Tab, List<Route>>()
    private val lists = HashMap<String, LazyListState>()

    fun stack(tab: Tab = this.tab) = stacks[tab].orEmpty()
    fun top(tab: Tab = this.tab) = stack(tab).lastOrNull()
    val screen get() = Screen(tab, top())

    fun push(route: Route) {
        if (top() != route) stacks[tab] = stack() + route
    }

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
    val app: SonataApp,
    val nav: NavState,
    val openBrowser: (String) -> Unit,
    private val scope: CoroutineScope,
) : PlayerControls {
    val core get() = app.core

    fun open(route: Route) = nav.push(route)
    fun back() = nav.pop()

    /** Run a playback command off the main thread, reporting failures. */
    private fun command(action: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                action()
            } catch (e: Exception) {
                app.bridge.reportError(e.message ?: e.toString())
            }
        }
    }

    /**
     * Play [tracks] from [index]. With a [contextUri] (a whole playlist,
     * album or liked songs, unfiltered) Spotify plays the context itself:
     * its queue, shuffle and autoplay after the end. Otherwise exactly
     * these tracks play. [source] is the library key counted for the
     * "most played" order.
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
        command {
            if (contextUri != null) {
                core.playContext(contextUri, tracks[start].uri.takeUnless { shuffle }, shuffle)
            } else {
                core.playTracks(tracks.map { it.uri }, start.toUInt(), shuffle)
            }
        }
    }

    /** Add a song to Spotify's queue; it plays after the current one. */
    fun addToQueue(track: Track) {
        command { core.addToQueue(track.uri) }
        android.widget.Toast.makeText(app, "Adicionada à fila: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun playPause() = command {
        if (app.bridge.state.value.playing) core.pause() else core.resume()
    }

    override fun next() {
        app.bridge.preview(forward = true)
        command { core.next() }
    }

    override fun previous() {
        app.bridge.preview(forward = false)
        command { core.previous() }
    }

    override fun seek(positionMs: Long) = command { core.seek(positionMs.coerceAtLeast(0).toUInt()) }

    override fun setShuffle(on: Boolean) = command { core.setShuffle(on) }

    override fun setRepeat(mode: RepeatMode) = command { core.setRepeat(mode) }

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
}

@Composable
fun SonataRoot(
    app: SonataApp,
    controller: MediaController?,
    openBrowser: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val nav = remember { NavState() }
    val scope = rememberCoroutineScope()
    val actions = remember { AppActions(app, nav, openBrowser, scope) }
    val snapshot by app.bridge.state.collectAsState()
    val haze = remember { HazeState() }

    BackHandler(enabled = nav.stack().isNotEmpty()) { nav.pop() }
    BackHandler(enabled = nav.stack().isEmpty() && nav.tab != Tab.Home) { nav.tab = Tab.Home }

    val bottomSpace = if (snapshot.track != null) 176.dp else 104.dp
    CompositionLocalProvider(LocalHaze provides haze, LocalBottomSpace provides bottomSpace) {
        Box(Modifier.fillMaxSize().background(c.background)) {
            // Everything behind the dock and the mini player is what their glass blurs.
            Box(Modifier.fillMaxSize().glassSource(haze)) {
                AnimatedContent(
                    targetState = nav.screen,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        if (initialState.tab != targetState.tab) {
                            Motion.tabSwitch(targetState.tab.ordinal - initialState.tab.ordinal)
                        } else {
                            Motion.pageEnter togetherWith Motion.pageExit using SizeTransform(clip = false)
                        }
                    },
                    label = "screen",
                ) { screen ->
                    ScreenContent(screen, actions, Modifier.fillMaxSize())
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
        Route.Liked -> LikedScreen(actions, nav.list(route.key), modifier)
        Route.Settings -> SettingsScreen(actions, modifier.safeDrawingPadding())
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
