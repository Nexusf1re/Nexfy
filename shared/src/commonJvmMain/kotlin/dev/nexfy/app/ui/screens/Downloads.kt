package dev.nexfy.app.ui.screens

import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.Peek
import dev.nexfy.app.ui.Route
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.design.EmptyState
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.RoundIconButton
import dev.nexfy.app.ui.design.SectionTitle
import dev.nexfy.app.ui.design.awaitShown
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.rememberLoaded
import dev.nexfy.app.ui.theme.accentColor
import kotlinx.coroutines.delay

/** A kept list and how much of it is already on the phone. */
private data class Kept(
    val key: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val route: Route,
    val peek: Peek,
    val total: Int,
    val onPhone: Int,
)

private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_000_000_000.0).replace('.', ',')

/**
 * "No celular": the lists marked to keep and how far their download got,
 * so it is plain what still plays with no connection. Marking a list is
 * done from its own page; this only reports.
 */
@Composable
fun DownloadsScreen(actions: AppActions, list: LazyListState, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val app = actions.services
    val core = actions.core
    val kept by app.library.kept.collectAsState()
    val prefetch by app.library.prefetchQueue.collectAsState()

    val playlists = rememberLoaded("dl-pl") { core.myPlaylists(it) }
    val albums = rememberLoaded("dl-al") { core.savedAlbums(it) }

    var info by remember { mutableStateOf(core.cacheInfo()) }
    var counts by remember { mutableStateOf<Map<String, Pair<Int, Int>>>(emptyMap()) }
    // Downloads fill in the background: keep the numbers live while open.
    LaunchedEffect(kept) {
        while (true) {
            info = core.cacheInfo()
            counts = kept.associateWith { key ->
                val uris = runCatching { app.keptTracks(key) }.getOrDefault(emptyList())
                uris.size to runCatching { core.cachedCount(uris).toInt() }.getOrDefault(0)
            }
            delay(4_000)
            awaitShown()
        }
    }

    val byId = playlists.state.value.orEmpty().associateBy { it.id }
    val albumById = albums.state.value.orEmpty().associateBy { it.id }
    val rows = kept.mapNotNull { key ->
        val id = key.substringAfter(':')
        val (total, onPhone) = counts[key] ?: (0 to 0)
        when {
            key.startsWith("pl:") -> byId[id]?.let {
                Kept(key, it.name, "Playlist", it.imageUrl, Route.Playlist(id), Peek(it.name, it.owner, it.imageUrl), total, onPhone)
            }
            key.startsWith("al:") -> albumById[id]?.let {
                Kept(key, it.name, "Álbum · ${it.artists}", it.imageUrl, Route.Album(id), Peek(it.name, it.artists, it.imageUrl), total, onPhone)
            }
            else -> null
        }
    }.sortedBy { it.title.lowercase() }

    LazyColumn(
        modifier,
        state = list,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = LocalBottomSpace.current),
    ) {
        item(key = "header") {
            Column {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", { actions.back() }, container = c.background)
                }
                Text("No celular", Modifier.padding(top = 14.dp), fontSize = 34.sp, lineHeight = 40.sp, style = MaterialTheme.typography.headlineLarge)
                Text("O que toca sem internet", color = c.onSurfaceVariant, fontSize = 14.sp)
            }
        }
        item(key = "space") {
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${gb(info.usedBytes.toLong())} de ${gb(info.limitBytes.toLong())}", style = MaterialTheme.typography.titleMedium)
                val share = (info.usedBytes.toFloat() / info.limitBytes.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                val shown by animateFloatAsState(share, Motion.settle(), label = "space")
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(CircleShape).background(accentColor()))
                }
                Text(
                    if (prefetch) "As próximas da fila também baixam, no Wi-Fi." else "O download das próximas da fila está desligado.",
                    color = c.onSurfaceVariant, fontSize = 13.sp,
                )
            }
        }
        if (rows.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    Icons.Rounded.CloudOff,
                    "Nada guardado ainda",
                    "Abre uma playlist ou um álbum e toca em baixar: ele fica no celular e toca sem internet.",
                    Modifier.padding(top = 24.dp),
                )
            }
            return@LazyColumn
        }
        item(key = "lists-title") { SectionTitle("Listas guardadas") }
        items(rows, key = { "kept:${it.key}" }) { row -> KeptRow(row) { actions.open(row.route, row.peek) } }
    }
}

/** A kept list: its cover, how many of its songs are down, and a thin gauge. */
@Composable
private fun KeptRow(row: Kept, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val accent = accentColor()
    val haptics = rememberHaptics()
    val done = row.total > 0 && row.onPhone >= row.total
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable { haptics.tick(); onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(row.imageUrl, Modifier.size(56.dp), 12.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (done) {
                    Icon(Icons.Rounded.DownloadDone, "Toda no celular", tint = accent, modifier = Modifier.size(18.dp))
                } else {
                    Text("${row.onPhone}/${row.total}", color = c.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(row.subtitle, color = c.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!done) {
                val share = if (row.total > 0) row.onPhone.toFloat() / row.total else 0f
                val shown by animateFloatAsState(share, Motion.settle(), label = "kept")
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(c.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(accent))
                }
            }
        }
    }
}

/** The way into "No celular" from the library, with how many lists are kept. */
@Composable
fun DownloadsShortcut(lists: Int, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(18.dp))
            .background(accentColor().copy(alpha = .12f))
            .clickable { haptics.tick(); onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.DownloadDone, null, tint = accentColor(), modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("No celular", color = c.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (lists == 1) "1 lista toca sem internet" else "$lists listas tocam sem internet",
                color = c.onSurfaceVariant, fontSize = 12.sp,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.onSurfaceVariant)
    }
}
