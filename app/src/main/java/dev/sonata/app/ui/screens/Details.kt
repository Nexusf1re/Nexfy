package dev.sonata.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sonata.app.ui.AppActions
import dev.sonata.app.ui.LoadedContent
import dev.sonata.app.ui.Route
import dev.sonata.app.ui.design.Carousel
import dev.sonata.app.ui.design.CoverCard
import dev.sonata.app.ui.design.SectionTitle
import dev.sonata.app.ui.rememberLoaded
import uniffi.sonata_core.Track

private fun minutes(tracks: List<Track>): String {
    val total = tracks.sumOf { it.durationMs.toLong() } / 60_000
    return if (total > 0) "$total min" else ""
}

private fun meta(vararg parts: String) = parts.filter { it.isNotBlank() }.joinToString(" · ")

private fun count(n: Int) = "$n ${if (n == 1) "faixa" else "faixas"}"

@Composable
fun PlaylistScreen(actions: AppActions, id: String, list: LazyListState, modifier: Modifier) {
    val detail = rememberLoaded(id) { refresh -> actions.core.playlist(id, refresh) }
    LoadedContent(detail) { d ->
        CollectionScreen(
            actions, list,
            title = d.playlist.name,
            subtitle = d.playlist.owner,
            meta = meta("Playlist", count(d.tracks.size), minutes(d.tracks)),
            imageUrl = d.playlist.imageUrl,
            tracks = d.tracks,
            modifier = modifier,
        )
    }
}

@Composable
fun AlbumScreen(actions: AppActions, id: String, list: LazyListState, modifier: Modifier) {
    val detail = rememberLoaded(id) { refresh -> actions.core.album(id, refresh) }
    LoadedContent(detail) { d ->
        val artistId = d.tracks.firstOrNull()?.artistIds?.firstOrNull()
        CollectionScreen(
            actions, list,
            title = d.album.name,
            subtitle = d.album.artists,
            meta = meta("Álbum", d.album.year.orEmpty(), count(d.tracks.size), minutes(d.tracks)),
            imageUrl = d.album.imageUrl,
            tracks = d.tracks,
            modifier = modifier,
            numbered = true,
            onSubtitle = artistId?.let { { actions.open(Route.Artist(it)) } },
        )
    }
}

@Composable
fun ArtistScreen(actions: AppActions, id: String, list: LazyListState, modifier: Modifier) {
    val detail = rememberLoaded(id) { refresh -> actions.core.artist(id, refresh) }
    LoadedContent(detail) { d ->
        CollectionScreen(
            actions, list,
            title = d.artist.name,
            subtitle = null,
            meta = if (d.artist.followers > 0u) "%,d seguidores".format(d.artist.followers.toLong()) else "Artista",
            imageUrl = d.artist.imageUrl,
            tracks = d.topTracks,
            modifier = modifier,
            round = true,
        ) {
            if (d.albums.isNotEmpty()) {
                item(key = "discography") {
                    Box(Modifier.padding(horizontal = 24.dp)) {
                        androidx.compose.foundation.layout.Column {
                            SectionTitle("Discografia")
                            Carousel(actions.nav.list("row:ar-$id")) {
                                items(d.albums, key = { it.id }) { album ->
                                    CoverCard(album.imageUrl, album.name, album.year.orEmpty(), "al-${album.id}") {
                                        actions.open(Route.Album(album.id))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Liked songs, 50 at a time; the next page loads as the list nears its end. */
@Composable
fun LikedScreen(actions: AppActions, list: LazyListState, modifier: Modifier) {
    val tracks = remember { mutableStateListOf<Track>() }
    var nextOffset by remember { mutableStateOf<UInt?>(0u) }
    var total by rememberSaveable { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }

    val nearEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 12
        }
    }
    LaunchedEffect(nearEnd, nextOffset) {
        val offset = nextOffset ?: return@LaunchedEffect
        if (!nearEnd && tracks.isNotEmpty() || loading) return@LaunchedEffect
        loading = true
        try {
            val page = actions.core.likedTracks(offset, 50u, false)
            tracks.addAll(page.tracks)
            total = page.total.toInt()
            nextOffset = page.nextOffset
        } catch (_: Exception) {
            nextOffset = null
        } finally {
            loading = false
        }
    }

    CollectionScreen(
        actions, list,
        title = "Músicas curtidas",
        subtitle = null,
        meta = meta("Playlist", count(total)),
        imageUrl = tracks.firstOrNull()?.imageUrl,
        tracks = tracks,
        modifier = modifier,
    ) {
        if (loading) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
