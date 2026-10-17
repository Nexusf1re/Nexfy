package dev.nexfy.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.LoadError
import dev.nexfy.app.ui.design.Bone
import dev.nexfy.app.ui.design.CollectionBackdrop
import dev.nexfy.app.ui.design.artInk
import dev.nexfy.app.ui.theme.isDark
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.design.DetailTopBar
import dev.nexfy.app.ui.design.GlassBox
import dev.nexfy.app.ui.design.GlassIcon
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.PlayingBars
import dev.nexfy.app.ui.design.SectionTitle
import dev.nexfy.app.ui.design.TextBone
import dev.nexfy.app.ui.design.TopBarHeight
import dev.nexfy.app.ui.design.boneWidth
import dev.nexfy.app.ui.design.collapseOf
import dev.nexfy.app.ui.design.dragToDismiss
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.design.skeletonFade
import dev.nexfy.app.ui.design.weightedPress
import dev.nexfy.app.ui.rememberLoaded
import dev.nexfy.app.ui.theme.accentColor
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.nexfy_core.Episode
import uniffi.nexfy_core.RemoteCommand

/**
 * A podcast: the collection header over its cover, then its latest
 * episodes, newest first. Tapping one plays it from where it was left.
 */
@Composable
fun ShowScreen(actions: AppActions, id: String, list: LazyListState, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val dark = c.isDark
    val ink = artInk()
    val haptics = rememberHaptics()
    val detail = rememberLoaded(id) { refresh -> actions.core.show(id, refresh) }
    val d = detail.state.value
    val peek = actions.nav.peek("sh:$id")
    val edits = actions.app.edits
    val uri = "spotify:show:$id"
    LaunchedEffect(uri) { edits.load(listOf(uri)) }
    val context = LocalContext.current
    val collapse = collapseOf(list)
    val dismiss = remember { Animatable(0f) }
    val back = {
        haptics.tick()
        actions.back()
        Unit
    }
    var menu by remember { mutableStateOf(false) }

    val name = d?.show?.name ?: peek?.title.orEmpty()
    val publisher = d?.show?.publisher?.ifEmpty { null } ?: peek?.subtitle
    val imageUrl = d?.show?.imageUrl ?: peek?.imageUrl
    val episodes = d?.episodes
    val error = detail.state.error.takeIf { d == null && !detail.state.loading }
    // The play button picks up the newest episode not finished yet.
    val next = episodes?.firstOrNull { !it.fullyPlayed } ?: episodes?.firstOrNull()
    val playbackState = actions.app.bridge.state.collectAsState()
    val playingUri by remember { derivedStateOf { playbackState.value.track?.uri } }
    val play = { episode: Episode ->
        haptics.press()
        playEpisode(actions, id, episode, context)
    }

    if (menu) {
        PageMenu(name, imageUrl, { menu = false }) {
            val saved = edits.saved[uri] == true
            MenuAction(if (saved) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, if (saved) "Remover dos salvos" else "Salvar podcast") {
                menu = false
                edits.setSaved(uri, !saved, if (saved) "Podcast removido" else "Podcast salvo")
            }
            MenuAction(Icons.Rounded.Share, "Compartilhar") { menu = false; shareLink(context, name, "show/$id") }
        }
    }

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
                            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
                            .graphicsLayer { alpha = (1f - collapse.value * 1.5f).coerceIn(0f, 1f) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val coverModifier = Modifier.size(208.dp).graphicsLayer {
                            val sc = 1f - .15f * collapse.value.coerceIn(0f, 1f)
                            scaleX = sc
                            scaleY = sc
                        }
                        if (d == null && imageUrl == null) {
                            Bone(coverModifier, RoundedCornerShape(10.dp), light = dark)
                        } else {
                            Cover(imageUrl, coverModifier, radius = 10.dp, motion = true, sharedKey = actions.nav.origin("sh:$id"))
                        }
                        Spacer(Modifier.height(16.dp))
                        if (name.isBlank()) {
                            Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                                Bone(Modifier.size(180.dp, 22.dp), light = dark)
                            }
                        } else {
                            Text(
                                name, color = ink, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (publisher != null) {
                            Text(
                                publisher, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                color = ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Spacer(Modifier.height(4.dp))
                        }
                        if (d == null) {
                            Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                                if (error == null) Bone(Modifier.size(132.dp, 10.dp), light = dark)
                            }
                        } else {
                            val count = d.show.episodeCount.toInt().takeIf { it > 0 } ?: d.episodes.size
                            Text(
                                "Podcast · ${episodesLabel(count)}".uppercase(), color = ink.copy(.7f), fontSize = 12.sp,
                                letterSpacing = 1.sp, textAlign = TextAlign.Center, maxLines = 1,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            GlassBox(Modifier.height(48.dp).width(156.dp), CircleShape, dark = dark) {
                                Row(
                                    Modifier.matchParentSize().clickable(enabled = next != null) { next?.let(play) },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, null, tint = ink)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (next != null && next.started) "Continuar" else "Tocar",
                                        color = ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                                    )
                                }
                            }
                            GlassIcon(Icons.Rounded.MoreHoriz, "Mais opções", { haptics.tick(); menu = true }, light = !dark)
                        }
                    }
                }
                item(key = "title") { SectionTitle("Episódios", Modifier.padding(horizontal = 24.dp)) }
                when {
                    error != null -> item(key = "error") { LoadError(error, detail.refresh) }
                    episodes == null -> items(8, key = { "sk$it" }) { i ->
                        EpisodeRowSkeleton(i, Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = skeletonFade()))
                    }
                    episodes.isEmpty() -> item(key = "none") {
                        Text(
                            "Nenhum episódio por aqui ainda",
                            Modifier.fillMaxWidth().padding(32.dp),
                            color = c.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    else -> itemsIndexed(episodes, key = { _, e -> e.uri }) { i, episode ->
                        Column(Modifier.animateItem(fadeInSpec = skeletonFade(), placementSpec = null, fadeOutSpec = null)) {
                            EpisodeRow(episode, playing = playingUri == episode.uri) { play(episode) }
                            if (i < episodes.lastIndex) {
                                HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = c.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
        DetailTopBar(name, collapse, back, onPlay = next?.let { episode -> { play(episode) } })
    }
}

/** Begun but not finished: it resumes where it was left. */
private val Episode.started get() = !fullyPlayed && resumePositionMs > 0u

/**
 * Play [episode] in its show, from where it was left. While another device
 * plays, it plays there (Spotify's own apps also stream what Nexfy cannot).
 */
private fun playEpisode(actions: AppActions, showId: String, episode: Episode, context: android.content.Context) {
    val app = actions.app
    val showUri = "spotify:show:$showId"
    if (app.commands.remote != null) {
        app.commands.remote(RemoteCommand.PlayContext(showUri, episode.uri))
        return
    }
    if (!episode.playable) {
        Toast.makeText(context, "Este episódio não toca aqui: fica fora do Spotify ou não está liberado na tua região", Toast.LENGTH_LONG).show()
        return
    }
    val from = if (episode.started) episode.resumePositionMs else 0u
    app.commands.run {
        try {
            app.core.playEpisode(showId, episode.uri, from)
        } catch (e: Exception) {
            // The core explains why (hosted outside Spotify, no audio); say it plainly.
            val reason = e.message?.substringAfter(": ")?.replaceFirstChar { it.uppercase() } ?: "Não deu para tocar este episódio"
            withContext(Dispatchers.Main) { Toast.makeText(app, reason, Toast.LENGTH_LONG).show() }
        }
    }
}

/**
 * An episode: title, the start of its description, then date and length
 * (what is left, with a thin progress line, once begun; a check once heard).
 */
@Composable
private fun EpisodeRow(episode: Episode, playing: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val accent = accentColor()
    val press = remember { MutableInteractionSource() }
    Box(Modifier.padding(horizontal = 16.dp)) {
        Column(
            Modifier.fillMaxWidth().weightedPress(press, 16.dp).clip(RoundedCornerShape(16.dp))
                .clickable(press, null, onClick = onClick)
                .alpha(if (episode.playable) 1f else .4f)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    episode.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    color = if (playing) accent else c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (playing) {
                    Spacer(Modifier.width(12.dp))
                    PlayingBars(accent, Modifier.size(16.dp))
                }
            }
            if (episode.description.isNotEmpty()) {
                Text(
                    episode.description, Modifier.padding(top = 2.dp), color = c.onSurfaceVariant,
                    fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (episode.fullyPlayed) {
                    Icon(Icons.Rounded.CheckCircle, "Ouvido", tint = accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                }
                val length = if (episode.started) {
                    "restam ${minutesLabel(episode.durationMs.toLong() - episode.resumePositionMs.toLong())}"
                } else {
                    minutesLabel(episode.durationMs.toLong())
                }
                Text(
                    listOf(releaseLabel(episode.releaseDate), length).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = c.onSurfaceVariant, fontSize = 12.sp, maxLines = 1,
                )
                if (episode.started && episode.durationMs > 0u) {
                    val heard = (episode.resumePositionMs.toFloat() / episode.durationMs.toFloat()).coerceIn(.04f, 1f)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.width(48.dp).height(3.dp).clip(CircleShape).background(c.onSurface.copy(alpha = .12f))) {
                        Box(Modifier.fillMaxWidth(heard).fillMaxHeight().background(accent))
                    }
                }
            }
        }
    }
}

/** Same footprint as [EpisodeRow]. */
@Composable
private fun EpisodeRowSkeleton(seed: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        TextBone(boneWidth(seed), 24.dp, 14.dp)
        TextBone(boneWidth(seed, 1), 18.dp, 10.dp)
        TextBone(boneWidth(seed, 2) * .9f, 18.dp, 10.dp)
        TextBone(.3f, 26.dp, 9.dp)
    }
}

private fun episodesLabel(n: Int) = if (n == 1) "1 episódio" else "$n episódios"

/** "54 min", "1 h 12 min". */
private fun minutesLabel(ms: Long): String {
    if (ms <= 0) return ""
    val minutes = ((ms + 30_000) / 60_000).coerceAtLeast(1)
    return when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

private val brazil = Locale.forLanguageTag("pt-BR")

/** "14 de mar.", with the year when it is not this one; Spotify may give only a year or a month. */
private fun releaseLabel(date: String): String = try {
    when (date.length) {
        10 -> LocalDate.parse(date).let { day ->
            val pattern = if (day.year == LocalDate.now().year) "d 'de' MMM" else "d 'de' MMM 'de' yyyy"
            day.format(DateTimeFormatter.ofPattern(pattern, brazil))
        }
        7 -> YearMonth.parse(date).format(DateTimeFormatter.ofPattern("MMM 'de' yyyy", brazil))
        else -> date
    }
} catch (_: Exception) {
    date
}
