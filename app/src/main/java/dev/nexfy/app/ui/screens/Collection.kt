package dev.nexfy.app.ui.screens

import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.Route
import dev.nexfy.app.ui.LoadError
import dev.nexfy.app.ui.design.Bone
import dev.nexfy.app.ui.design.CollectionBackdrop
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.design.DetailTopBar
import dev.nexfy.app.ui.design.GlassBox
import dev.nexfy.app.ui.design.GlassIcon
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.RoundIconButton
import dev.nexfy.app.ui.design.TopBarHeight
import dev.nexfy.app.ui.design.TrackRow
import dev.nexfy.app.ui.design.TrackRowSkeleton
import dev.nexfy.app.ui.design.skeletonFade
import dev.nexfy.app.ui.design.collapseOf
import dev.nexfy.app.ui.design.dragToDismiss
import dev.nexfy.app.ui.design.rememberHaptics
import uniffi.nexfy_core.Track

/**
 * Root tab header: a small wordmark and the settings button; a centred
 * small title appears once the large title scrolls away.
 */
@Composable
fun RootHeader(title: String, collapse: State<Float>, actions: AppActions) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "nexfy",
                Modifier.weight(1f).graphicsLayer { alpha = (1f - collapse.value * 1.4f).coerceIn(0f, 1f) },
                style = MaterialTheme.typography.titleLarge,
                letterSpacing = (-1).sp,
            )
            RoundIconButton(Icons.Rounded.Devices, "Dispositivos", { actions.openDevices() }, container = c.background)
            RoundIconButton(Icons.Rounded.Settings, "Ajustes", { actions.open(Route.Settings) }, container = c.background)
        }
        Text(
            title,
            Modifier.align(Alignment.Center).clearAndSetSemantics { }.graphicsLayer {
                val v = ((collapse.value - .55f) / .45f).coerceIn(0f, 1f)
                alpha = v
                translationY = (1f - v) * 12.dp.toPx()
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * Album, playlist or liked songs: a collapsing hero header with glass
 * actions and the track list, over the cover's living backdrop. Pull down at
 * the top to close.
 */
@Composable
fun CollectionScreen(
    actions: AppActions,
    list: LazyListState,
    title: String,
    subtitle: String?,
    meta: String,
    imageUrl: String?,
    /** Null while loading: the header shows what is known and the rows are skeletons. */
    tracks: List<Track>?,
    modifier: Modifier = Modifier,
    /** Why loading failed, shown in place of the rows with [onRetry]. */
    error: String? = null,
    onRetry: () -> Unit = {},
    numbered: Boolean = false,
    round: Boolean = false,
    onSubtitle: (() -> Unit)? = null,
    /** Library key counted when playing from here (`pl:<id>`, `al:<id>`, `liked`). */
    source: String? = null,
    /** Spotify context for the whole, unfiltered list (playlist, album, liked songs). */
    contextUri: String? = null,
    /** Told whether a search is active (liked songs then load every page). */
    onFiltering: (Boolean) -> Unit = {},
    footer: LazyListScope.() -> Unit = {},
) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val collapse = collapseOf(list)
    val dismiss = remember { Animatable(0f) }
    val back = {
        haptics.tick()
        actions.back()
        Unit
    }
    val loading = tracks == null
    val all = tracks.orEmpty()
    // Only the playing song's URIs: rows recompose when the song changes, not on every playback update.
    val playbackState = actions.app.bridge.state.collectAsState()
    val playingUris by remember { derivedStateOf { playbackState.value.queue.current?.uri to playbackState.value.track?.uri } }
    // Kept on the phone: downloaded on Wi-Fi; count what is there already.
    val keptAll by actions.app.library.kept.collectAsState()
    val keepKey = source?.takeIf { it.startsWith("pl:") || it.startsWith("al:") }
    val kept = keepKey != null && keepKey in keptAll
    var onPhone by remember { mutableIntStateOf(0) }
    LaunchedEffect(kept, all.size) {
        while (kept && all.isNotEmpty()) {
            onPhone = actions.core.cachedCount(all.map { it.uri }).toInt()
            delay(4_000)
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(TrackSort.Original) }
    LaunchedEffect(query.isNotBlank()) { onFiltering(query.isNotBlank()) }
    // What the list shows (and what plays from it), with each track's position in the original.
    // tracks.size: liked songs grow the same list instance page by page.
    val shown = remember(all, all.size, query, sort) { sort.apply(filterTracks(all, query)) }
    val shownTracks = remember(shown) { shown.map { it.value } }
    // A filtered or re-sorted view plays as an explicit list, not as the context.
    val rowContext = contextUri.takeIf { query.isBlank() && sort == TrackSort.Original }
    var menuFor by remember { mutableStateOf<Track?>(null) }
    menuFor?.let { TrackMenu(it, actions) { menuFor = null } }

    Box(modifier) {
        CollectionBackdrop(imageUrl, collapse, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().dragToDismiss(dismiss, back)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(bottom = LocalBottomSpace.current),
            ) {
                item(key = "header") {
                    Column(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(top = TopBarHeight)
                            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                            .graphicsLayer { alpha = (1f - collapse.value * 1.5f).coerceIn(0f, 1f) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val coverModifier = Modifier.size(208.dp).graphicsLayer {
                            val sc = 1f - .15f * collapse.value.coerceIn(0f, 1f)
                            scaleX = sc
                            scaleY = sc
                        }
                        val coverRadius = if (round) 104.dp else 10.dp
                        if (loading && imageUrl == null) {
                            Bone(coverModifier, RoundedCornerShape(coverRadius), light = true)
                        } else {
                            Cover(imageUrl, coverModifier, radius = coverRadius, motion = true, sharedKey = source?.let(actions.nav::origin))
                        }
                        Spacer(Modifier.height(16.dp))
                        if (loading && title.isBlank()) {
                            Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                                Bone(Modifier.size(180.dp, 22.dp), light = true)
                            }
                        } else {
                            Text(
                                title, color = Color.White, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                Modifier.clip(CircleShape)
                                    .then(if (onSubtitle != null) Modifier.clickable { haptics.tick(); onSubtitle() } else Modifier)
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (loading && meta.isBlank()) {
                            Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                                Bone(Modifier.size(132.dp, 10.dp), light = true)
                            }
                        } else {
                            Text(
                                meta.uppercase(), color = Color.White.copy(.7f), fontSize = 12.sp, letterSpacing = 1.sp,
                                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            GlassIcon(Icons.Rounded.Shuffle, "Aleatório", {
                                haptics.press()
                                actions.play(all, all.indices.random(), shuffle = true, source = source, contextUri = contextUri)
                            }, enabled = all.isNotEmpty())
                            GlassBox(Modifier.height(48.dp).width(144.dp), CircleShape) {
                                Row(
                                    Modifier.matchParentSize().clickable(enabled = all.isNotEmpty()) {
                                        haptics.press()
                                        actions.play(all, source = source, contextUri = contextUri)
                                    },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Tocar", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                }
                            }
                            if (keepKey != null) {
                                GlassIcon(if (kept) Icons.Rounded.OfflinePin else Icons.Rounded.DownloadForOffline, if (kept) "Tirar do aparelho" else "Manter no aparelho", {
                                    haptics.press()
                                    actions.app.library.toggleKept(keepKey)
                                    android.widget.Toast.makeText(
                                        actions.app,
                                        if (kept) "Não fica mais no aparelho" else "Mantida no aparelho: baixa no Wi-Fi",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }, enabled = all.isNotEmpty())
                            }
                        }
                        if (kept && all.isNotEmpty()) {
                            Text(
                                if (onPhone >= all.size) "Todas no aparelho" else "$onPhone de ${all.size} no aparelho · baixando no Wi-Fi",
                                Modifier.padding(top = 10.dp),
                                color = Color.White.copy(.7f), fontSize = 12.sp,
                            )
                        }
                    }
                }
                if (loading && error == null) {
                    item(key = "tools") { TrackToolsSkeleton() }
                } else if (all.size > 1) {
                    item(key = "tools") {
                        TrackTools(query, { query = it }, sort, { sort = it }, shown.size, all.size, dated = all.any { it.addedAtMs != null })
                    }
                }
                if (loading) {
                    if (error != null) {
                        item(key = "error") { LoadError(error, onRetry) }
                    } else {
                        items(10, key = { "sk$it" }) { i ->
                            Column(Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = skeletonFade())) {
                                TrackRowSkeleton(i, Modifier.padding(horizontal = 16.dp), numbered = numbered)
                                HorizontalDivider(Modifier.padding(start = if (numbered) 66.dp else 86.dp, end = 16.dp), color = c.outlineVariant)
                            }
                        }
                    }
                }
                if (shown.isEmpty() && query.isNotBlank()) {
                    item(key = "none") {
                        Text(
                            "Nada encontrado para “${query.trim()}”",
                            Modifier.fillMaxWidth().padding(32.dp),
                            color = c.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                itemsIndexed(shown, key = { _, entry -> "${entry.index}:${entry.value.uri}" }) { i, entry ->
                    val track = entry.value
                    Column(Modifier.animateItem(fadeInSpec = skeletonFade(), placementSpec = null, fadeOutSpec = null)) {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            TrackRow(
                                track,
                                { actions.play(shownTracks, i, source = source, contextUri = rowContext) },
                                number = if (numbered) entry.index + 1 else null,
                                // The queue keeps the URI as listed; the playing one may be relinked.
                                playing = playingUris.first == track.uri || playingUris.second == track.uri,
                                onLongClick = { menuFor = track },
                                onSwipe = { actions.addToQueue(track) },
                            )
                        }
                        if (i < shown.lastIndex) {
                            HorizontalDivider(Modifier.padding(start = if (numbered) 66.dp else 86.dp, end = 16.dp), color = c.outlineVariant)
                        }
                    }
                }
                footer()
            }
        }
        DetailTopBar(title, collapse, back) { haptics.press(); actions.play(all, source = source, contextUri = contextUri) }
    }
}

enum class TrackSort(val label: String) {
    Original("Ordem original"),
    Added("Adicionadas recentemente"),
    Title("Título"),
    Artist("Artista"),
    Album("Álbum"),
    Duration("Duração"),
    ;

    fun apply(tracks: List<IndexedValue<Track>>): List<IndexedValue<Track>> = when (this) {
        Original -> tracks
        // Undated tracks (albums, artist pages) keep their order at the end.
        Added -> tracks.sortedByDescending { it.value.addedAtMs ?: Long.MIN_VALUE }
        Title -> tracks.sortedBy { fold(it.value.title) }
        Artist -> tracks.sortedWith(compareBy({ fold(it.value.artists) }, { fold(it.value.album) }, { it.index }))
        Album -> tracks.sortedWith(compareBy({ fold(it.value.album) }, { it.index }))
        Duration -> tracks.sortedBy { it.value.durationMs }
    }
}

/** Lower case without accents, for matching and sorting "Á" with "a". */
private fun fold(text: String): String =
    java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

/** Tracks whose title, artists or album contain every word of [query]. */
private fun filterTracks(tracks: List<Track>, query: String): List<IndexedValue<Track>> {
    val words = fold(query).split(' ').filter(String::isNotBlank)
    val indexed = tracks.withIndex().toList()
    if (words.isEmpty()) return indexed
    return indexed.filter { (_, t) ->
        val haystack = fold("${t.title} ${t.artists} ${t.album}")
        words.all(haystack::contains)
    }
}

/** Search field and sort menu above a track list. */
@Composable
private fun TrackTools(
    query: String,
    onQuery: (String) -> Unit,
    sort: TrackSort,
    onSort: (TrackSort) -> Unit,
    shown: Int,
    total: Int,
    /** Whether the tracks carry an added date (playlists, liked songs). */
    dated: Boolean,
) {
    val c = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(48.dp).clip(CircleShape).background(c.surfaceVariant).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
                BasicTextField(
                    query,
                    onQuery,
                    Modifier.weight(1f).padding(horizontal = 10.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                    cursorBrush = SolidColor(c.onSurface),
                    decorationBox = { field ->
                        if (query.isEmpty()) Text("Buscar nesta lista", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        field()
                    },
                )
                if (query.isNotEmpty()) {
                    IconButton({ onQuery("") }) { Icon(Icons.Rounded.Close, "Limpar", tint = c.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box {
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .background(if (sort != TrackSort.Original) c.primary else c.surfaceVariant)
                        .clickable { menu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Sort, "Ordenar",
                        tint = if (sort != TrackSort.Original) c.onPrimary else c.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(18.dp)) {
                    TrackSort.entries.filter { dated || it != TrackSort.Added }.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                menu = false
                                onSort(option)
                            },
                            trailingIcon = { if (option == sort) Icon(Icons.Rounded.Check, null) },
                        )
                    }
                }
            }
        }
        if (query.isNotBlank() || sort != TrackSort.Original) {
            Text(
                listOfNotNull(
                    if (query.isNotBlank()) "$shown de $total" else null,
                    if (sort != TrackSort.Original) "por ${sort.label.lowercase()}" else null,
                ).joinToString(" · "),
                color = c.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp),
            )
        }
    }
}

/** Same footprint as [TrackTools] while the list loads. */
@Composable
private fun TrackToolsSkeleton() {
    Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Bone(Modifier.weight(1f).height(48.dp), CircleShape)
        Spacer(Modifier.width(8.dp))
        Bone(Modifier.size(48.dp), CircleShape)
    }
}
