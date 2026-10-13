package dev.nexfy.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.Loaded
import dev.nexfy.app.ui.Peek
import dev.nexfy.app.ui.Route
import dev.nexfy.app.ui.design.Carousel
import dev.nexfy.app.ui.design.CoverCard
import dev.nexfy.app.ui.design.SectionTitle
import dev.nexfy.app.ui.design.TrackRowSkeleton
import dev.nexfy.app.ui.design.skeletonFade
import dev.nexfy.app.ui.rememberLoaded
import uniffi.nexfy_core.Track

private fun minutes(tracks: List<Track>): String {
    val total = tracks.sumOf { it.durationMs.toLong() } / 60_000
    return if (total > 0) "$total min" else ""
}

private fun meta(vararg parts: String) = parts.filter { it.isNotBlank() }.joinToString(" · ")

private fun count(n: Int) = "$n ${if (n == 1) "faixa" else "faixas"}"

@Composable
fun PlaylistScreen(actions: AppActions, id: String, list: LazyListState, modifier: Modifier) {
    val detail = rememberLoaded(id) { refresh -> actions.core.playlist(id, refresh) }
    val d = detail.state.value
    val peek = actions.nav.peek("pl:$id")
    CollectionScreen(
        actions, list,
        title = d?.playlist?.name ?: peek?.title.orEmpty(),
        subtitle = d?.playlist?.owner ?: peek?.subtitle,
        meta = d?.let { meta("Playlist", count(it.tracks.size), minutes(it.tracks)) }.orEmpty(),
        imageUrl = d?.playlist?.imageUrl ?: peek?.imageUrl,
        tracks = d?.tracks,
        modifier = modifier,
        error = detail.state.failure(),
        onRetry = detail.refresh,
        source = "pl:$id",
        contextUri = "spotify:playlist:$id",
    )
}

@Composable
fun AlbumScreen(actions: AppActions, id: String, list: LazyListState, modifier: Modifier) {
    val detail = rememberLoaded(id) { refresh -> actions.core.album(id, refresh) }
    val d = detail.state.value
    val peek = actions.nav.peek("al:$id")
    val artistId = d?.tracks?.firstOrNull()?.artistIds?.firstOrNull()
    CollectionScreen(
        actions, list,
        title = d?.album?.name ?: peek?.title.orEmpty(),
        subtitle = d?.album?.artists ?: peek?.subtitle,
        meta = d?.let { meta("Álbum", it.album.year.orEmpty(), count(it.tracks.size), minutes(it.tracks)) }.orEmpty(),
        imageUrl = d?.album?.imageUrl ?: peek?.imageUrl,
        tracks = d?.tracks,
        modifier = modifier,
        error = detail.state.failure(),
        onRetry = detail.refresh,
        numbered = true,
        source = "al:$id",
        contextUri = "spotify:album:$id",
        onSubtitle = artistId?.let { { actions.open(Route.Artist(it), Peek(d?.album?.artists.orEmpty().substringBefore(", "))) } },
    )
}

@Composable
fun ArtistScreen(actions: AppActions, id: String, list: LazyListState, modifier: Modifier) {
    val detail = rememberLoaded(id) { refresh -> actions.core.artist(id, refresh) }
    val d = detail.state.value
    val peek = actions.nav.peek("ar:$id")
    CollectionScreen(
        actions, list,
        title = d?.artist?.name ?: peek?.title.orEmpty(),
        subtitle = null,
        meta = when {
            d == null -> ""
            d.artist.followers > 0u -> "%,d seguidores".format(d.artist.followers.toLong())
            else -> "Artista"
        },
        imageUrl = d?.artist?.imageUrl ?: peek?.imageUrl,
        tracks = d?.topTracks,
        modifier = modifier,
        error = detail.state.failure(),
        onRetry = detail.refresh,
        round = true,
        source = "ar:$id",
    ) {
        if (d != null && d.albums.isNotEmpty()) {
            item(key = "discography") {
                Box(Modifier.padding(horizontal = 24.dp)) {
                    androidx.compose.foundation.layout.Column {
                        SectionTitle("Discografia")
                        Carousel(actions.nav.list("row:ar-$id")) {
                            items(d.albums, key = { it.id }) { album ->
                                CoverCard(album.imageUrl, album.name, album.year.orEmpty(), "al-${album.id}") {
                                    actions.open(Route.Album(album.id), Peek(album.name, d.artist.name, album.imageUrl))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The error to show in place of the rows: only once loading ended with nothing to show. */
private fun Loaded<*>.failure(): String? = error.takeIf { value == null && !loading }

/** Liked songs, 50 at a time; the next page loads as the list nears its end. */
@Composable
fun LikedScreen(actions: AppActions, list: LazyListState, modifier: Modifier) {
    val tracks = remember { mutableStateListOf<Track>() }
    var nextOffset by remember { mutableStateOf<UInt?>(0u) }
    var total by rememberSaveable { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    // While searching, keep loading pages so the search covers every song.
    var filtering by remember { mutableStateOf(false) }

    val nearEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 12
        }
    }
    LaunchedEffect(nearEnd, nextOffset, filtering) {
        val offset = nextOffset ?: return@LaunchedEffect
        if (!nearEnd && !filtering && tracks.isNotEmpty() || loading) return@LaunchedEffect
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
    // Nothing yet and more to come: still loading the first page.
    val firstPage = tracks.isEmpty() && nextOffset != null

    CollectionScreen(
        actions, list,
        title = "Músicas curtidas",
        subtitle = null,
        meta = if (firstPage) "" else meta("Playlist", count(total)),
        imageUrl = tracks.firstOrNull()?.imageUrl,
        tracks = tracks.takeUnless { firstPage },
        modifier = modifier,
        source = "liked",
        contextUri = actions.core.username()?.let { "spotify:user:$it:collection" },
        onFiltering = { filtering = it },
    ) {
        if (loading && tracks.isNotEmpty()) {
            items(3, key = { "more$it" }) { i ->
                TrackRowSkeleton(
                    i,
                    Modifier.animateItem(fadeInSpec = skeletonFade(), placementSpec = null, fadeOutSpec = skeletonFade())
                        .padding(horizontal = 16.dp),
                )
            }
        }
    }
}
