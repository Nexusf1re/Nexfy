package dev.nexfy.app.ui.screens

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

private data class ArtistStat(val name: String, val id: String?, val ms: Long)

private data class Bar(val label: String, val ms: Long, val current: Boolean)

private data class Stats(
    val ms: Long,
    val songs: List<SongStat>,
    val artists: List<ArtistStat>,
    val bars: List<Bar>,
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
            byArtist[key] = ArtistStat(old?.name ?: name, id ?: old?.id, (old?.ms ?: 0) + l.ms)
        }
    }
    val artists = byArtist.values.sortedByDescending { it.ms }

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
    return Stats(listens.sumOf { it.ms }, songs, artists, bars, days.coerceAtLeast(1L))
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
    val listens by actions.app.playLog.listens.collectAsState()
    var periodName by rememberSaveable { mutableStateOf(Period.Week.name) }
    val period = Period.valueOf(periodName)
    val zone = remember { ZoneId.systemDefault() }
    val s = remember(listens, period) { stats(listens, period, zone) }
    var menuFor by remember { mutableStateOf<Track?>(null) }
    menuFor?.let { TrackMenu(it, actions) { menuFor = null } }

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
    val listens by actions.app.playLog.listens.collectAsState()
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
