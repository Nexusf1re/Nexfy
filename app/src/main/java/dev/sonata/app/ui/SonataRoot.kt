package dev.sonata.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import dev.sonata.app.SonataApp
import dev.sonata.app.playback.PlaybackSnapshot
import dev.sonata.app.ui.screens.AlbumScreen
import dev.sonata.app.ui.screens.ArtistScreen
import dev.sonata.app.ui.screens.LibraryScreen
import dev.sonata.app.ui.screens.LikedScreen
import dev.sonata.app.ui.screens.PlaylistScreen
import dev.sonata.app.ui.screens.SearchScreen
import dev.sonata.app.ui.screens.SettingsScreen
import kotlinx.coroutines.delay
import uniffi.sonata_core.Track

sealed interface Screen {
    data object Library : Screen
    data object Search : Screen
    data object Settings : Screen
    data object Liked : Screen
    data class Playlist(val id: String) : Screen
    data class Album(val id: String) : Screen
    data class Artist(val id: String) : Screen
}

/** What every screen needs: the core, playback and navigation. */
class AppActions(
    val app: SonataApp,
    private val controller: () -> MediaController?,
    val open: (Screen) -> Unit,
    val openBrowser: (String) -> Unit,
) {
    val core get() = app.core

    /** Play [tracks] as the queue, starting at [index]. */
    fun play(tracks: List<Track>, index: Int) {
        val items = tracks.map { MediaItem.Builder().setMediaId(it.uri).build() }
        controller()?.run {
            setMediaItems(items, index, 0)
            prepare()
            play()
        }
    }
}

private val TABS = listOf(
    Triple(Screen.Library, "Biblioteca", "♫"),
    Triple(Screen.Search, "Buscar", "⌕"),
    Triple(Screen.Settings, "Ajustes", "⚙"),
)

@Composable
fun SonataRoot(
    app: SonataApp,
    controller: MediaController?,
    openBrowser: (String) -> Unit,
) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Library) }
    val actions = remember(controller) {
        AppActions(app, { controller }, { stack.add(it) }, openBrowser)
    }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    Scaffold(
        bottomBar = {
            Column {
                MiniPlayer(app, controller)
                NavigationBar {
                    TABS.forEach { (screen, label, glyph) ->
                        NavigationBarItem(
                            selected = stack.first() == screen,
                            onClick = { stack.clear(); stack.add(screen) },
                            icon = { Text(glyph, style = MaterialTheme.typography.titleLarge) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (val screen = stack.last()) {
                Screen.Library -> LibraryScreen(actions)
                Screen.Search -> SearchScreen(actions)
                Screen.Settings -> SettingsScreen(actions)
                Screen.Liked -> LikedScreen(actions)
                is Screen.Playlist -> PlaylistScreen(actions, screen.id)
                is Screen.Album -> AlbumScreen(actions, screen.id)
                is Screen.Artist -> ArtistScreen(actions, screen.id)
            }
        }
    }
}

@Composable
private fun MiniPlayer(app: SonataApp, controller: MediaController?) {
    val snapshot by app.bridge.state.collectAsState()
    val track = snapshot.track ?: return
    Surface(tonalElevation = 3.dp) {
        Column {
            Progress(snapshot)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Cover(track.coverUrl, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        track.artists,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { controller?.seekToPrevious() }) { Text("⏮") }
                TextButton(onClick = {
                    controller?.run { if (snapshot.playing) pause() else play() }
                }) { Text(if (snapshot.playing) "⏸" else "▶") }
                TextButton(onClick = { controller?.seekToNext() }) { Text("⏭") }
            }
        }
    }
}

@Composable
private fun Progress(snapshot: PlaybackSnapshot) {
    val duration = snapshot.track?.durationMs?.toLong()?.coerceAtLeast(1) ?: 1
    var fraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(snapshot) {
        while (true) {
            fraction = (snapshot.currentPositionMs().toFloat() / duration).coerceIn(0f, 1f)
            if (!snapshot.playing) break
            delay(500)
        }
    }
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
}
