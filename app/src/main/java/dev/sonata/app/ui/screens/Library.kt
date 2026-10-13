package dev.sonata.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sonata.app.AlbumSort
import dev.sonata.app.PlaylistSort
import dev.sonata.app.pinnedFirst
import dev.sonata.app.playScores
import dev.sonata.app.ui.AppActions
import dev.sonata.app.ui.Route
import dev.sonata.app.ui.design.Cover
import dev.sonata.app.ui.design.LargeTitle
import dev.sonata.app.ui.design.LocalBottomSpace
import dev.sonata.app.ui.design.PillSelector
import dev.sonata.app.ui.design.cascade
import dev.sonata.app.ui.design.rememberCascade
import dev.sonata.app.ui.design.rememberHaptics
import dev.sonata.app.ui.design.rememberTitleCollapse
import dev.sonata.app.ui.design.weightedPress
import dev.sonata.app.ui.rememberLoaded
import dev.sonata.app.ui.theme.accentColor

private enum class Shelf(val label: String) { Playlists("Playlists"), Albums("Álbuns") }

/** A row of the library list. */
private data class Entry(val key: String, val title: String, val subtitle: String, val imageUrl: String?, val route: Route)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(actions: AppActions, list: LazyListState) {
    val core = actions.core
    val library = actions.app.library
    val collapse = rememberTitleCollapse(list)
    var shelf by rememberSaveable { mutableStateOf(Shelf.Playlists) }
    val pinned by library.pinned.collectAsState()
    val plays by library.plays.collectAsState()
    val playlistSort by library.playlistSort.collectAsState()
    val albumSort by library.albumSort.collectAsState()

    val playlists = rememberLoaded("lib-pl") { core.myPlaylists(it) }
    val albums = rememberLoaded("lib-al", shelf) { if (shelf == Shelf.Albums) core.savedAlbums(it) else emptyList() }
    val recent = rememberLoaded("lib-recent") { core.recentlyPlayed(it) }
    val scores = playScores(plays, recent.state.value.orEmpty())

    val entries: List<Entry>? = when (shelf) {
        Shelf.Playlists -> playlists.state.value?.let { all ->
            pinnedFirst(playlistSort.apply(all, scores), pinned) { "pl:${it.id}" }.map {
                Entry("pl:${it.id}", it.name, "Playlist · ${it.owner}", it.imageUrl, Route.Playlist(it.id))
            }
        }
        Shelf.Albums -> albums.state.value?.let { all ->
            pinnedFirst(albumSort.apply(all, scores), pinned) { "al:${it.id}" }.map {
                Entry("al:${it.id}", it.name, "Álbum · ${it.artists}", it.imageUrl, Route.Album(it.id))
            }
        }
    }
    val cascadeClock = rememberCascade(shelf to (entries != null))
    var menuFor by remember { mutableStateOf<Entry?>(null) }

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
                PillSelector(Shelf.entries, shelf, { it.label }, { shelf = it }, Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            item(key = "sort") {
                when (shelf) {
                    Shelf.Playlists -> SortButton(PlaylistSort.entries, playlistSort, { it.label }, library::setPlaylistSort)
                    Shelf.Albums -> SortButton(AlbumSort.entries, albumSort, { it.label }, library::setAlbumSort)
                }
            }
            if (shelf == Shelf.Playlists) {
                item(key = "liked") {
                    LibraryRow(null, "Músicas curtidas", "Playlist", liked = true, onClick = { actions.open(Route.Liked) })
                }
            }
            if (entries == null && (if (shelf == Shelf.Playlists) playlists else albums).state.error != null) {
                item(key = "connect") { ConnectPrompt(actions) }
            }
            itemsIndexed(entries.orEmpty(), key = { _, e -> e.key }) { i, e ->
                Box(Modifier.cascade(i, cascadeClock)) {
                    LibraryRow(
                        e.imageUrl, e.title, e.subtitle,
                        pinned = e.key in pinned,
                        onClick = { actions.open(e.route) },
                        onLongClick = { menuFor = e },
                    )
                }
            }
        }
    }

    menuFor?.let { entry ->
        val isPinned = entry.key in pinned
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(entry.imageUrl, Modifier.size(52.dp), 10.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                    }
                }
                SheetAction(Icons.Rounded.PushPin, if (isPinned) "Desafixar" else "Fixar no topo") {
                    library.togglePin(entry.key)
                    menuFor = null
                }
                SheetAction(Icons.Rounded.OpenInNew, "Abrir") {
                    menuFor = null
                    actions.open(entry.route)
                }
            }
        }
    }
}

@Composable
private fun <T> SortButton(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val c = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(vertical = 6.dp)) {
        Row(
            Modifier.clip(CircleShape).clickable { open = true }.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.Sort, null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label(selected), color = c.onSurface, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(open, { open = false }, shape = RoundedCornerShape(18.dp)) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    trailingIcon = { if (option == selected) Icon(Icons.Rounded.Check, null) },
                )
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(
    imageUrl: String?,
    title: String,
    subtitle: String,
    pinned: Boolean = false,
    liked: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().weightedPress(source, 16.dp).clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                source, null,
                onClick = {
                    haptics.tick()
                    onClick()
                },
                onLongClick = onLongClick?.let {
                    {
                        haptics.press()
                        it()
                    }
                },
            )
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
            Cover(imageUrl, Modifier.size(64.dp), 12.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned || liked) {
                    Icon(Icons.Rounded.PushPin, "Fixado", tint = accentColor(), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(subtitle, color = c.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
