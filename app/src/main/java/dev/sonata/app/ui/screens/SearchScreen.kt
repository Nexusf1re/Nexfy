package dev.sonata.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sonata.app.ui.AppActions
import dev.sonata.app.ui.MediaRow
import dev.sonata.app.ui.Screen
import dev.sonata.app.ui.SectionTitle
import dev.sonata.app.ui.TrackRow
import kotlinx.coroutines.delay
import uniffi.sonata_core.SearchResults

@Composable
fun SearchScreen(actions: AppActions) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Search once typing pauses.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(400)
        loading = true
        try {
            results = actions.core.search(query)
            error = null
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Músicas, artistas, álbuns, playlists") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text("Erro: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }

        val r = results ?: return@Column
        LazyColumn {
            if (r.tracks.isNotEmpty()) item { SectionTitle("Músicas") }
            itemsIndexed(r.tracks) { index, track -> TrackRow(track, { actions.play(r.tracks, index) }) }

            if (r.artists.isNotEmpty()) item { SectionTitle("Artistas") }
            items(r.artists, key = { "ar" + it.id }) { artist ->
                MediaRow(artist.name, "Artista", artist.imageUrl, { actions.open(Screen.Artist(artist.id)) }, round = true)
            }

            if (r.albums.isNotEmpty()) item { SectionTitle("Álbuns") }
            items(r.albums, key = { "al" + it.id }) { album ->
                MediaRow(album.name, "${album.artists} · ${album.year ?: ""}", album.imageUrl, {
                    actions.open(Screen.Album(album.id))
                })
            }

            if (r.playlists.isNotEmpty()) item { SectionTitle("Playlists") }
            items(r.playlists, key = { "pl" + it.id }) { playlist ->
                MediaRow(playlist.name, playlist.owner, playlist.imageUrl, {
                    actions.open(Screen.Playlist(playlist.id))
                })
            }
        }
    }
}
