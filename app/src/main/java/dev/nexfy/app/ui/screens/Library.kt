package dev.nexfy.app.ui.screens

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
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
import androidx.compose.foundation.lazy.items
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
import dev.nexfy.app.AlbumSort
import dev.nexfy.app.PlaylistSort
import dev.nexfy.app.pinnedFirst
import dev.nexfy.app.playScores
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.Peek
import dev.nexfy.app.ui.Route
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.design.LargeTitle
import dev.nexfy.app.ui.design.ListRowSkeleton
import dev.nexfy.app.ui.design.skeletonFade
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.PillSelector
import dev.nexfy.app.ui.design.cascade
import dev.nexfy.app.ui.design.rememberCascade
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.design.rememberTitleCollapse
import dev.nexfy.app.ui.design.weightedPress
import dev.nexfy.app.ui.rememberLoaded
import dev.nexfy.app.ui.theme.accentColor

private enum class Shelf(val label: String) { Playlists("Playlists"), Albums("Álbuns"), Podcasts("Podcasts") }

/** A row of the library list. */
private data class Entry(
    val key: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val route: Route,
    val peek: Peek,
    val uri: String,
    /** The user's own playlist (deleted, not just removed). */
    val own: Boolean = false,
)

private const val GRID_COLUMNS = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(actions: AppActions, list: LazyListState) {
    val core = actions.core
    val library = actions.app.library
    val collapse = rememberTitleCollapse(list)
    // The shelf last shown, across launches.
    var shelf by remember { mutableStateOf(Shelf.entries.firstOrNull { it.name == library.shelf } ?: Shelf.Playlists) }
    val pinned by library.pinned.collectAsState()
    val plays by library.plays.collectAsState()
    val playlistSort by library.playlistSort.collectAsState()
    val albumSort by library.albumSort.collectAsState()

    val version by actions.app.edits.version.collectAsState()
    val playlists = rememberLoaded("lib-pl", reloadOn = version) { core.myPlaylists(it) }
    val albums = rememberLoaded("lib-al", shelf, reloadOn = version) { if (shelf == Shelf.Albums) core.savedAlbums(it) else emptyList() }
    val shows = rememberLoaded("lib-sh", shelf, reloadOn = version) { if (shelf == Shelf.Podcasts) core.savedShows(it) else emptyList() }
    val recent = rememberLoaded("lib-recent") { core.recentlyPlayed(it) }
    val scores = playScores(plays, recent.state.value.orEmpty())
    val me = remember { core.username() }

    val entries: List<Entry>? = when (shelf) {
        Shelf.Playlists -> playlists.state.value?.let { all ->
            pinnedFirst(playlistSort.apply(all, scores), pinned) { "pl:${it.id}" }.map {
                Entry("pl:${it.id}", it.name, "Playlist · ${it.owner}", it.imageUrl, Route.Playlist(it.id), Peek(it.name, it.owner, it.imageUrl), it.uri, own = it.ownerId == me)
            }
        }
        Shelf.Albums -> albums.state.value?.let { all ->
            pinnedFirst(albumSort.apply(all, scores), pinned) { "al:${it.id}" }.map {
                Entry("al:${it.id}", it.name, "Álbum · ${it.artists}", it.imageUrl, Route.Album(it.id), Peek(it.name, it.artists, it.imageUrl), it.uri)
            }
        }
        // Kept in Spotify's order (latest saved first); there is no play count to sort by.
        Shelf.Podcasts -> shows.state.value?.let { all ->
            pinnedFirst(all, pinned) { "sh:${it.id}" }.map {
                val subtitle = if (it.publisher.isEmpty()) "Podcast" else "Podcast · ${it.publisher}"
                Entry("sh:${it.id}", it.name, subtitle, it.imageUrl, Route.Show(it.id), Peek(it.name, it.publisher.ifEmpty { null }, it.imageUrl), it.uri)
            }
        }
    }
    val cascadeClock = rememberCascade(shelf to (entries != null))
    var menuFor by remember { mutableStateOf<Entry?>(null) }
    var grid by remember { mutableStateOf(library.libraryGrid) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Entry?>(null) }
    if (creating) {
        NameDialog("Nova playlist", "", "Criar", onDismiss = { creating = false }) { name ->
            creating = false
            actions.app.edits.create(name) { actions.open(Route.Playlist(it.id), Peek(it.name, it.owner)) }
        }
    }
    deleting?.let { entry ->
        ConfirmDialog("Excluir playlist?", "“${entry.title}” sai da tua biblioteca no Spotify.", "Excluir", { deleting = null }) {
            deleting = null
            actions.app.edits.setSaved(entry.uri, false, "Playlist excluída")
        }
    }

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
                PillSelector(Shelf.entries, shelf, { it.label }, { shelf = it; library.shelf = it.name }, Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            item(key = "sort") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (shelf) {
                        Shelf.Playlists -> SortButton(PlaylistSort.entries, playlistSort, { it.label }, library::setPlaylistSort)
                        Shelf.Albums -> SortButton(AlbumSort.entries, albumSort, { it.label }, library::setAlbumSort)
                        Shelf.Podcasts -> Unit
                    }
                    Spacer(Modifier.weight(1f))
                    if (shelf == Shelf.Playlists) {
                        IconButton({ creating = true }) { Icon(Icons.Rounded.Add, "Nova playlist", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    IconButton({ grid = !grid; library.libraryGrid = grid }) {
                        Icon(
                            if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                            if (grid) "Ver em lista" else "Ver em grade",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (shelf == Shelf.Playlists && !grid) {
                item(key = "liked") {
                    LibraryRow(null, "Músicas curtidas", "Playlist", liked = true, onClick = { actions.open(Route.Liked) })
                }
            }
            val shelfState = when (shelf) {
                Shelf.Playlists -> playlists.state
                Shelf.Albums -> albums.state
                Shelf.Podcasts -> shows.state
            }
            if (entries == null && shelfState.error != null) {
                item(key = "connect") { ConnectOrError(shelfState.error, actions, (if (shelf == Shelf.Playlists) playlists else albums).refresh) }
            }
            if (entries == null && shelfState.loading) {
                items(8, key = { "sk$it" }) { i ->
                    ListRowSkeleton(i, Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = skeletonFade()))
                }
            }
            if (grid) {
                // Liked songs lead the grid as they lead the list.
                val tiles = if (shelf == Shelf.Playlists && entries != null) listOf<Entry?>(null) + entries else entries.orEmpty()
                itemsIndexed(tiles.chunked(GRID_COLUMNS), key = { _, row -> row.joinToString { it?.key ?: "liked" } }) { i, row ->
                    Row(Modifier.cascade(i, cascadeClock), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { e ->
                            val tile = Modifier.weight(1f)
                            if (e == null) {
                                LibraryTile(null, "Músicas curtidas", "Playlist", tile, liked = true, onClick = { actions.open(Route.Liked) })
                            } else {
                                LibraryTile(
                                    e.imageUrl, e.title, e.subtitle, tile,
                                    pinned = e.key in pinned,
                                    sharedKey = "lib/${e.key}",
                                    onClick = { actions.open(e.route, e.peek, "lib/${e.key}") },
                                    onLongClick = { menuFor = e },
                                )
                            }
                        }
                        repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            } else {
                itemsIndexed(entries.orEmpty(), key = { _, e -> e.key }) { i, e ->
                    Box(Modifier.cascade(i, cascadeClock)) {
                        LibraryRow(
                            e.imageUrl, e.title, e.subtitle,
                            pinned = e.key in pinned,
                            sharedKey = "lib/${e.key}",
                            onClick = { actions.open(e.route, e.peek, "lib/${e.key}") },
                            onLongClick = { menuFor = e },
                        )
                    }
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
                    actions.open(entry.route, entry.peek)
                }
                if (entry.own) {
                    SheetAction(Icons.Rounded.DeleteOutline, "Excluir playlist") {
                        menuFor = null
                        deleting = entry
                    }
                } else {
                    SheetAction(Icons.Rounded.RemoveCircleOutline, "Remover da biblioteca") {
                        menuFor = null
                        actions.app.edits.setSaved(entry.uri, false, "Removida da biblioteca")
                    }
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
    sharedKey: String? = null,
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
            Cover(imageUrl, Modifier.size(64.dp), 12.dp, sharedKey = sharedKey)
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

/** A library entry as a grid tile: the cover, the name and its line under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTile(
    imageUrl: String?,
    title: String,
    subtitle: String,
    modifier: Modifier,
    pinned: Boolean = false,
    liked: Boolean = false,
    sharedKey: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Column(
        modifier.weightedPress(source, 14.dp).clip(RoundedCornerShape(14.dp))
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
    ) {
        if (liked) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF4B2BD6), accentColor().copy(alpha = .85f)))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
        } else {
            Cover(imageUrl, Modifier.fillMaxWidth().aspectRatio(1f), 12.dp, sharedKey = sharedKey)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pinned || liked) {
                Icon(Icons.Rounded.PushPin, "Fixado", tint = accentColor(), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
            }
            Text(subtitle, color = c.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
