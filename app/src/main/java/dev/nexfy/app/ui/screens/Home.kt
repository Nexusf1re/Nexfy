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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import dev.nexfy.app.ui.design.Motion
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.nexfy.app.ui.design.glassPane
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nexfy.app.PlaylistSort
import dev.nexfy.app.contextKey
import dev.nexfy.app.pinnedFirst
import dev.nexfy.app.playScores
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.Loaded
import dev.nexfy.app.ui.Peek
import dev.nexfy.app.ui.Route
import dev.nexfy.app.ui.design.Carousel
import dev.nexfy.app.ui.design.CarouselSkeleton
import dev.nexfy.app.ui.design.CoverCard
import dev.nexfy.app.ui.design.LargeTitle
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.SectionTitle
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.design.skeletonFade
import dev.nexfy.app.ui.design.rememberTitleCollapse
import dev.nexfy.app.ui.design.weightedPress
import dev.nexfy.app.ui.rememberLoaded
import dev.nexfy.app.ui.theme.accentColor
import uniffi.nexfy_core.Playlist
import uniffi.nexfy_core.RecentPlay
import uniffi.nexfy_core.Track

/** A playlist or album the user played recently, ready to show as a card. */
private data class RecentContext(
    val key: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val route: Route,
    val peek: Peek,
)

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
                        RecentContext(key, it.name, "Playlist · ${it.owner}", it.imageUrl, Route.Playlist(id), Peek(it.name, it.owner, it.imageUrl))
                    }
                    key.startsWith("al:") -> RecentContext(
                        key, play.track.album, "Álbum · ${play.track.artists}", play.track.imageUrl, Route.Album(id),
                        Peek(play.track.album, play.track.artists, play.track.imageUrl),
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
    val version by actions.app.edits.version.collectAsState()
    val playlists = rememberLoaded("home-pl", reloadOn = version) { core.myPlaylists(it) }
    val albums = rememberLoaded("home-al", reloadOn = version) { core.savedAlbums(it) }
    val recent = rememberLoaded("home-recent") { core.recentlyPlayed(it) }
    val topTracks = rememberLoaded("home-top-tracks") { core.topTracks(it) }
    val topArtists = rememberLoaded("home-top-artists") { core.topArtists(it) }
    val releases = rememberLoaded("home-releases") { core.newReleases(it) }
    val shows = rememberLoaded("home-sh", reloadOn = version) { core.savedShows(it) }
    val friends = rememberLoaded("home-friends") { core.friendActivity(it) }

    val name = profile.state.value?.displayName?.substringBefore(' ')
    val scores = playScores(plays, recent.state.value.orEmpty())
    val recentCards = recentContexts(recent.state.value.orEmpty(), playlists.state.value.orEmpty())
    // Pinned first, then the most played.
    val myPlaylists = playlists.state.value?.let { all ->
        pinnedFirst(PlaylistSort.MostPlayed.apply(all, scores), pinned) { "pl:${it.id}" }
    }
    // Held down, a card opens the menu its row in the library has.
    val me = remember { core.username() }
    var menuFor by remember { mutableStateOf<Track?>(null) }
    menuFor?.let { TrackMenu(it, actions) { menuFor = null } }
    var shelfFor by remember { mutableStateOf<Shelved?>(null) }
    shelfFor?.let { ShelfMenu(it, actions) { shelfFor = null } }

    Column(Modifier.fillMaxSize()) {
        RootHeader("Início", collapse, actions)
        LazyColumn(
            Modifier.weight(1f),
            state = list,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = LocalBottomSpace.current),
        ) {
            item(key = "title") {
                LargeTitle(if (name != null) "${greeting()}, $name" else greeting(), collapse)
                StatsShortcut(actions)
                Spacer(Modifier.height(8.dp))
            }
            item(key = "liked") { LikedTile { actions.open(Route.Liked) } }
            // Only for people whose friends share what they play, and only
            // where Spotify gives this client the list: no skeleton, no error.
            friends.state.value?.takeIf { it.isNotEmpty() }?.let { list ->
                item(key = "friends") {
                    FriendsShortcut(
                        list, actions,
                        Modifier.padding(top = 10.dp).animateItem(fadeInSpec = skeletonFade(), placementSpec = null, fadeOutSpec = null),
                    )
                }
            }
            if (playlists.state.error != null && playlists.state.value == null) {
                item(key = "connect") { ConnectOrError(playlists.state.error, actions, playlists.refresh) }
            }
            homeRow("recent", "Tocadas recentemente", recent.state.pending() || playlists.state.pending(), recentCards.takeIf { it.isNotEmpty() }) { cards ->
                Carousel(actions.nav.list("row:home-recent"), cards.first().key) {
                    items(cards, key = { it.key }) { r ->
                        CoverCard(r.imageUrl, r.title, r.subtitle, r.key, shared = "home-recent/${r.key}") { actions.open(r.route, r.peek, "home-recent/${r.key}") }
                    }
                }
            }
            homeRow("releases", "Lançamentos dos teus artistas", releases.state.pending(), releases.state.value?.takeIf { it.isNotEmpty() }) { items ->
                Carousel(actions.nav.list("row:home-releases"), items.first().id) {
                    items(items, key = { it.id }) { a ->
                        val kind = if (a.trackCount in 1u..3u) "Single" else "Álbum"
                        CoverCard(a.imageUrl, a.name, "$kind · ${a.artists}", "rel-${a.id}", shared = "home-rel/${a.id}") {
                            actions.open(Route.Album(a.id), Peek(a.name, a.artists, a.imageUrl), "home-rel/${a.id}")
                        }
                    }
                }
            }
            homeRow("top-tracks", "Tuas mais ouvidas", topTracks.state.pending(), topTracks.state.value?.takeIf { it.isNotEmpty() }) { tracks ->
                Carousel(actions.nav.list("row:home-top"), tracks.first().uri) {
                    itemsIndexed(tracks, key = { _, t -> t.uri }) { i, t ->
                        CoverCard(t.imageUrl, t.title, t.artists, "tt-${t.uri}", onLongClick = { menuFor = t }) { actions.play(tracks, i) }
                    }
                }
            }
            homeRow("playlists", "Tuas playlists", playlists.state.pending(), myPlaylists?.takeIf { it.isNotEmpty() }) { items ->
                Carousel(actions.nav.list("row:home-pl"), items.first().id) {
                    items(items.take(20), key = { it.id }) { p ->
                        val peek = Peek(p.name, p.owner, p.imageUrl)
                        CoverCard(
                            p.imageUrl, p.name, "${p.trackCount} faixas", "pl-${p.id}", shared = "home-pl/${p.id}",
                            onLongClick = {
                                shelfFor = Shelved(
                                    "pl:${p.id}", p.name, "Playlist · ${p.owner}", p.imageUrl,
                                    Route.Playlist(p.id), peek, p.uri, own = p.ownerId == me,
                                )
                            },
                        ) { actions.open(Route.Playlist(p.id), peek, "home-pl/${p.id}") }
                    }
                }
            }
            homeRow("top-artists", "Artistas que tu mais ouve", topArtists.state.pending(), topArtists.state.value?.takeIf { it.isNotEmpty() }, circle = true) { artists ->
                Carousel(actions.nav.list("row:home-artists"), artists.first().id) {
                    items(artists, key = { it.id }) { a ->
                        CoverCard(a.imageUrl, a.name, "Artista", "ar-${a.id}", circle = true, shared = "home-ar/${a.id}") {
                            actions.open(Route.Artist(a.id), Peek(a.name, imageUrl = a.imageUrl), "home-ar/${a.id}")
                        }
                    }
                }
            }
            homeRow("albums", "Álbuns salvos", albums.state.pending(), albums.state.value?.takeIf { it.isNotEmpty() }?.let { all -> pinnedFirst(all, pinned) { "al:${it.id}" } }) { items ->
                Carousel(actions.nav.list("row:home-al"), items.first().id) {
                    items(items.take(20), key = { it.id }) { a ->
                        val peek = Peek(a.name, a.artists, a.imageUrl)
                        CoverCard(
                            a.imageUrl, a.name, a.artists, "al-${a.id}", shared = "home-al/${a.id}",
                            onLongClick = {
                                shelfFor = Shelved("al:${a.id}", a.name, "Álbum · ${a.artists}", a.imageUrl, Route.Album(a.id), peek, a.uri)
                            },
                        ) { actions.open(Route.Album(a.id), peek, "home-al/${a.id}") }
                    }
                }
            }
            // Never a skeleton: the row is there only for people who follow podcasts.
            homeRow("shows", "Teus podcasts", false, shows.state.value?.takeIf { it.isNotEmpty() }?.let { all -> pinnedFirst(all, pinned) { "sh:${it.id}" } }) { items ->
                Carousel(actions.nav.list("row:home-sh"), items.first().id) {
                    items(items.take(20), key = { it.id }) { s ->
                        val peek = Peek(s.name, s.publisher.ifEmpty { null }, s.imageUrl)
                        CoverCard(
                            s.imageUrl, s.name, s.publisher.ifEmpty { "Podcast" }, "sh-${s.id}", shared = "home-sh/${s.id}",
                            onLongClick = {
                                shelfFor = Shelved("sh:${s.id}", s.name, "Podcast", s.imageUrl, Route.Show(s.id), peek, s.uri)
                            },
                        ) { actions.open(Route.Show(s.id), peek, "home-sh/${s.id}") }
                    }
                }
            }
        }
    }
}

/** Still waiting for a first value. */
private fun Loaded<*>.pending() = value == null && loading

/**
 * A titled home row: the cards once [data] is there, a skeleton of the same
 * size while it is [pending], nothing otherwise. The cards slide in over
 * the fading skeleton.
 */
private fun <T> LazyListScope.homeRow(
    key: String,
    title: String,
    pending: Boolean,
    data: T?,
    circle: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    if (data == null && !pending) return
    // One item for both states: the title stays put and only the cards cross-fade.
    item(key = key) {
        Column(Modifier.animateItem(fadeInSpec = skeletonFade(), placementSpec = null, fadeOutSpec = null)) {
            SectionTitle(title)
            AnimatedContent(data != null, transitionSpec = { Motion.arrive using SizeTransform(clip = false) }, label = key) { loaded ->
                if (loaded && data != null) content(data) else CarouselSkeleton(circle)
            }
        }
    }
}


/** Big glass tile for liked songs, tinted from violet to the accent; the heart beats when pressed. */
@Composable
private fun LikedTile(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val accent = accentColor()
    val pressed by source.collectIsPressedAsState()
    val beat = animateFloatAsState(if (pressed) 1.22f else 1f, if (pressed) spring(.4f, 900f) else spring(.32f, 420f), label = "beat")
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().height(76.dp).weightedPress(source, 20.dp)
            .background(Brush.linearGradient(listOf(Color(0xFF4B2BD6), accent.copy(alpha = .85f))), shape)
            .glassPane(shape, Color.Transparent, true)
            .clickable(source, null) {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Favorite, null, tint = Color.White,
            modifier = Modifier.size(28.dp).graphicsLayer {
                scaleX = beat.value
                scaleY = beat.value
            },
        )
        Spacer(Modifier.width(14.dp))
        Text("Músicas curtidas", color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}
