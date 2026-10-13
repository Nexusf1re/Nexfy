package dev.nexfy.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nexfy.app.PlaylistSort
import dev.nexfy.app.contextKey
import dev.nexfy.app.pinnedFirst
import dev.nexfy.app.playScores
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.Route
import dev.nexfy.app.ui.design.Carousel
import dev.nexfy.app.ui.design.CoverCard
import dev.nexfy.app.ui.design.LargeTitle
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.SectionTitle
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.design.rememberTitleCollapse
import dev.nexfy.app.ui.design.weightedPress
import dev.nexfy.app.ui.rememberLoaded
import dev.nexfy.app.ui.theme.accentColor
import uniffi.nexfy_core.Playlist
import uniffi.nexfy_core.RecentPlay

/** A playlist or album the user played recently, ready to show as a card. */
private data class RecentContext(val key: String, val title: String, val subtitle: String, val imageUrl: String?, val route: Route)

/**
 * Distinct playlists and albums from the recent plays, newest first.
 * Playlists not in the library are skipped (their name is unknown here).
 */
private fun recentContexts(plays: List<RecentPlay>, playlists: List<Playlist>): List<RecentContext> {
    val byId = playlists.associateBy { it.id }
    return plays.mapNotNull { play ->
        when (val key = contextKey(play.contextUri)) {
            null, "liked" -> null
            else -> {
                val id = key.substringAfter(':')
                when {
                    key.startsWith("pl:") -> byId[id]?.let {
                        RecentContext(key, it.name, "Playlist · ${it.owner}", it.imageUrl, Route.Playlist(id))
                    }
                    key.startsWith("al:") -> RecentContext(
                        key, play.track.album, "Álbum · ${play.track.artists}", play.track.imageUrl, Route.Album(id),
                    )
                    else -> null
                }
            }
        }
    }.distinctBy { it.key }
}

@Composable
fun HomeScreen(actions: AppActions, list: LazyListState) {
    val core = actions.core
    val library = actions.app.library
    val collapse = rememberTitleCollapse(list)
    val pinned by library.pinned.collectAsState()
    val plays by library.plays.collectAsState()

    val profile = rememberLoaded("profile") { core.profile(it) }
    val playlists = rememberLoaded("home-pl") { core.myPlaylists(it) }
    val albums = rememberLoaded("home-al") { core.savedAlbums(it) }
    val recent = rememberLoaded("home-recent") { core.recentlyPlayed(it) }
    val topTracks = rememberLoaded("home-top-tracks") { core.topTracks(it) }
    val topArtists = rememberLoaded("home-top-artists") { core.topArtists(it) }

    val name = profile.state.value?.displayName?.substringBefore(' ')
    val scores = playScores(plays, recent.state.value.orEmpty())
    val recentCards = recentContexts(recent.state.value.orEmpty(), playlists.state.value.orEmpty())
    // Pinned first, then the most played.
    val myPlaylists = playlists.state.value?.let { all ->
        pinnedFirst(PlaylistSort.MostPlayed.apply(all, scores), pinned) { "pl:${it.id}" }
    }

    Column(Modifier.fillMaxSize()) {
        RootHeader("Início", collapse, actions)
        LazyColumn(
            Modifier.weight(1f),
            state = list,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = LocalBottomSpace.current),
        ) {
            item(key = "title") {
                LargeTitle(if (name != null) "${greeting()}, $name" else greeting(), collapse)
                Spacer(Modifier.height(12.dp))
            }
            item(key = "liked") { LikedTile { actions.open(Route.Liked) } }
            if (playlists.state.error != null && playlists.state.value == null) {
                item(key = "connect") { ConnectPrompt(actions) }
            }
            if (recentCards.isNotEmpty()) {
                item(key = "recent") {
                    Column {
                        SectionTitle("Tocadas recentemente")
                        Carousel(actions.nav.list("row:home-recent"), recentCards.first().key) {
                            items(recentCards, key = { it.key }) { r ->
                                CoverCard(r.imageUrl, r.title, r.subtitle, r.key) { actions.open(r.route) }
                            }
                        }
                    }
                }
            }
            topTracks.state.value?.takeIf { it.isNotEmpty() }?.let { tracks ->
                item(key = "top-tracks") {
                    Column {
                        SectionTitle("Tuas mais ouvidas")
                        Carousel(actions.nav.list("row:home-top"), tracks.first().uri) {
                            itemsIndexed(tracks, key = { _, t -> t.uri }) { i, t ->
                                CoverCard(t.imageUrl, t.title, t.artists, "tt-${t.uri}") { actions.play(tracks, i) }
                            }
                        }
                    }
                }
            }
            myPlaylists?.takeIf { it.isNotEmpty() }?.let { items ->
                item(key = "playlists") {
                    Column {
                        SectionTitle("Tuas playlists")
                        Carousel(actions.nav.list("row:home-pl"), items.first().id) {
                            items(items.take(20), key = { it.id }) { p ->
                                CoverCard(p.imageUrl, p.name, "${p.trackCount} faixas", "pl-${p.id}") { actions.open(Route.Playlist(p.id)) }
                            }
                        }
                    }
                }
            }
            topArtists.state.value?.takeIf { it.isNotEmpty() }?.let { artists ->
                item(key = "top-artists") {
                    Column {
                        SectionTitle("Artistas que tu mais ouve")
                        Carousel(actions.nav.list("row:home-artists"), artists.first().id) {
                            items(artists, key = { it.id }) { a ->
                                CoverCard(a.imageUrl, a.name, "Artista", "ar-${a.id}", circle = true) { actions.open(Route.Artist(a.id)) }
                            }
                        }
                    }
                }
            }
            albums.state.value?.takeIf { it.isNotEmpty() }?.let { items ->
                item(key = "albums") {
                    Column {
                        SectionTitle("Álbuns salvos")
                        Carousel(actions.nav.list("row:home-al"), pinnedFirst(items, pinned) { "al:${it.id}" }.first().id) {
                            items(pinnedFirst(items, pinned) { "al:${it.id}" }.take(20), key = { it.id }) { a ->
                                CoverCard(a.imageUrl, a.name, a.artists, "al-${a.id}") { actions.open(Route.Album(a.id)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Big gradient tile for liked songs. */
@Composable
private fun LikedTile(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val accent = accentColor()
    Row(
        Modifier.fillMaxWidth().height(76.dp).weightedPress(source, 20.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF4B2BD6), accent.copy(alpha = .85f))))
            .clickable(source, null) {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Text("Músicas curtidas", color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}
