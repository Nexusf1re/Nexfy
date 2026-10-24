package dev.nexfy.app.ui.screens

import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.rounded.History
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.nexfy.app.ui.theme.isDark
import dev.nexfy.app.ui.design.glassPane
import dev.nexfy.app.ui.design.Motion
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.Listen
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.Peek
import dev.nexfy.app.ui.Route
import dev.nexfy.app.ui.design.Carousel
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.design.CoverCard
import dev.nexfy.app.ui.design.EmptyState
import dev.nexfy.app.ui.design.LargeTitle
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.LocalCardWidth
import dev.nexfy.app.ui.design.LocalPointer
import dev.nexfy.app.ui.design.PillButton
import dev.nexfy.app.ui.design.PillSelector
import dev.nexfy.app.ui.design.SectionTitle
import dev.nexfy.app.ui.design.TrackColumns
import dev.nexfy.app.ui.design.TrackRow
import dev.nexfy.app.ui.design.TrackRowSkeleton
import dev.nexfy.app.ui.design.TrackTable
import dev.nexfy.app.ui.design.TrackTableRow
import dev.nexfy.app.ui.design.gridColumns
import dev.nexfy.app.ui.design.springPress
import dev.nexfy.app.ui.design.skeletonFade
import dev.nexfy.app.ui.design.cascade
import dev.nexfy.app.ui.design.rememberCascade
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.design.rememberTitleCollapse
import dev.nexfy.app.ui.design.weightedPress
import dev.nexfy.app.ui.theme.accentColor
import uniffi.nexfy_core.SearchResults
import uniffi.nexfy_core.Track
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** The salutation alone, where there is no room for the line under it. */
internal fun greeting(): String = clockRung(LocalTime.now().hour, null).headline

/**
 * The home's two first lines. One rung of the ladder writes both, so the
 * title can never claim one thing and the line below it another.
 */
private data class Greeting(val headline: String, val line: String)

/** Further ahead of now than this, a play's stamp is a wrong clock. */
private const val FutureSlack = 5 * 60 * 1000L

/**
 * The home's first words: what this person's listening has been like, not
 * what time it is. The first rung that fires writes both lines, the clock
 * being only the last resort; a rung names a pattern and never a song, an
 * artist or an album, and nothing is read but the play log already on disk.
 *
 * The reference app has two more rungs, for a calm and for an energetic
 * stretch, which weigh each play's genre. No play here carries one, and the
 * core exposes no genre at all, so they are left out rather than guessed at
 * from some other field.
 */
private fun greetingFor(plays: List<Listen>, name: String?, now: Long, zone: ZoneId): Greeting {
    // The log is kept oldest first, so the newest plays are its tail: reading
    // forty of them stays forty steps however long the log has grown. A play
    // stamped before the epoch or well ahead of now is a clock that was
    // wrong, and says nothing about anybody's listening.
    val recent = plays.asReversed().asSequence()
        .filter { it.at > 0L && it.at <= now + FutureSlack }
        .take(40)
        .toList()
    val clock = Instant.ofEpochMilli(now).atZone(zone)
    if (recent.size < 5) return clockRung(clock.hour, name)

    // One song three times over the last eight plays.
    if (recent.take(8).groupingBy { it.uri }.eachCount().any { (_, times) -> times >= 3 }) {
        return Greeting("Direto no repeat", "O botão de repetir não tem tido descanso.")
    }

    // Wall-clock hour of each play, over a window wide enough that one late
    // night is not a habit: six plays of it, and half of what was played.
    val lately = recent.take(30)
    val hours = lately.map { Instant.ofEpochMilli(it.at).atZone(zone).hour }
    val night = hours.count { it >= 22 || it < 5 }
    if (night >= 6 && night * 2 >= lately.size) {
        return Greeting("Coruja", "Teu ritmo de ouvir tarde da noite segue firme.")
    }
    val early = hours.count { it in 5..8 }
    if (early >= 6 && early * 2 >= lately.size) {
        return Greeting("Madrugador", "As primeiras horas do dia vêm com trilha sonora.")
    }

    // Four of the last seven days with something played on them.
    val week = clock.toLocalDate().minusDays(6)
    val days = recent.asSequence()
        .map { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
        .filter { !it.isBefore(week) }
        .distinct()
        .count()
    if (days >= 4) return Greeting("No embalo", "Teus dias de música vêm emendando um no outro.")

    // Whoever is credited first, not the whole credit line the log keeps: a
    // name that only ever appears beside another would otherwise pass for
    // variety of its own. Eight named plays, four fifths of them different.
    val credited = recent.map { it.artists.substringBefore(',').trim().lowercase() }.filter { it.isNotEmpty() }
    if (credited.size >= 8 && credited.distinct().size * 5 >= credited.size * 4) {
        return Greeting("Rotação variada", "Tem passado bastante coisa diferente na tua rotação.")
    }

    return clockRung(clock.hour, name)
}

/** The last resort, and the one rung that greets by name. */
private fun clockRung(hour: Int, name: String?): Greeting {
    val (salutation, line) = when (hour) {
        in 5..11 -> "Bom dia" to "Boa hora para começar o dia com música."
        in 12..17 -> "Boa tarde" to "Escolhe algo para a tarde seguir."
        in 18..22 -> "Boa noite" to "A noite fica melhor com algo tocando."
        else -> "Boa noite" to "Companhia para as horas tardias."
    }
    return Greeting(if (name != null) "$salutation, $name" else salutation, line)
}

/**
 * The home's headline and the line under it, from the play log. Both come
 * from the same rung, and the work is forty steps over a list already in
 * time order, which is why it can sit in a [remember] instead of going off
 * to another thread.
 */
@Composable
internal fun HomeGreeting(name: String?, actions: AppActions, collapse: State<Float>) {
    val plays by actions.services.listens.collectAsState()
    val said = remember(plays, name) { greetingFor(plays, name, System.currentTimeMillis(), ZoneId.systemDefault()) }
    Column {
        LargeTitle(said.headline, collapse)
        // The line goes with the title it belongs to, read as it draws so that
        // scrolling moves it without recomposing the words.
        Text(
            said.line,
            Modifier.graphicsLayer { alpha = 1f - collapse.value.coerceIn(0f, 1f) }.padding(top = 2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            lineHeight = 20.sp,
        )
    }
}

/** Shown when the Web API is not signed in yet. */
@Composable
internal fun ConnectPrompt(actions: AppActions) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    EmptyState(Icons.Rounded.LibraryMusic, "Conecte a biblioteca", "Entre com o app pessoal e o compartilhado em Ajustes para ver tuas playlists e buscar.")
    PillButton("Abrir ajustes", { actions.open(Route.Settings) })
}

/** The sign-in prompt only when no Web API app is connected; any other failure says what happened. */
@Composable
internal fun ConnectOrError(error: String?, actions: AppActions, onRetry: () -> Unit) {
    if (error?.contains("nenhum app da Web API") == true) ConnectPrompt(actions) else dev.nexfy.app.ui.LoadError(error, onRetry)
}

@Composable
fun SearchScreen(actions: AppActions, list: LazyListState) {
    val c = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val collapse = rememberTitleCollapse(list)
    // Held by the nav state: the window's title bar searches through the same
    // field (see NavState.searchQuery), and the query outlives this screen.
    var query by actions.nav::searchQuery
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var searching by remember { mutableStateOf(false) }
    // The window's title strip already carries a field on this same query, an
    // inch above this screen's own, so here the screen does not draw a second
    // one; a phone has no strip and keeps its field.
    val pointer = LocalPointer.current
    var error by remember { mutableStateOf<String?>(null) }
    val cascadeClock = rememberCascade(results)
    var menuFor by remember { mutableStateOf<uniffi.nexfy_core.Track?>(null) }
    val recent by actions.services.library.recentSearches.collectAsState()
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
            results = actions.core.search(q).also { found ->
                if (found.tracks.isNotEmpty() || found.artists.isNotEmpty() || found.albums.isNotEmpty() || found.playlists.isNotEmpty()) {
                    actions.services.library.addRecentSearch(q)
                }
            }
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
            // What the field's own sweep says on a phone: a search is on its way.
            if (pointer) item(key = "working") { SearchProgress(searching) }
            if (!pointer) item(key = "field") {
                // A pane of glass that lights up in the accent while focused; a
                // light runs along its lower edge while a search is on its way.
                val focus = remember { MutableInteractionSource() }
                val focused by focus.collectIsFocusedAsState()
                val glow = animateFloatAsState(if (focused) 1f else 0f, Motion.standard(), label = "focus")
                val accent = accentColor()
                val icon by animateColorAsState(if (focused) accent else c.onSurfaceVariant, Motion.standard(), label = "icon")
                val sweep = remember { Animatable(0f) }
                LaunchedEffect(searching) {
                    while (searching) {
                        sweep.snapTo(0f)
                        sweep.animateTo(1f, tween(Motion.scaled(950), easing = LinearEasing))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp).height(52.dp)
                        .glassPane(CircleShape, c.surfaceVariant.copy(alpha = .75f), c.isDark)
                        .drawWithContent {
                            drawContent()
                            val g = glow.value
                            if (g > 0f) {
                                drawRoundRect(
                                    accent.copy(alpha = .75f * g), cornerRadius = CornerRadius(size.height / 2),
                                    style = Stroke(1.5.dp.toPx()),
                                )
                            }
                            if (searching) {
                                val w = size.width * .35f
                                val x = sweep.value * (size.width + w) - w
                                val line = 2.dp.toPx()
                                drawRect(
                                    Brush.horizontalGradient(listOf(Color.Transparent, accent, Color.Transparent), startX = x, endX = x + w),
                                    topLeft = Offset(0f, size.height - line), size = Size(size.width, line),
                                )
                            }
                        }
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = icon)
                    BasicTextField(
                        query, { query = it }, Modifier.weight(1f).padding(horizontal = 12.dp), singleLine = true,
                        interactionSource = focus,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                        cursorBrush = SolidColor(c.onSurface),
                        decorationBox = { field ->
                            if (query.isEmpty()) {
                                Text("Músicas, artistas, álbuns", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            }
                            field()
                        },
                    )
                    if (query.isNotEmpty()) {
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
                if (query.isBlank() && recent.isNotEmpty()) {
                    item(key = "recent-title") { SectionTitle("Buscas recentes", Modifier.padding(start = 4.dp)) }
                    items(recent, key = { "recent:$it" }) { past ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { query = past }
                                .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.History, null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(past, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton({ actions.services.library.removeRecentSearch(past) }) {
                                Icon(Icons.Rounded.Close, "Remover", tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                } else if (query.isBlank()) {
                    item(key = "hint") {
                        Text(
                            if (pointer) "Clique num resultado para ouvir na hora." else "Toque num resultado para ouvir na hora.",
                            color = c.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            } else if (r.tracks.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty() && r.playlists.isEmpty()) {
                item(key = "none") { EmptyState(Icons.Rounded.SearchOff, "Nada encontrado para “${query.trim()}”", "Confira a grafia ou tente o nome do artista.") }
            } else if (pointer) {
                // Ten of each at most, so the whole arrangement is one item of
                // the list rather than a page of items to keep track of.
                item(key = "window") { WindowResults(r, actions) { menuFor = it } }
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
                                    CoverCard(a.imageUrl, a.name, "Artista", "ar-${a.id}", circle = true, shared = "search-ar/${a.id}") { actions.open(Route.Artist(a.id), Peek(a.name, imageUrl = a.imageUrl), "search-ar/${a.id}") }
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
                                    CoverCard(a.imageUrl, a.name, a.artists, "al-${a.id}", shared = "search-al/${a.id}") { actions.open(Route.Album(a.id), Peek(a.name, a.artists, a.imageUrl), "search-al/${a.id}") }
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
                                    CoverCard(p.imageUrl, p.name, p.owner, "pl-${p.id}", shared = "search-pl/${p.id}") { actions.open(Route.Playlist(p.id), Peek(p.name, p.owner, p.imageUrl), "search-pl/${p.id}") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The gap between the result cards, across and down. */
private val CardGap = 16.dp

/** A card in a window's grid of results, whatever kind of thing it stands for. */
private data class ResultCard(
    val key: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val shared: String,
    val open: () -> Unit,
)

/**
 * The results as a window lists them: each kind under its own label, the
 * card kinds in a grid that divides the width, and the songs as the table
 * the collections use, with a way to play the lot. A phone's one column of
 * rows is still the phone's.
 */
@Composable
private fun WindowResults(r: SearchResults, actions: AppActions, onMenu: (Track) -> Unit) {
    val edits = actions.services.edits
    // One lookup answers the heart on every row, out of the saved set the core keeps.
    LaunchedEffect(r) { edits.load(r.tracks.map { it.uri }) }
    val playback = actions.services.bridge.state.collectAsState()
    // Only the playing song's URIs: a row recomposes when the song changes,
    // not on every playback update.
    val playing by remember { derivedStateOf { playback.value.queue.current?.uri to playback.value.track?.uri } }
    Column {
        if (r.tracks.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Músicas", Modifier.weight(1f).padding(start = 4.dp))
                PlayAll { actions.play(r.tracks, 0) }
            }
            // Songs from all over the catalogue, and nothing dated: the album
            // is worth a column, the added-at date has nothing to put in one.
            val columns = remember { TrackColumns(album = true, date = false) }
            TrackTable(columns)
            r.tracks.forEachIndexed { i, track ->
                val liked = edits.saved[track.uri] == true
                TrackTableRow(
                    track,
                    number = i + 1,
                    columns = columns,
                    onClick = { actions.play(r.tracks, i) },
                    onMenu = { onMenu(track) },
                    playing = playing.first == track.uri || playing.second == track.uri,
                    liked = liked,
                    onLike = {
                        edits.setSaved(track.uri, !liked, if (liked) "Removida das curtidas" else "Adicionada às curtidas")
                    },
                )
            }
        }
        CardSection(
            "Artistas",
            r.artists.map { a ->
                ResultCard("ar-${a.id}", a.name, "Artista", a.imageUrl, "search-ar/${a.id}") {
                    actions.open(Route.Artist(a.id), Peek(a.name, imageUrl = a.imageUrl), "search-ar/${a.id}")
                }
            },
            circle = true,
        )
        CardSection(
            "Álbuns",
            r.albums.map { a ->
                ResultCard("al-${a.id}", a.name, a.artists, a.imageUrl, "search-al/${a.id}") {
                    actions.open(Route.Album(a.id), Peek(a.name, a.artists, a.imageUrl), "search-al/${a.id}")
                }
            },
        )
        CardSection(
            "Playlists",
            r.playlists.map { p ->
                ResultCard("pl-${p.id}", p.name, p.owner, p.imageUrl, "search-pl/${p.id}") {
                    actions.open(Route.Playlist(p.id), Peek(p.name, p.owner, p.imageUrl), "search-pl/${p.id}")
                }
            },
        )
    }
}

/** One labelled kind of card result; nothing at all when none came back. */
@Composable
private fun CardSection(title: String, cards: List<ResultCard>, circle: Boolean = false) {
    if (cards.isEmpty()) return
    SectionTitle(title, Modifier.padding(start = 4.dp))
    BoxWithConstraints {
        // As many as fit the width, which they then divide between them, the
        // way the shelves and the library's grid do.
        val columns = gridColumns(maxWidth)
        val card = (maxWidth - CardGap * (columns - 1)) / columns
        CompositionLocalProvider(LocalCardWidth provides card) {
            Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
                cards.chunked(columns).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                        line.forEach { entry ->
                            CoverCard(
                                entry.imageUrl, entry.title, entry.subtitle, entry.key,
                                circle = circle, shared = entry.shared, onClick = entry.open,
                            )
                        }
                        // A short last line keeps the columns of the lines above.
                        repeat(columns - line.size) { Spacer(Modifier.width(card)) }
                    }
                }
            }
        }
    }
}

/** "Tocar todas", in the accent, at the end of the songs' label. */
@Composable
private fun PlayAll(onClick: () -> Unit) {
    val accent = accentColor()
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.springPress(source).clip(CircleShape).clickable(source, null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PlayArrow, null, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("Tocar todas", color = accent, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A light crossing the page while a search runs, in place of the sweep along
 * the field this screen does not draw under a pointer. It keeps its 2 dp
 * whether it shows or not, so nothing below it moves when a search starts.
 */
@Composable
private fun SearchProgress(searching: Boolean) {
    val accent = accentColor()
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(searching) {
        while (searching) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(Motion.scaled(950), easing = LinearEasing))
        }
    }
    Box(
        Modifier.fillMaxWidth().height(2.dp).drawWithContent {
            drawContent()
            if (!searching) return@drawWithContent
            val w = size.width * .35f
            val x = sweep.value * (size.width + w) - w
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, accent, Color.Transparent), startX = x, endX = x + w),
            )
        },
    )
}
