package dev.nexfy.app.ui.screens

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
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
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.Peek
import dev.nexfy.app.ui.Route
import dev.nexfy.app.ui.design.Carousel
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.design.CoverCard
import dev.nexfy.app.ui.design.EmptyState
import dev.nexfy.app.ui.design.LargeTitle
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.PillButton
import dev.nexfy.app.ui.design.PillSelector
import dev.nexfy.app.ui.design.SectionTitle
import dev.nexfy.app.ui.design.TrackRow
import dev.nexfy.app.ui.design.TrackRowSkeleton
import dev.nexfy.app.ui.design.skeletonFade
import dev.nexfy.app.ui.design.cascade
import dev.nexfy.app.ui.design.rememberCascade
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.design.rememberTitleCollapse
import dev.nexfy.app.ui.design.weightedPress
import dev.nexfy.app.ui.theme.accentColor
import uniffi.nexfy_core.SearchResults
import java.time.LocalTime

internal fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Bom dia"
    in 12..17 -> "Boa tarde"
    else -> "Boa noite"
}

/** Shown when the Web API is not signed in yet. */
@Composable
internal fun ConnectPrompt(actions: AppActions) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    EmptyState(Icons.Rounded.LibraryMusic, "Conecte a biblioteca", "Entre com o app pessoal e o compartilhado em Ajustes para ver tuas playlists e buscar.")
    PillButton("Abrir ajustes", { actions.open(Route.Settings) })
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
    val cascadeClock = rememberCascade(results)
    var menuFor by remember { mutableStateOf<uniffi.nexfy_core.Track?>(null) }
    menuFor?.let { TrackMenu(it, actions) { menuFor = null } }

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
                    BasicTextField(
                        query, { query = it }, Modifier.weight(1f).padding(horizontal = 12.dp), singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                        cursorBrush = SolidColor(c.onSurface),
                        decorationBox = { field ->
                            if (query.isEmpty()) {
                                Text("Músicas, artistas, álbuns", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            }
                            field()
                        },
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
                // First search on its way (typing pause included): rows the size of the results.
                if (query.trim().length >= 2 && error == null) {
                    items(6, key = { "sk$it" }) { i ->
                        TrackRowSkeleton(i, Modifier.animateItem(fadeInSpec = skeletonFade(), placementSpec = null, fadeOutSpec = skeletonFade()))
                    }
                }
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
                    Box(Modifier.cascade(i, cascadeClock)) {
                        TrackRow(t, {
                            keyboard?.hide()
                            actions.play(r.tracks, i)
                        }, onLongClick = { menuFor = t }, onSwipe = { actions.addToQueue(t) })
                    }
                }
                if (r.artists.isNotEmpty()) {
                    item(key = "artists") {
                        Column {
                            SectionTitle("Artistas", Modifier.padding(start = 4.dp))
                            Carousel(actions.nav.list("row:s-ar")) {
                                items(r.artists, key = { it.id }) { a ->
                                    CoverCard(a.imageUrl, a.name, "Artista", "ar-${a.id}", circle = true) { actions.open(Route.Artist(a.id), Peek(a.name, imageUrl = a.imageUrl)) }
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
                                    CoverCard(a.imageUrl, a.name, a.artists, "al-${a.id}") { actions.open(Route.Album(a.id), Peek(a.name, a.artists, a.imageUrl)) }
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
                                    CoverCard(p.imageUrl, p.name, p.owner, "pl-${p.id}") { actions.open(Route.Playlist(p.id), Peek(p.name, p.owner, p.imageUrl)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
