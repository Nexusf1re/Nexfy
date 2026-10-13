package dev.sonata.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sonata.app.ui.AppActions
import dev.sonata.app.ui.Cover
import dev.sonata.app.ui.LoadedContent
import dev.sonata.app.ui.MediaRow
import dev.sonata.app.ui.Screen
import dev.sonata.app.ui.SectionTitle
import dev.sonata.app.ui.TrackRow
import dev.sonata.app.ui.rememberLoaded
import kotlinx.coroutines.launch
import uniffi.sonata_core.Track

@Composable
fun LibraryScreen(actions: AppActions) {
    var showAlbums by remember { mutableStateOf(false) }
    val playlists = rememberLoaded("playlists") { refresh -> actions.core.myPlaylists(refresh) }
    val albums = rememberLoaded("albums", showAlbums) { refresh ->
        if (showAlbums) actions.core.savedAlbums(refresh) else emptyList()
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Biblioteca", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            FilterChip(selected = !showAlbums, onClick = { showAlbums = false }, label = { Text("Playlists") })
            FilterChip(selected = showAlbums, onClick = { showAlbums = true }, label = { Text("Álbuns") })
        }
        if (showAlbums) {
            LoadedContent(albums) { list ->
                LazyColumn {
                    items(list, key = { it.id }) { album ->
                        MediaRow(album.name, "${album.artists} · ${album.year ?: ""}", album.imageUrl, {
                            actions.open(Screen.Album(album.id))
                        })
                    }
                }
            }
        } else {
            LoadedContent(playlists) { list ->
                LazyColumn {
                    item {
                        MediaRow("Músicas curtidas", "Tuas faixas salvas", null, { actions.open(Screen.Liked) })
                    }
                    items(list, key = { it.id }) { playlist ->
                        MediaRow(
                            playlist.name,
                            "${playlist.owner} · ${playlist.trackCount} faixas",
                            playlist.imageUrl,
                            { actions.open(Screen.Playlist(playlist.id)) },
                        )
                    }
                }
            }
        }
    }
}

/** Header with cover, title, subtitle and play/shuffle buttons. */
@Composable
private fun Header(title: String, subtitle: String, imageUrl: String?, tracks: List<Track>, actions: AppActions, round: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Cover(imageUrl, 200.dp, round)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = tracks.isNotEmpty(), onClick = { actions.play(tracks, 0) }) { Text("Tocar") }
            OutlinedButton(enabled = tracks.isNotEmpty(), onClick = {
                actions.play(tracks.shuffled(), 0)
            }) { Text("Aleatório") }
        }
    }
}

@Composable
fun PlaylistScreen(actions: AppActions, id: String) {
    val detail = rememberLoaded(id) { refresh -> actions.core.playlist(id, refresh) }
    LoadedContent(detail) { d ->
        LazyColumn {
            item {
                Header(d.playlist.name, "${d.playlist.owner} · ${d.tracks.size} faixas", d.playlist.imageUrl, d.tracks, actions)
            }
            itemsIndexed(d.tracks) { index, track ->
                TrackRow(track, { actions.play(d.tracks, index) })
            }
        }
    }
}

@Composable
fun AlbumScreen(actions: AppActions, id: String) {
    val detail = rememberLoaded(id) { refresh -> actions.core.album(id, refresh) }
    LoadedContent(detail) { d ->
        LazyColumn {
            item {
                Header(d.album.name, "${d.album.artists} · ${d.album.year ?: ""}", d.album.imageUrl, d.tracks, actions)
            }
            itemsIndexed(d.tracks) { index, track ->
                TrackRow(track, { actions.play(d.tracks, index) }, showCover = false)
            }
        }
    }
}

@Composable
fun ArtistScreen(actions: AppActions, id: String) {
    val detail = rememberLoaded(id) { refresh -> actions.core.artist(id, refresh) }
    LoadedContent(detail) { d ->
        LazyColumn {
            item {
                Header(d.artist.name, "%,d seguidores".format(d.artist.followers.toLong()), d.artist.imageUrl, d.topTracks, actions, round = true)
            }
            item { SectionTitle("Populares") }
            itemsIndexed(d.topTracks) { index, track ->
                TrackRow(track, { actions.play(d.topTracks, index) })
            }
            item { SectionTitle("Discografia") }
            items(d.albums, key = { it.id }) { album ->
                MediaRow(album.name, album.year ?: "", album.imageUrl, { actions.open(Screen.Album(album.id)) })
            }
        }
    }
}

/** Liked songs load 50 at a time; the next page loads near the end of the list. */
@Composable
fun LikedScreen(actions: AppActions) {
    val scope = rememberCoroutineScope()
    val tracks = remember { mutableStateListOf<Track>() }
    var nextOffset by remember { mutableStateOf<UInt?>(0u) }
    var total by remember { mutableStateOf(0u) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadMore() {
        val offset = nextOffset ?: return
        if (loading) return
        loading = true
        scope.launch {
            try {
                val page = actions.core.likedTracks(offset, 50u, false)
                tracks.addAll(page.tracks)
                total = page.total
                nextOffset = page.nextOffset
                error = null
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { loadMore() }

    LazyColumn {
        item {
            Header("Músicas curtidas", "$total faixas", null, tracks, actions)
            error?.let { Text("Erro: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        }
        itemsIndexed(tracks) { index, track ->
            if (index >= tracks.size - 10) LaunchedEffect(tracks.size) { loadMore() }
            TrackRow(track, { actions.play(tracks.toList(), index) })
        }
    }
}
