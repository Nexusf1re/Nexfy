package dev.nexfy.app.ui.screens

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.geometry.Offset
import dev.nexfy.app.ui.design.GlassBackdrop
import dev.nexfy.app.ui.design.LocalShellHeader
import dev.nexfy.app.ui.design.closesMenus
import dev.nexfy.app.ui.rememberUpdateOffer
import dev.nexfy.app.ui.theme.accentColor
import androidx.compose.foundation.border
import dev.nexfy.app.ui.design.awaitShown

import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.ui.design.glassMenuColor
import dev.nexfy.app.ui.design.glassMenuEdge
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.Route
import dev.nexfy.app.ui.LoadError
import dev.nexfy.app.ui.design.Bone
import dev.nexfy.app.ui.design.CollectionBackdrop
import dev.nexfy.app.ui.design.artInk
import dev.nexfy.app.ui.theme.LocalLook
import dev.nexfy.app.ui.theme.isDark
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.design.DetailTopBar
import dev.nexfy.app.ui.design.glassSource
import dev.nexfy.app.ui.design.GlassBox
import dev.nexfy.app.ui.design.GlassIcon
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.LocalPointer
import dev.nexfy.app.ui.design.RoundIconButton
import dev.nexfy.app.ui.design.TopBarHeight
import dev.nexfy.app.ui.design.TrackColumns
import dev.nexfy.app.ui.design.TrackRow
import dev.nexfy.app.ui.design.TrackRowSkeleton
import dev.nexfy.app.ui.design.TrackTable
import dev.nexfy.app.ui.design.TrackTableRow
import dev.nexfy.app.ui.design.skeletonFade
import dev.nexfy.app.ui.design.collapseOf
import dev.nexfy.app.ui.design.dragToDismiss
import dev.nexfy.app.ui.design.rememberHaptics
import uniffi.nexfy_core.Track
import uniffi.nexfy_core.UserFace

/** Contributors seen this run, so reopened playlists show them at once. */
private val knownFaces = java.util.concurrent.ConcurrentHashMap<String, UserFace>()

/**
 * Root tab header: a small wordmark and the settings button; a centred
 * small title appears once the large title scrolls away.
 */
@Composable
fun RootHeader(title: String, collapse: State<Float>, actions: AppActions) {
    // The window's frame carries the name and the buttons where there is one.
    if (LocalShellHeader.current) return
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
            Spacer(Modifier.width(8.dp))
            // A dot on the gear while a newer Nexfy waits in the settings.
            val update = rememberUpdateOffer(actions.services)
            Box {
                RoundIconButton(Icons.Rounded.Settings, if (update != null) "Ajustes, atualização disponível" else "Ajustes", { actions.open(Route.Settings) }, container = c.background)
                if (update != null) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(10.dp)
                            .clip(CircleShape).background(accentColor()).border(2.dp, c.background, CircleShape),
                    )
                }
            }
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
    /** The playlist is the user's own: its songs can be removed from their menus. */
    ownPlaylist: String? = null,
    /** A small photo before the subtitle (the playlist's creator). */
    subtitleImage: String? = null,
    /** The page's own menu (save, edit, share), behind a button by the play button. */
    onMore: (() -> Unit)? = null,
    footer: LazyListScope.() -> Unit = {},
) {
    val c = MaterialTheme.colorScheme
    // Header colours over the backdrop: white on dark art, ink on the light theme's pale wash.
    val dark = c.isDark
    val ink = artInk()
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
    val playbackState = actions.services.bridge.state.collectAsState()
    val playingUris by remember { derivedStateOf { playbackState.value.queue.current?.uri to playbackState.value.track?.uri } }
    // Kept on the phone: downloaded on Wi-Fi; count what is there already.
    val keptAll by actions.services.library.kept.collectAsState()
    val keepKey = source?.takeIf { it.startsWith("pl:") || it.startsWith("al:") }
    val kept = keepKey != null && keepKey in keptAll
    var onPhone by remember { mutableIntStateOf(0) }
    LaunchedEffect(kept, all.size) {
        while (kept && all.isNotEmpty()) {
            onPhone = actions.core.cachedCount(all.map { it.uri }).toInt()
            delay(4_000)
            awaitShown()
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    // A window asks for the search before it gives the field: a button in the
    // action row, as every desktop player has it. A phone keeps its own field
    // standing, where a thumb can always reach it.
    val pointer = LocalPointer.current
    var searching by rememberSaveable { mutableStateOf(false) }
    // Each list keeps the order last chosen for it, across launches.
    val library = actions.services.library
    val edits = actions.services.edits
    var sort by remember(source) {
        mutableStateOf(source?.let(library::trackSort)?.let { saved -> TrackSort.entries.firstOrNull { it.name == saved } } ?: TrackSort.Original)
    }
    LaunchedEffect(query.isNotBlank()) { onFiltering(query.isNotBlank()) }
    // What the list shows (and what plays from it), with each track's position in the original.
    // tracks.size: liked songs grow the same list instance page by page.
    val shown = remember(all, all.size, query, sort) { sort.apply(filterTracks(all, query)) }
    val shownTracks = remember(shown) { shown.map { it.value } }
    // A filtered or re-sorted view plays as an explicit list, not as the context.
    val rowContext = contextUri.takeIf { query.isBlank() && sort == TrackSort.Original }
    val shuffleSaved by library.shuffle.collectAsState()
    val playingHere by remember(contextUri) {
        derivedStateOf { contextUri != null && playbackState.value.queue.contextUri == contextUri }
    }
    // While this list plays, the button shows what the player does, whoever changed it.
    val playerShuffle by remember { derivedStateOf { playbackState.value.queue.shuffle } }
    val shuffleOn = if (playingHere) playerShuffle else shuffleSaved
    // Shuffled, a new random first song each time; the player shuffles the rest.
    val playAll = {
        actions.play(all, if (shuffleOn) all.indices.random() else 0, shuffle = shuffleOn, source = source, contextUri = contextUri)
    }
    var menuFor by remember { mutableStateOf<Track?>(null) }
    // Where the menu was asked for, in window pixels; null for a long press,
    // which has no point of its own and keeps the phone's sheet.
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    menuFor?.let { t ->
        TrackMenu(t, actions, ownPlaylist, all.indexOf(t).takeIf { it >= 0 }, menuAt) { menuFor = null }
    }
    // Who added each song, shown only where more than one person adds (and
    // "Mostrar quem adicionou" is on: off, the faces are not even looked up).
    val addedByOn = LocalLook.current.showAddedBy
    val contributors = remember(all, all.size) { all.mapNotNull { it.addedBy }.distinct() }
    var faces by remember(contributors) { mutableStateOf(contributors.mapNotNull { knownFaces[it] }.associateBy { it.id }) }
    LaunchedEffect(contributors, addedByOn) {
        if (addedByOn && contributors.size > 1 && contributors.any { it !in faces }) {
            val loaded = actions.core.users(contributors.take(MAX_FACES))
            loaded.forEach { knownFaces[it.id] = it }
            faces = loaded.associateBy { it.id }
        }
    }
    val showFaces = addedByOn && contributors.size > 1
    // What a table of these songs can label: no album column when they all
    // come from the same one, no date column where nothing is dated.
    val afforded = remember(all, all.size, showFaces) {
        TrackColumns(
            album = all.asSequence().map { it.album }.distinct().take(2).count() > 1,
            date = all.any { it.addedAtMs != null },
            addedBy = showFaces,
        )
    }

    // The page's own glass backdrop: the top bar floats over it as liquid glass.
    val pageGlass = remember { GlassBackdrop() }
    Box(modifier) {
        Box(Modifier.fillMaxSize().glassSource(pageGlass)) {
            CollectionBackdrop(imageUrl, collapse, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().dragToDismiss(dismiss, back)) {
                BoxWithConstraints {
                // A mouse and room for real columns: the songs are listed as
                // a table, the way a window lists them. A finger, or a window
                // this narrow, keeps the rows a phone is built around.
                val table = LocalPointer.current && maxWidth >= TableWidth
                // Narrowing the window, or opening the side panel, gives up
                // room without giving up a fact: the album folds under the
                // title first (as Lokal's table does), then the date takes its
                // short form. Both used to vanish, the date first.
                val columns = when {
                    maxWidth >= DateColumnWidth -> afforded
                    maxWidth >= AlbumColumnWidth -> afforded.copy(album = false, albumBelow = afforded.album)
                    else -> afforded.copy(album = false, albumBelow = afforded.album, shortDate = true)
                }
                // The table carries a heart on each row: one lookup answers
                // the whole list, out of the saved set the core already keeps.
                LaunchedEffect(table, all.size) { if (table) edits.load(all.map { it.uri }) }
                LazyColumn(
                    Modifier.fillMaxSize().closesMenus(),
                    state = list,
                    contentPadding = PaddingValues(bottom = LocalBottomSpace.current),
                ) {
                    item(key = "header") {
                        BoxWithConstraints {
                        // A phone's column can only stack the cover above the
                        // title; a window has room beside it, which is where
                        // every desktop player puts it.
                        val wide = maxWidth >= 640.dp
                        val coverSize = if (wide) 232.dp else 208.dp
                        val align = if (wide) Alignment.Start else Alignment.CenterHorizontally
                        val textAlign = if (wide) TextAlign.Start else TextAlign.Center
                        Column(
                            Modifier.fillMaxWidth().statusBarsPadding()
                                .padding(top = if (wide) 24.dp else TopBarHeight)
                                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                                .graphicsLayer { alpha = (1f - collapse.value * 1.5f).coerceIn(0f, 1f) },
                            horizontalAlignment = align,
                        ) {
                            val coverModifier = Modifier.size(coverSize).graphicsLayer {
                                val sc = 1f - .15f * collapse.value.coerceIn(0f, 1f)
                                scaleX = sc
                                scaleY = sc
                            }
                            val coverRadius = if (round) coverSize / 2 else 10.dp
                            val cover = @Composable {
                                if (loading && imageUrl == null) {
                                    Bone(coverModifier, RoundedCornerShape(coverRadius), light = dark)
                                } else {
                                    Cover(imageUrl, coverModifier, radius = coverRadius, motion = true, sharedKey = source?.let(actions.nav::origin))
                                }
                            }
                            val naming = @Composable {
                                if (wide && meta.isNotBlank()) {
                                    Text(
                                        meta.substringBefore(" \u00b7 ").uppercase(), color = ink.copy(.7f), fontSize = 12.sp,
                                        letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                                if (loading && title.isBlank()) {
                                    Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                                        Bone(Modifier.size(180.dp, 22.dp), light = dark)
                                    }
                                } else {
                                    Text(
                                        title, color = ink,
                                        fontSize = if (wide) 44.sp else 24.sp,
                                        lineHeight = if (wide) 48.sp else 28.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = textAlign, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                if (!subtitle.isNullOrBlank()) {
                                    Row(
                                        Modifier.clip(CircleShape)
                                            .then(if (onSubtitle != null) Modifier.clickable { haptics.tick(); onSubtitle() } else Modifier)
                                            .padding(horizontal = if (wide) 0.dp else 12.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (subtitleImage != null) {
                                            dev.nexfy.app.ui.design.UserAvatar(UserFace("", subtitle, subtitleImage), Modifier.size(22.dp))
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(
                                            subtitle,
                                            color = ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (loading && meta.isBlank()) {
                                    Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                                        Bone(Modifier.size(132.dp, 10.dp), light = dark)
                                    }
                                } else {
                                    Text(
                                        if (wide) meta.substringAfter(" \u00b7 ", meta).trim().uppercase() else meta.uppercase(),
                                        color = ink.copy(.7f), fontSize = 12.sp, letterSpacing = 1.sp,
                                        textAlign = textAlign, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (wide) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    cover()
                                    Column(Modifier.padding(start = 24.dp).weight(1f).padding(bottom = 8.dp)) { naming() }
                                }
                            } else {
                                cover()
                                Spacer(Modifier.height(16.dp))
                                naming()
                            }
                            Spacer(Modifier.height(if (wide) 24.dp else 16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                // A toggle, as in Spotify: on, "Tocar" plays shuffled; flipped while
                                // this list plays, the player follows.
                                GlassIcon(Icons.Rounded.Shuffle, if (shuffleOn) "Aleatório ligado" else "Aleatório desligado", {
                                    haptics.tick()
                                    val on = !shuffleOn
                                    if (playingHere) actions.setShuffle(on) else library.setShuffle(on)
                                }, enabled = all.isNotEmpty(), light = !dark, selected = shuffleOn)
                                GlassBox(Modifier.height(48.dp).width(144.dp), CircleShape, dark = dark) {
                                    Row(
                                        Modifier.matchParentSize().clickable(enabled = all.isNotEmpty()) {
                                            haptics.press()
                                            playAll()
                                        },
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, null, tint = ink)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Tocar", color = ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                    }
                                }
                                if (pointer && all.size > 1) {
                                    if (searching) {
                                        ListSearch(query, { query = it }, ink, dark) { searching = false }
                                    } else {
                                        GlassIcon(Icons.Rounded.Search, "Buscar nesta lista", { searching = true }, light = !dark, selected = query.isNotBlank())
                                    }
                                }
                                if (keepKey != null) {
                                    GlassIcon(if (kept) Icons.Rounded.OfflinePin else Icons.Rounded.DownloadForOffline, if (kept) "Tirar do aparelho" else "Manter no aparelho", {
                                        haptics.press()
                                        actions.services.library.toggleKept(keepKey)
                                        actions.services.toast(
                                            if (kept) "Não fica mais no aparelho" else "Mantida no aparelho: baixa no Wi-Fi",
                                        )
                                    }, enabled = all.isNotEmpty(), light = !dark)
                                }
                                onMore?.let { more ->
                                    GlassIcon(Icons.Rounded.MoreHoriz, "Mais opções", { haptics.tick(); more() }, light = !dark)
                                }
                            }
                            if (kept && all.isNotEmpty()) {
                                Text(
                                    if (onPhone >= all.size) "Todas no aparelho" else "$onPhone de ${all.size} no aparelho · baixando no Wi-Fi",
                                    Modifier.padding(top = 10.dp),
                                    color = ink.copy(.7f), fontSize = 12.sp,
                                )
                            }
                        }
                        }
                    }
                    if (loading && error == null) {
                        item(key = "tools") { TrackToolsSkeleton() }
                    } else if (all.size > 1) {
                        item(key = "tools") {
                            TrackTools(query, { query = it }, sort, { new ->
                                sort = new
                                source?.let { library.setTrackSort(it, new.name) }
                            }, shown.size, all.size, dated = columns.date, standing = !pointer)
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
                    if (table && shown.isNotEmpty()) {
                        item(key = "columns") { TrackTable(columns, Modifier.padding(horizontal = 16.dp)) }
                    }
                    itemsIndexed(shown, key = { _, entry -> "${entry.index}:${entry.value.uri}" }) { i, entry ->
                        val track = entry.value
                        // The queue keeps the URI as listed; the playing one may be relinked.
                        val playing = playingUris.first == track.uri || playingUris.second == track.uri
                        val play = { actions.play(shownTracks, i, shuffle = shuffleOn, source = source, contextUri = rowContext) }
                        val addedBy = if (showFaces) track.addedBy?.let(faces::get) else null
                        Column(Modifier.animateItem(fadeInSpec = skeletonFade(), placementSpec = reorderSpring, fadeOutSpec = null)) {
                            if (table) {
                                val liked = edits.saved[track.uri] == true
                                TrackTableRow(
                                    track,
                                    // The place in the list as it was given, whatever this view shows.
                                    number = entry.index + 1,
                                    columns = columns,
                                    onClick = play,
                                    onMenu = { at -> menuAt = at; menuFor = track },
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    playing = playing,
                                    liked = liked,
                                    onLike = {
                                        edits.setSaved(track.uri, !liked, if (liked) "Removida das curtidas" else "Adicionada às curtidas")
                                    },
                                    addedBy = addedBy,
                                )
                            } else {
                                Box(Modifier.padding(horizontal = 16.dp)) {
                                    TrackRow(
                                        track,
                                        play,
                                        number = if (numbered) entry.index + 1 else null,
                                        playing = playing,
                                        onLongClick = { menuAt = null; menuFor = track },
                                        onSwipe = { actions.addToQueue(track) },
                                        addedBy = addedBy,
                                    )
                                }
                                if (i < shown.lastIndex) {
                                    HorizontalDivider(Modifier.padding(start = if (numbered) 66.dp else 86.dp, end = 16.dp), color = c.outlineVariant)
                                }
                            }
                        }
                    }
                    footer()
                }
                }
            }
        }
        DetailTopBar(title, collapse, back, pageGlass) { haptics.press(); playAll() }
    }
}

/** Most contributors looked up for one playlist. */
private const val MAX_FACES = 60

/**
 * Narrower than this even a table of number, title and length is too tight,
 * and the list stays the column of rows a phone is built around. A 1100px
 * window with the panel open and the library kept wide leaves the page about
 * 480dp, which is the case this number exists to let through: the point of
 * shedding columns is that asking for the words does not cost the table.
 */
private val TableWidth = 440.dp

/** Below this the date takes its short form, beside the title with the album under it. */
private val AlbumColumnWidth = 600.dp

/**
 * Wide enough for every column at its full size. A 1100px window gives the
 * page about 815dp with the sidebar open, and the whole set reads cleanly
 * there; below it, as when the panel opens, the album folds under the title.
 */
private val DateColumnWidth = 760.dp

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
internal fun fold(text: String): String =
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

/**
 * The per-list search as a window shows it: a glass field in the header's
 * action row, there only while it is in use. [onClose] gives the button its
 * place back, which Esc and leaving an empty field behind also do.
 */
@Composable
private fun RowScope.ListSearch(
    query: String,
    onQuery: (String) -> Unit,
    ink: Color,
    dark: Boolean,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    // Asking for the search is asking to type: the caret is already there.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // The field reports itself unfocused before the caret reaches it, which
    // must not count as leaving it: only a field that had the caret can.
    var had by remember { mutableStateOf(false) }
    GlassBox(Modifier.weight(1f).widthIn(max = 360.dp).height(48.dp), CircleShape, dark = dark) {
        Row(Modifier.matchParentSize().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = ink.copy(.7f), modifier = Modifier.size(20.dp))
            BasicTextField(
                query,
                onQuery,
                Modifier.weight(1f).padding(horizontal = 10.dp).focusRequester(focus)
                    // Clicking away from a field with nothing in it was never
                    // a search: the row takes its button back.
                    .onFocusChanged {
                        if (it.isFocused) had = true else if (had && query.isBlank()) onClose()
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onQuery("")
                            onClose()
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
                cursorBrush = SolidColor(ink),
                decorationBox = { field ->
                    if (query.isEmpty()) Text("Buscar nesta lista", color = ink.copy(.6f), style = MaterialTheme.typography.bodyLarge)
                    field()
                },
            )
            IconButton({ onQuery(""); onClose() }) { Icon(Icons.Rounded.Close, "Fechar a busca", tint = ink.copy(.7f)) }
        }
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
    /** The field stands here; off, the header's button carries the search. */
    standing: Boolean,
) {
    val c = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!standing) {
                // Only the order is left to set here; the search went up to the header.
                Spacer(Modifier.weight(1f))
            } else {
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
            }
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
                DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(18.dp), containerColor = glassMenuColor(), shadowElevation = 18.dp, border = glassMenuEdge()) {
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

/** Rows glide to their new place when the order or the filter changes. */
private val reorderSpring = androidx.compose.animation.core.spring(
    dampingRatio = .86f,
    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
    visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold,
)
