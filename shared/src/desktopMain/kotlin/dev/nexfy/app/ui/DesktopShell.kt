package dev.nexfy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.NexfyServices
import dev.nexfy.app.elapsedRealtimeMs
import dev.nexfy.app.playback.PlaybackSnapshot
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.design.GlassBackdrop
import dev.nexfy.app.ui.design.GlassLevel
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.LocalHaze
import dev.nexfy.app.ui.design.LocalPlaying
import dev.nexfy.app.ui.design.PlayingBars
import dev.nexfy.app.ui.design.ShimmerDriver
import dev.nexfy.app.ui.design.ThinSlider
import dev.nexfy.app.ui.design.durationLabel
import dev.nexfy.app.ui.design.glassSource
import dev.nexfy.app.ui.design.liquidGlass
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.player.DevicesSheet
import dev.nexfy.app.ui.player.PlayPauseGlyph
import dev.nexfy.app.ui.player.TrackActionsSheet
import dev.nexfy.app.ui.theme.Hairline
import dev.nexfy.app.ui.theme.accentColor
import dev.nexfy.app.ui.theme.isDark
import kotlinx.coroutines.launch
import uniffi.nexfy_core.RepeatMode
import uniffi.nexfy_core.Track

/** How tall the playing bar is, and how much room the pages leave under them for it. */
private val BarHeight = 84.dp
private val SidebarWidth = 268.dp
private val PanelWidth = 332.dp

/**
 * The app on a computer: a column of the library on the left, the page in
 * the middle, what is playing along the bottom, and the song's own panel on
 * the right when it is asked for. The arrangement is the one every desktop
 * music app has, so nothing has to be learnt; the material is Nexfy's, so
 * the glass bar refracts the page scrolling behind it.
 *
 * The phone's [NexfyRoot] shows the same screens under a tab dock and a
 * player that pulls up, which is right for a thumb and wrong for a mouse.
 */
@Composable
fun NexfyDesktop(services: NexfyServices, openBrowser: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    val dark = c.isDark
    val nav = remember { NavState() }
    val scope = rememberCoroutineScope()
    val actions = remember { AppActions(services, nav, openBrowser, scope) }
    val snapshot by services.bridge.state.collectAsState()
    val backdrop = remember { GlassBackdrop() }
    var panelOpen by remember { mutableStateOf(false) }

    ShimmerDriver()
    // Esc goes back a page, then out of a tab, as the phone's back does.
    BackGesture(enabled = nav.stack().isNotEmpty()) { nav.pop() }
    BackGesture(enabled = nav.stack().isEmpty() && nav.tab != Tab.Home) { nav.tab = Tab.Home }

    CompositionLocalProvider(
        LocalHaze provides backdrop,
        LocalBottomSpace provides BarHeight,
        LocalPlaying provides snapshot.playing,
    ) {
        Box(Modifier.fillMaxSize().background(c.background)) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(actions, Modifier.width(SidebarWidth).fillMaxHeight())
                Page(actions, Modifier.weight(1f).fillMaxHeight().glassSource(backdrop))
                if (panelOpen) {
                    SongPanel(snapshot, actions, Modifier.width(PanelWidth).fillMaxHeight())
                }
            }
            PlayingBar(
                snapshot, actions,
                panelOpen = panelOpen,
                onPanel = { panelOpen = !panelOpen },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            NoticeBar(
                services.edits,
                Modifier.align(Alignment.BottomCenter).padding(bottom = BarHeight + 18.dp, start = 24.dp, end = 24.dp),
            )
            if (actions.showDevices.value) {
                DevicesSheet(snapshot, actions) { actions.showDevices.value = false }
            }
            LinkOpener(actions)
        }
    }
}

// ------------------------------------------------------------------ sidebar

/** The wordmark, where to go, and the library itself: one standing panel. */
@Composable
private fun Sidebar(actions: AppActions, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val nav = actions.nav
    Column(modifier.padding(start = 12.dp, top = 12.dp, end = 6.dp, bottom = BarHeight + 16.dp)) {
        Panel {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "nexfy", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge, letterSpacing = (-1).sp,
                )
                NavIcon(Icons.Rounded.Settings, "Ajustes") { actions.open(Route.Settings) }
            }
            NavRow(Icons.Rounded.Home, "Início", nav.tab == Tab.Home && nav.stack().isEmpty()) {
                nav.tab = Tab.Home
                nav.popToRoot()
            }
            NavRow(Icons.Rounded.Search, "Buscar", nav.tab == Tab.Search && nav.stack().isEmpty()) {
                nav.tab = Tab.Search
                nav.popToRoot()
            }
            NavRow(Icons.Rounded.LibraryMusic, "Biblioteca", nav.tab == Tab.Library && nav.stack().isEmpty()) {
                nav.tab = Tab.Library
                nav.popToRoot()
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(12.dp))
        Panel(Modifier.weight(1f)) { LibraryList(actions) }
    }
}

/** A panel of the shell: the page's own surface, framed by a hairline. */
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.surface)
            .border(Hairline, if (c.isDark) Color.White.copy(.07f) else c.outline, shape),
    ) { content() }
}

@Composable
private fun NavRow(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val accent = accentColor()
    val tint = if (selected) accent else c.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(.12f) else Color.Transparent)
            .clickable { haptics.tick(); onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            label, Modifier.padding(start = 14.dp),
            color = if (selected) c.onSurface else c.onSurfaceVariant,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun NavIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    Icon(
        icon, description, tint = c.onSurfaceVariant,
        modifier = Modifier.clip(CircleShape).clickable { haptics.tick(); onClick() }.padding(8.dp).size(20.dp),
    )
}

/** The user's playlists and saved albums, the way the library page reads them. */
@Composable
private fun LibraryList(actions: AppActions) {
    val c = MaterialTheme.colorScheme
    val version by actions.services.edits.version.collectAsState()
    val playlists = rememberLoaded("side-pl", reloadOn = version) { actions.core.myPlaylists(it) }
    val albums = rememberLoaded("side-al", reloadOn = version) { actions.core.savedAlbums(it) }
    val list = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), list, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)) {
        item {
            Text(
                "Sua biblioteca",
                Modifier.padding(start = 20.dp, top = 18.dp, bottom = 10.dp),
                color = c.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            ShelfRow(null, "Curtidas", "Músicas", Icons.Rounded.Favorite) { actions.open(Route.Liked) }
        }
        items(playlists.state.value.orEmpty(), key = { "pl:${it.id}" }) { p ->
            ShelfRow(p.imageUrl, p.name, "Playlist · ${p.owner}") {
                actions.open(Route.Playlist(p.id), Peek(p.name, p.owner, p.imageUrl))
            }
        }
        items(albums.state.value.orEmpty(), key = { "al:${it.id}" }) { a ->
            ShelfRow(a.imageUrl, a.name, "Álbum · ${a.artists}") {
                actions.open(Route.Album(a.id), Peek(a.name, a.artists, a.imageUrl))
            }
        }
    }
}

@Composable
private fun ShelfRow(art: String?, title: String, subtitle: String, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp)).clickable { haptics.tick(); onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(accentColor().copy(.18f)),
                Alignment.Center,
            ) { Icon(icon, null, tint = accentColor(), modifier = Modifier.size(20.dp)) }
        } else {
            Cover(art, Modifier.size(42.dp), radius = 10.dp)
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, color = c.onSurface, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = c.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// --------------------------------------------------------------------- page

/** The page itself, with the way back above it when there is one. */
@Composable
private fun Page(actions: AppActions, modifier: Modifier) {
    val nav = actions.nav
    val screen = nav.screen
    Column(modifier.padding(start = 6.dp, top = 12.dp, end = 12.dp)) {
        Panel(Modifier.weight(1f)) {
            if (nav.stack().isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NavIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") { nav.pop() }
                }
            }
            ScreenContent(screen, actions, Modifier.fillMaxSize())
        }
    }
}

// -------------------------------------------------------------- playing bar

/**
 * What is playing, along the bottom: the song on the left, the transport
 * and the line in the middle, the devices and the volume on the right. It
 * is liquid glass over the page, so the list scrolling behind it shows
 * through, blurred and bent at the rim.
 */
@Composable
private fun PlayingBar(
    s: PlaybackSnapshot,
    actions: AppActions,
    panelOpen: Boolean,
    onPanel: () -> Unit,
    modifier: Modifier,
) {
    val c = MaterialTheme.colorScheme
    val dark = c.isDark
    val track = s.track
    val shape = RoundedCornerShape(22.dp)
    var menu by remember { mutableStateOf(false) }
    if (menu && track != null) {
        TrackActionsSheet(track, actions, onNavigate = { menu = false }) { menu = false }
    }
    Row(
        modifier.height(BarHeight).liquidGlass(shape, dark, GlassLevel.Strong)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The song.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Cover(track?.coverUrl, Modifier.size(52.dp), radius = 12.dp)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    track?.title ?: "Nada tocando", color = c.onSurface, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track?.artists.orEmpty(), color = c.onSurfaceVariant, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (track != null) {
                NavIcon(Icons.Rounded.MoreHoriz, "Mais opções") { menu = true }
            }
        }
        // The transport and the line.
        Column(
            Modifier.weight(1.4f).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Transport(s, actions)
            Spacer(Modifier.height(4.dp))
            SeekLine(s, actions)
        }
        // Where it plays, and how loud.
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (s.playing) PlayingBars(accentColor(), Modifier.size(16.dp).padding(end = 2.dp))
            NavIcon(Icons.AutoMirrored.Rounded.QueueMusic, if (panelOpen) "Fechar a fila" else "Fila e letra", onPanel)
            NavIcon(Icons.Rounded.Devices, "Dispositivos") { actions.openDevices() }
            Volume(s, actions)
        }
    }
}

@Composable
private fun Transport(s: PlaybackSnapshot, actions: AppActions) {
    val c = MaterialTheme.colorScheme
    val accent = accentColor()
    val haptics = rememberHaptics()
    val shuffle = s.queue.shuffle
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            Icons.Rounded.Shuffle, if (shuffle) "Desligar aleatório" else "Aleatório",
            tint = if (shuffle) accent else c.onSurfaceVariant,
            modifier = Modifier.clip(CircleShape).clickable { haptics.tick(); actions.setShuffle(!shuffle) }
                .padding(7.dp).size(18.dp),
        )
        NavIcon(Icons.Rounded.SkipPrevious, "Anterior") { actions.previous() }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(c.onSurface)
                .clickable { haptics.press(); actions.playPause() },
            Alignment.Center,
        ) { PlayPauseGlyph(s.playing, c.surface, Modifier.size(18.dp)) }
        NavIcon(Icons.Rounded.SkipNext, "Próxima") { actions.next() }
        val repeat = s.queue.repeat
        Icon(
            if (repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            "Repetir",
            tint = if (repeat == RepeatMode.OFF) c.onSurfaceVariant else accent,
            modifier = Modifier.clip(CircleShape).clickable {
                haptics.tick()
                actions.setRepeat(
                    when (repeat) {
                        RepeatMode.OFF -> RepeatMode.ALL
                        RepeatMode.ALL -> RepeatMode.ONE
                        RepeatMode.ONE -> RepeatMode.OFF
                    },
                )
            }.padding(7.dp).size(18.dp),
        )
    }
}

/** The line, with where the song is and how long it runs either side of it. */
@Composable
private fun SeekLine(s: PlaybackSnapshot, actions: AppActions) {
    val c = MaterialTheme.colorScheme
    val duration = s.track?.durationMs?.toLong() ?: 0L
    // Dragged, the head follows the mouse and the clock with it; let go, the
    // player takes over again.
    var dragging by remember { mutableStateOf(false) }
    var held by remember { mutableFloatStateOf(0f) }
    val position by produceState(0L, s) {
        while (true) {
            withFrameMillis { }
            value = s.currentPositionMs()
        }
    }
    val shown = if (dragging) (held * duration).toLong() else position.coerceIn(0L, duration.coerceAtLeast(0L))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(durationLabel(shown), color = c.onSurfaceVariant, fontSize = 11.sp)
        ThinSlider(
            progress = { if (duration <= 0) 0f else (shown.toFloat() / duration).coerceIn(0f, 1f) },
            onChange = { dragging = true; held = it },
            onDone = {
                dragging = false
                if (duration > 0) actions.seek((held * duration).toLong())
            },
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            enabled = s.track != null,
            waiting = { s.loading },
            lit = s.playing,
        )
        Text(durationLabel(duration), color = c.onSurfaceVariant, fontSize = 11.sp)
    }
}

/** Only the device playing elsewhere takes a volume; this machine's is the system's. */
@Composable
private fun Volume(s: PlaybackSnapshot, actions: AppActions) {
    val c = MaterialTheme.colorScheme
    val remote = s.remote ?: return
    var held by remember(remote.id) { mutableFloatStateOf((remote.volume?.toInt() ?: 50) / 100f) }
    Row(Modifier.width(140.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.VolumeUp, null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
        ThinSlider(
            progress = { held },
            onChange = { held = it },
            onDone = { actions.setVolume((held * 100).toInt()) },
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}

// -------------------------------------------------------------- song panel

/** The song's own column: its art large, and what plays after it. */
@Composable
private fun SongPanel(s: PlaybackSnapshot, actions: AppActions, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val track = s.track
    val next = s.queue.next
    val uris = remember(next) { next.take(30).map { it.uri } }
    val songs by produceState(emptyList<Track>(), uris) {
        value = if (uris.isEmpty()) emptyList() else runCatching { actions.core.tracksInfo(uris) }.getOrDefault(emptyList())
    }
    Column(modifier.padding(start = 6.dp, top = 12.dp, end = 12.dp, bottom = BarHeight + 16.dp)) {
        Panel(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                item {
                    Cover(track?.coverUrl, Modifier.fillMaxWidth(), radius = 16.dp)
                    Text(
                        track?.title ?: "Nada tocando",
                        Modifier.padding(top = 14.dp),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track?.artists.orEmpty(), color = c.onSurfaceVariant, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "A seguir", Modifier.padding(top = 22.dp, bottom = 6.dp),
                        color = c.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                itemsIndexed(songs) { i, song ->
                    ShelfRow(song.imageUrl, song.title, song.artists) {
                        next.getOrNull(i)?.let { actions.skipTo(it, i) }
                    }
                }
                if (songs.isEmpty()) {
                    item {
                        Text("A fila está vazia.", color = c.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
