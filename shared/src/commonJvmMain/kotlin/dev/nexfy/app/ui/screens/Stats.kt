package dev.nexfy.app.ui.screens

import androidx.compose.runtime.LaunchedEffect
import dev.nexfy.app.ui.rememberLoaded
import dev.nexfy.app.contextKey
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.Listen
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.Peek
import dev.nexfy.app.ui.Route
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.design.EmptyState
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.PillSelector
import dev.nexfy.app.ui.design.RoundIconButton
import dev.nexfy.app.ui.design.SectionTitle
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.theme.accentColor
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import uniffi.nexfy_core.Track

private enum class Period(val label: String, val days: Long?) {
    Week("Semana", 7),
    Month("Mês", 30),
    All("Sempre", null),
}

private data class SongStat(val last: Listen, val plays: Int, val ms: Long)

private data class ArtistStat(val name: String, val id: String?, val ms: Long, val plays: Int = 0)

private data class AlbumStat(val name: String, val artists: String, val imageUrl: String?, val ms: Long, val plays: Int)

/** A playlist, album or Liked Songs the music was played from. */
private data class ContextStat(val key: String, val ms: Long, val plays: Int, val fallback: Listen)

private data class Bar(val label: String, val ms: Long, val current: Boolean)

/** The short lines above the charts: a streak, a best day, songs met for the first time. */
private data class Highlights(val streak: Int, val bestDay: LocalDate?, val bestMs: Long, val fresh: Int)

private data class Stats(
    val ms: Long,
    val songs: List<SongStat>,
    val artists: List<ArtistStat>,
    val albums: List<AlbumStat>,
    val contexts: List<ContextStat>,
    val bars: List<Bar>,
    /** Listening by hour of the day, in pairs of hours. */
    val hours: List<Bar>,
    val highlights: Highlights,
    /** Days the period covers, for the daily average. */
    val days: Long,
)

private const val TOP = 10

/** Initials of the weekdays and months, as Brazilian calendars print them. */
private fun weekday(day: DayOfWeek) = "STQQSSD"[day.value - 1].toString()
private fun month(m: Int) = "JFMAMJJASOND"[m - 1].toString()

/** Artists of a listen with their ids, when names and ids line up. */
private fun artistsOf(l: Listen): List<Pair<String, String?>> {
    val names = l.artists.split(", ").filter { it.isNotBlank() }
    return when {
        names.size == l.artistIds.size -> names.zip(l.artistIds)
        // A name with a comma in it ("Tyler, The Creator"): one artist, one id.
        l.artistIds.size == 1 -> listOf(l.artists to l.artistIds[0])
        else -> names.map { it to null }
    }
}

private fun stats(all: List<Listen>, period: Period, zone: ZoneId): Stats {
    val today = LocalDate.now(zone)
    val dayOf = { l: Listen -> Instant.ofEpochMilli(l.at).atZone(zone).toLocalDate() }
    val start = period.days?.let { today.minusDays(it - 1) }
    val listens = if (start == null) all else all.filter { !dayOf(it).isBefore(start) }

    val songs = listens.groupBy { it.uri }
        .map { (_, plays) -> SongStat(plays.last(), plays.size, plays.sumOf { it.ms }) }
        .sortedWith(compareByDescending<SongStat> { it.plays }.thenByDescending { it.ms })
    val byArtist = HashMap<String, ArtistStat>()
    for (l in listens) {
        val credits = artistsOf(l)
        for ((name, id) in credits) {
            val key = id ?: name.lowercase()
            val old = byArtist[key]
            // A song shared by several artists counts fully for each of them.
            byArtist[key] = ArtistStat(old?.name ?: name, id ?: old?.id, (old?.ms ?: 0) + l.ms, (old?.plays ?: 0) + 1)
        }
    }
    val artists = byArtist.values.sortedByDescending { it.ms }

    val albums = listens.filter { it.album.isNotBlank() }.groupBy { it.album }
        .map { (name, plays) -> AlbumStat(name, plays.last().artists, plays.last().imageUrl, plays.sumOf { it.ms }, plays.size) }
        .sortedByDescending { it.ms }

    // Where the music came from. Only plays logged since the app started
    // keeping it have one, so the section hides itself until there are some.
    val contexts = listens.mapNotNull { l -> contextKey(l.contextUri)?.let { it to l } }
        .groupBy({ it.first }, { it.second })
        .map { (key, plays) -> ContextStat(key, plays.sumOf { it.ms }, plays.size, plays.last()) }
        .sortedByDescending { it.ms }

    // Two-hour buckets: 24 bars are too thin to read on a phone.
    val perHour = listens.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).hour / 2 }
    val thisHour = Instant.now().atZone(zone).hour / 2
    val hours = (0 until 12).map { h ->
        // The hour alone: the column is too narrow for "12h" on one line.
        Bar(if (h % 3 == 0) "${h * 2}" else "", perHour[h].orEmpty().sumOf { it.ms }, h == thisHour)
    }

    val bars = when (period) {
        Period.All -> {
            val now = YearMonth.from(today)
            val first = all.firstOrNull()?.let { YearMonth.from(dayOf(it)) } ?: now
            val from = maxOf(first, now.minusMonths(11))
            val perMonth = listens.groupBy { YearMonth.from(dayOf(it)) }
            generateSequence(from) { it.plusMonths(1) }.takeWhile { !it.isAfter(now) }
                .map { m -> Bar(month(m.monthValue), perMonth[m].orEmpty().sumOf { it.ms }, m == now) }
                .toList()
        }
        else -> {
            val perDay = listens.groupBy(dayOf)
            (0 until period.days!!).map { i ->
                val day = start!!.plusDays(i)
                Bar(if (period == Period.Week) weekday(day.dayOfWeek) else "", perDay[day].orEmpty().sumOf { it.ms }, day == today)
            }
        }
    }
    val days = period.days ?: all.firstOrNull()?.let { today.toEpochDay() - dayOf(it).toEpochDay() + 1 } ?: 1L
    return Stats(listens.sumOf { it.ms }, songs, artists, albums, contexts, bars, hours, highlights(all, listens, dayOf, today), days.coerceAtLeast(1L))
}

/**
 * The run of days up to today with something played, the day with the most
 * minutes, and how many songs of the period were never heard before it.
 * The streak looks at the whole log, not only the period.
 */
private fun highlights(all: List<Listen>, listens: List<Listen>, dayOf: (Listen) -> LocalDate, today: LocalDate): Highlights {
    val heard = all.map(dayOf).toSet()
    var streak = 0
    // Yesterday still counts while today has nothing yet.
    var day = if (today in heard) today else today.minusDays(1)
    while (day in heard) {
        streak++
        day = day.minusDays(1)
    }
    val best = listens.groupBy(dayOf).maxByOrNull { (_, plays) -> plays.sumOf { it.ms } }
    val firstHeard = HashMap<String, Long>()
    for (l in all) firstHeard.merge(l.uri, l.at, ::minOf)
    val fresh = listens.map { it.uri }.distinct().count { uri -> listens.any { it.uri == uri && it.at == firstHeard[uri] } }
    return Highlights(streak, best?.key, best?.value?.sumOf { it.ms } ?: 0, fresh)
}

private fun minutes(ms: Long) = "%,d".format(ms / 60_000)

private fun Listen.asTrack() = Track(
    uri = uri, title = title, artists = artists, artistIds = artistIds, album = album, albumId = null,
    imageUrl = imageUrl, durationMs = 0u, explicit = false, playable = true, addedAtMs = null, addedBy = null,
)

/**
 * "Tuas estatísticas": what was played, here and on the account's other devices, in the last week,
 * month or ever. Minutes count what played here; a play elsewhere counts as the whole song.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatsScreen(actions: AppActions, list: LazyListState, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val listens by actions.services.listens.collectAsState()
    var periodName by rememberSaveable { mutableStateOf(Period.Week.name) }
    val period = Period.valueOf(periodName)
    val zone = remember { ZoneId.systemDefault() }
    val s = remember(listens, period) { stats(listens, period, zone) }
    var menuFor by remember { mutableStateOf<Track?>(null) }
    menuFor?.let { TrackMenu(it, actions) { menuFor = null } }

    // Names and covers for the playlists the music came from. The library
    // answers for most; the rest (someone else's, Spotify's own) are read
    // one by one from the disk cache.
    val playlists = rememberLoaded("stats-pl") { actions.core.myPlaylists(it) }
    var sources by remember { mutableStateOf<Map<String, Pair<String, String?>>>(emptyMap()) }
    val mine = remember(playlists.state.value) {
        playlists.state.value.orEmpty().associate { "pl:${it.id}" to (it.name to it.imageUrl) }
    }
    val wanted = remember(s.contexts, mine, sources) {
        s.contexts.take(TOP).map { it.key }.filter { it.startsWith("pl:") && it !in mine && it !in sources }
    }
    LaunchedEffect(wanted) {
        for (key in wanted) {
            val found = runCatching { actions.core.playlist(key.substringAfter(':'), false) }.getOrNull() ?: continue
            sources = sources + (key to (found.playlist.name to found.playlist.imageUrl))
        }
    }
    val sourceInfo = mine + sources

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
                Text("Tuas estatísticas", Modifier.padding(top = 14.dp), fontSize = 34.sp, lineHeight = 40.sp, style = MaterialTheme.typography.headlineLarge)
                Text("Neste celular e nos teus outros aparelhos", color = c.onSurfaceVariant, fontSize = 14.sp)
                PillSelector(Period.entries, period, { it.label }, { periodName = it.name }, Modifier.fillMaxWidth().padding(top = 18.dp))
            }
        }
        if (s.songs.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    Icons.Rounded.BarChart,
                    "Nada por aqui ainda",
                    "Músicas ouvidas por mais de 30 segundos, aqui ou em outro aparelho, aparecem aqui.",
                    Modifier.padding(top = 24.dp),
                )
            }
            return@LazyColumn
        }
        item(key = "totals") {
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Total(minutes(s.ms), "minutos", Modifier.weight(1f))
                Total("%,d".format(s.songs.size), if (s.songs.size == 1) "música" else "músicas", Modifier.weight(1f))
                Total("%,d".format(s.artists.size), if (s.artists.size == 1) "artista" else "artistas", Modifier.weight(1f))
            }
        }
        item(key = "chart") {
            Column {
                SectionTitle(if (period == Period.All) "Por mês" else "Por dia")
                Text(
                    "${minutes(s.ms / s.days)} min por dia, em média",
                    Modifier.padding(bottom = 14.dp),
                    color = c.onSurfaceVariant, fontSize = 13.sp,
                )
                Bars(s.bars)
            }
        }
        item(key = "highlights") { HighlightRow(s.highlights, period) }
        if (s.hours.any { it.ms > 0 }) {
            item(key = "hours") {
                Column {
                    SectionTitle("A que horas tu ouve")
                    Text(
                        busiestHours(s.hours),
                        Modifier.padding(bottom = 14.dp),
                        color = c.onSurfaceVariant, fontSize = 13.sp,
                    )
                    Bars(s.hours)
                }
            }
        }
        item(key = "songs-title") { SectionTitle("Músicas mais ouvidas") }
        val top = s.songs.take(TOP)
        itemsIndexed(top, key = { _, song -> "song:${song.last.uri}" }) { i, song ->
            val l = song.last
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .combinedClickable(
                        onClick = { actions.play(top.map { it.last.asTrack() }, i) },
                        onLongClick = { menuFor = l.asTrack() },
                    )
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Rank(i + 1)
                Cover(l.imageUrl, Modifier.size(48.dp), 10.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(l.artists, color = c.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
                Text(if (song.plays == 1) "1 vez" else "${song.plays} vezes", color = c.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        item(key = "artists-title") { SectionTitle("Artistas mais ouvidos") }
        val artists = s.artists.take(TOP)
        val most = artists.firstOrNull()?.ms?.coerceAtLeast(1L) ?: 1L
        itemsIndexed(artists, key = { _, a -> "artist:${a.id ?: a.name}" }) { i, a ->
            ArtistRow(i + 1, a, a.ms.toFloat() / most) {
                a.id?.let { actions.open(Route.Artist(it), Peek(a.name)) }
            }
        }
        // Only worth a section once more than one album shows up.
        val albums = s.albums.take(TOP)
        if (albums.size > 1) {
            item(key = "albums-title") { SectionTitle("Álbuns mais ouvidos") }
            itemsIndexed(albums, key = { _, a -> "album:${a.name}" }) { i, a ->
                CountRow(i + 1, a.imageUrl, a.name, a.artists, "${minutes(a.ms)} min")
            }
        }
        val from = s.contexts.take(TOP)
        if (from.isNotEmpty()) {
            item(key = "sources-title") { SectionTitle("De onde tu ouve") }
            itemsIndexed(from, key = { _, ctx -> "ctx:${ctx.key}" }) { i, ctx ->
                val known = sourceInfo[ctx.key]
                val name = known?.first ?: sourceName(ctx)
                // The cover of a playlist, or of the album the song is on.
                val cover = known?.second ?: ctx.fallback.imageUrl.takeIf { !ctx.key.startsWith("pl:") }
                CountRow(i + 1, cover, name, sourceKind(ctx.key), "${minutes(ctx.ms)} min") {
                    when {
                        ctx.key == "liked" -> actions.open(Route.Liked)
                        ctx.key.startsWith("pl:") -> actions.open(Route.Playlist(ctx.key.substringAfter(':')), Peek(name, imageUrl = cover))
                        ctx.key.startsWith("al:") -> actions.open(Route.Album(ctx.key.substringAfter(':')), Peek(name, ctx.fallback.artists, ctx.fallback.imageUrl))
                    }
                }
            }
        }
    }
}

/** "Mais entre 18h e 20h", from the fullest two-hour bucket. */
private fun busiestHours(hours: List<Bar>): String {
    val i = hours.indices.maxByOrNull { hours[it].ms } ?: return ""
    return "Tu ouve mais entre ${i * 2}h e ${i * 2 + 2}h"
}

private fun sourceKind(key: String) = when {
    key == "liked" -> "Músicas curtidas"
    key.startsWith("al:") -> "Álbum"
    key.startsWith("ar:") -> "Artista"
    else -> "Playlist"
}

/** What to call a source while its real name is unknown. */
private fun sourceName(ctx: ContextStat) = when {
    ctx.key == "liked" -> "Músicas curtidas"
    ctx.key.startsWith("al:") -> ctx.fallback.album.ifBlank { "Álbum" }
    else -> "Uma playlist"
}

/** The period's three short facts, in a line that scrolls sideways if it must. */
@Composable
private fun HighlightRow(h: Highlights, period: Period) {
    val c = MaterialTheme.colorScheme
    val accent = accentColor()
    val day = h.bestDay?.format(java.time.format.DateTimeFormatter.ofPattern("d 'de' MMMM", java.util.Locale.forLanguageTag("pt-BR")))
    val facts = buildList {
        if (h.streak > 1) add(Icons.Rounded.LocalFireDepartment to "${h.streak} dias seguidos")
        if (day != null && h.bestMs > 0) add(Icons.Rounded.EmojiEvents to "$day: ${minutes(h.bestMs)} min")
        if (h.fresh > 0) add(Icons.Rounded.AutoAwesome to if (h.fresh == 1) "1 música nova" else "${h.fresh} músicas novas")
    }
    if (facts.isEmpty()) return
    Row(
        Modifier.padding(top = 16.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        facts.forEach { (icon, text) ->
            Row(
                Modifier.clip(CircleShape).background(accent.copy(alpha = .13f)).padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(text, color = c.onSurface, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
    if (period == Period.Week && facts.isNotEmpty()) Spacer(Modifier.height(4.dp))
}


/** A ranked row with a cover, two lines and a figure: albums, and where the music came from. */
@Composable
private fun CountRow(rank: Int, imageUrl: String?, title: String, subtitle: String, value: String, onClick: (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .then(if (onClick == null) Modifier else Modifier.clickable { haptics.tick(); onClick() })
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Rank(rank)
        Cover(imageUrl, Modifier.size(48.dp), 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = c.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text(value, color = c.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun Total(value: String, label: String, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(c.surfaceVariant.copy(alpha = .6f)).padding(horizontal = 14.dp, vertical = 16.dp)) {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp, maxLines = 1)
        Text(label, color = c.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun Rank(n: Int) = Text(
    "$n",
    Modifier.width(28.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize = 15.sp,
    fontWeight = FontWeight.SemiBold,
)

/** Listening per day (or month); today's bar in full accent, the rest softer. */
@Composable
private fun Bars(bars: List<Bar>) {
    val c = MaterialTheme.colorScheme
    val accent = accentColor()
    val most = bars.maxOfOrNull { it.ms }?.coerceAtLeast(1L) ?: 1L
    val labels = bars.any { it.label.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().height(if (labels) 132.dp else 112.dp),
        horizontalArrangement = Arrangement.spacedBy(if (bars.size > 14) 3.dp else 8.dp),
    ) {
        bars.forEachIndexed { i, bar ->
            // Bars glide to their new height when the period changes.
            val share by animateFloatAsState(bar.ms.toFloat() / most, Motion.settle(), label = "bar$i")
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth()
                            .fillMaxHeight(share.coerceIn(0f, 1f).coerceAtLeast(.03f))
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                            .background(if (bar.current) accent else accent.copy(alpha = if (bar.ms > 0) .38f else .12f)),
                    )
                }
                if (labels) {
                    Text(
                        bar.label,
                        Modifier.padding(top = 6.dp),
                        color = if (bar.current) c.onSurface else c.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = if (bar.current) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistRow(rank: Int, artist: ArtistStat, share: Float, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val accent = accentColor()
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(enabled = artist.id != null) { haptics.tick(); onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Rank(rank)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(artist.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${minutes(artist.ms)} min", color = c.onSurfaceVariant, fontSize = 13.sp)
            }
            // How each compares with the most listened, as a thin track.
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(c.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(share.coerceIn(.02f, 1f)).fillMaxHeight().clip(CircleShape).background(accent))
            }
        }
    }
}

/**
 * The way into the stats under the home greeting: this week's minutes once
 * there are some, the plain name before that.
 */
@Composable
fun StatsShortcut(actions: AppActions, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val listens by actions.services.listens.collectAsState()
    val weekMs = remember(listens) {
        val since = System.currentTimeMillis() - 7 * 86_400_000L
        listens.asReversed().asSequence().takeWhile { it.at >= since }.sumOf { it.ms }
    }
    Row(
        modifier.clip(CircleShape).clickable { haptics.tick(); actions.open(Route.Stats) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.BarChart, null, Modifier.size(16.dp), tint = accentColor())
        Spacer(Modifier.width(6.dp))
        Text(
            if (weekMs >= 60_000) "${minutes(weekMs)} min ouvidos esta semana" else "Tuas estatísticas",
            color = c.onSurfaceVariant, fontSize = 14.sp,
        )
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
    }
}
