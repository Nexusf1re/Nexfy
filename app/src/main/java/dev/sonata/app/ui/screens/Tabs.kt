package dev.sonata.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sonata.app.ui.AppActions
import dev.sonata.app.ui.LoadedContent
import dev.sonata.app.ui.Route
import dev.sonata.app.ui.design.Carousel
import dev.sonata.app.ui.design.Cover
import dev.sonata.app.ui.design.CoverCard
import dev.sonata.app.ui.design.EmptyState
import dev.sonata.app.ui.design.LargeTitle
import dev.sonata.app.ui.design.LocalBottomSpace
import dev.sonata.app.ui.design.PillButton
import dev.sonata.app.ui.design.PillSelector
import dev.sonata.app.ui.design.SectionTitle
import dev.sonata.app.ui.design.TrackRow
import dev.sonata.app.ui.design.cascade
import dev.sonata.app.ui.design.rememberHaptics
import dev.sonata.app.ui.design.rememberTitleCollapse
import dev.sonata.app.ui.design.weightedPress
import dev.sonata.app.ui.rememberLoaded
import dev.sonata.app.ui.theme.accentColor
import uniffi.sonata_core.SearchResults
import java.time.LocalTime

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Bom dia"
    in 12..17 -> "Boa tarde"
    else -> "Boa noite"
}

/** Shown when the Web API is not signed in yet. */
@Composable
private fun ConnectPrompt(actions: AppActions) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    EmptyState(Icons.Rounded.LibraryMusic, "Conecte a biblioteca", "Entre com o app pessoal e o compartilhado em Ajustes para ver tuas playlists e buscar.")
    PillButton("Abrir ajustes", { actions.open(Route.Settings) })
}

@Composable
fun HomeScreen(actions: AppActions, list: LazyListState) {
    val collapse = rememberTitleCollapse(list)
    val profile = rememberLoaded("profile") { actions.core.profile(it) }
    val playlists = rememberLoaded("home-pl") { actions.core.myPlaylists(it) }
    val albums = rememberLoaded("home-al") { actions.core.savedAlbums(it) }
    val name = profile.state.value?.displayName?.substringBefore(' ')

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
            playlists.state.value?.takeIf { it.isNotEmpty() }?.let { items ->
                item(key = "playlists") {
                    Column {
                        SectionTitle("Tuas playlists")
                        Carousel(actions.nav.list("row:home-pl")) {
                            items(items.take(20), key = { it.id }) { p ->
                                CoverCard(p.imageUrl, p.name, "${p.trackCount} faixas", "pl-${p.id}") { actions.open(Route.Playlist(p.id)) }
                            }
                        }
                    }
                }
            }
            albums.state.value?.takeIf { it.isNotEmpty() }?.let { items ->
                item(key = "albums") {
                    Column {
                        SectionTitle("Álbuns salvos")
                        Carousel(actions.nav.list("row:home-al")) {
                            items(items.take(20), key = { it.id }) { a ->
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

private enum class Shelf(val label: String) { Playlists("Playlists"), Albums("Álbuns") }

@Composable
fun LibraryScreen(actions: AppActions, list: LazyListState) {
    val collapse = rememberTitleCollapse(list)
    var shelf by rememberSaveable { mutableStateOf(Shelf.Playlists) }
    val playlists = rememberLoaded("lib-pl") { actions.core.myPlaylists(it) }
    val albums = rememberLoaded("lib-al", shelf) { if (shelf == Shelf.Albums) actions.core.savedAlbums(it) else emptyList() }

    Column(Modifier.fillMaxSize()) {
        RootHeader("Biblioteca", collapse, actions)
        LazyColumn(
            Modifier.weight(1f),
            state = list,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = LocalBottomSpace.current),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "title") { LargeTitle("Biblioteca", collapse, Modifier.padding(start = 4.dp)) }
            item(key = "shelf") {
                PillSelector(Shelf.entries, shelf, { it.label }, { shelf = it }, Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }
            when (shelf) {
                Shelf.Playlists -> {
                    item(key = "liked") {
                        LibraryRow(null, "Músicas curtidas", "Playlist", liked = true) { actions.open(Route.Liked) }
                    }
                    val value = playlists.state.value
                    if (value == null && playlists.state.error != null) item(key = "connect") { ConnectPrompt(actions) }
                    itemsIndexed(value.orEmpty(), key = { _, p -> "pl:" + p.id }) { i, p ->
                        Box(Modifier.cascade(i)) {
                            LibraryRow(p.imageUrl, p.name, "Playlist · ${p.owner}") { actions.open(Route.Playlist(p.id)) }
                        }
                    }
                }
                Shelf.Albums -> itemsIndexed(albums.state.value.orEmpty(), key = { _, a -> "al:" + a.id }) { i, a ->
                    Box(Modifier.cascade(i)) {
                        LibraryRow(a.imageUrl, a.name, "Álbum · ${a.artists}") { actions.open(Route.Album(a.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(imageUrl: String?, title: String, subtitle: String, round: Boolean = false, liked: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().weightedPress(source, 16.dp).clip(RoundedCornerShape(16.dp))
            .clickable(source, null) {
                haptics.tick()
                onClick()
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (liked) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF4B2BD6), accentColor().copy(alpha = .85f)))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Favorite, null, tint = Color.White) }
        } else {
            Cover(imageUrl, Modifier.size(64.dp), if (round) 32.dp else 12.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = c.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SearchScreen(actions: AppActions, list: LazyListState) {
    val c = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val collapse = rememberTitleCollapse(list)
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Search once typing pauses.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(450)
        searching = true
        try {
            results = actions.core.search(q)
            error = null
        } catch (e: Exception) {
            error = e.message
        } finally {
            searching = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        RootHeader("Buscar", collapse, actions)
        LazyColumn(
            Modifier.weight(1f),
            state = list,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = LocalBottomSpace.current),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "title") { LargeTitle("Buscar", collapse, Modifier.padding(start = 4.dp)) }
            item(key = "field") {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp).height(52.dp).clip(CircleShape).background(c.surfaceVariant)
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = c.onSurfaceVariant)
                    TextField(
                        query, { query = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("Músicas, artistas, álbuns", color = c.onSurfaceVariant, maxLines = 1) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = c.surfaceVariant, unfocusedContainerColor = c.surfaceVariant,
                            focusedIndicatorColor = c.surfaceVariant, unfocusedIndicatorColor = c.surfaceVariant,
                        ),
                    )
                    if (searching) {
                        CircularProgressIndicator(Modifier.size(44.dp).padding(12.dp), strokeWidth = 2.dp, color = c.onSurface)
                    } else if (query.isNotEmpty()) {
                        IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Limpar busca", tint = c.onSurfaceVariant) }
                    }
                }
            }
            error?.let { item(key = "error") { Text(it, color = c.onSurfaceVariant, modifier = Modifier.padding(8.dp)) } }

            val r = results
            if (r == null) {
                if (query.isBlank()) {
                    item(key = "hint") {
                        Text(
                            "Toque num resultado para ouvir na hora.",
                            color = c.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            } else if (r.tracks.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty() && r.playlists.isEmpty()) {
                item(key = "none") { EmptyState(Icons.Rounded.SearchOff, "Nada encontrado para “${query.trim()}”", "Confira a grafia ou tente o nome do artista.") }
            } else {
                if (r.tracks.isNotEmpty()) item(key = "s-tracks") { SectionTitle("Músicas", Modifier.padding(start = 4.dp)) }
                itemsIndexed(r.tracks, key = { _, t -> "t:" + t.uri }) { i, t ->
                    Box(Modifier.cascade(i)) {
                        TrackRow(t, {
                            keyboard?.hide()
                            actions.play(r.tracks, i)
                        }, onLongClick = t.albumId?.let { id -> { actions.open(Route.Album(id)) } })
                    }
                }
                if (r.artists.isNotEmpty()) {
                    item(key = "artists") {
                        Column {
                            SectionTitle("Artistas", Modifier.padding(start = 4.dp))
                            Carousel(actions.nav.list("row:s-ar")) {
                                items(r.artists, key = { it.id }) { a ->
                                    CoverCard(a.imageUrl, a.name, "Artista", "ar-${a.id}", circle = true) { actions.open(Route.Artist(a.id)) }
                                }
                            }
                        }
                    }
                }
                if (r.albums.isNotEmpty()) {
                    item(key = "albums") {
                        Column {
                            SectionTitle("Álbuns", Modifier.padding(start = 4.dp))
                            Carousel(actions.nav.list("row:s-al")) {
                                items(r.albums, key = { it.id }) { a ->
                                    CoverCard(a.imageUrl, a.name, a.artists, "al-${a.id}") { actions.open(Route.Album(a.id)) }
                                }
                            }
                        }
                    }
                }
                if (r.playlists.isNotEmpty()) {
                    item(key = "playlists") {
                        Column {
                            SectionTitle("Playlists", Modifier.padding(start = 4.dp))
                            Carousel(actions.nav.list("row:s-pl")) {
                                items(r.playlists, key = { it.id }) { p ->
                                    CoverCard(p.imageUrl, p.name, p.owner, "pl-${p.id}") { actions.open(Route.Playlist(p.id)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
