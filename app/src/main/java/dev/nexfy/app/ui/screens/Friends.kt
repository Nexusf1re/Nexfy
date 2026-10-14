package dev.nexfy.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.design.PillButton
import dev.nexfy.app.ui.Peek
import dev.nexfy.app.ui.Route
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.design.EmptyState
import dev.nexfy.app.ui.design.ListRowSkeleton
import dev.nexfy.app.ui.design.LocalBottomSpace
import dev.nexfy.app.ui.design.RoundIconButton
import dev.nexfy.app.ui.design.UserAvatar
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.rememberLoaded
import dev.nexfy.app.ui.theme.accentColor
import kotlinx.coroutines.delay
import uniffi.nexfy_core.FriendActivity
import uniffi.nexfy_core.Track
import uniffi.nexfy_core.UserFace

/** "agora" while the song may still be playing, then how long ago it started. */
private fun ago(f: FriendActivity, now: Long = System.currentTimeMillis()): String {
    if (f.listeningNow) return "agora"
    val minutes = ((now - f.atMs) / 60_000).coerceAtLeast(1)
    return when {
        minutes < 60 -> "há $minutes min"
        minutes < 24 * 60 -> "há ${minutes / 60} h"
        else -> "há ${minutes / (24 * 60)} d"
    }
}

/** The page a friend's context opens: a playlist, album or artist. */
private fun contextRoute(uri: String?): Route? {
    val parts = uri?.split(':') ?: return null
    if (parts.size != 3 || parts[0] != "spotify") return null
    return when (parts[1]) {
        "playlist" -> Route.Playlist(parts[2])
        "album" -> Route.Album(parts[2])
        "artist" -> Route.Artist(parts[2])
        else -> null
    }
}

private fun FriendActivity.face() = UserFace(userId, name, imageUrl)

private fun FriendActivity.asTrack() = Track(
    uri = trackUri, title = title, artists = artists, artistIds = emptyList(), album = "", albumId = null,
    imageUrl = coverUrl, durationMs = 0u, explicit = false, playable = true, addedAtMs = null, addedBy = null,
)

/**
 * The way into friends' activity on the home screen: their faces and who
 * is listening. Shown only once the list loaded with someone in it; a
 * client Spotify does not give the buddy list to never sees it.
 */
@Composable
fun FriendsShortcut(friends: List<FriendActivity>, actions: AppActions, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val now = friends.filter { it.listeningNow }
    val lead = now.firstOrNull() ?: friends.first()
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .clickable { haptics.tick(); actions.open(Route.Friends) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Overlapping faces, the ones listening now first.
        val faces = (now + friends).distinctBy { it.userId }.take(3)
        Box(Modifier.width((28 + 18 * (faces.size - 1)).dp)) {
            faces.forEachIndexed { i, f ->
                UserAvatar(
                    f.face(),
                    Modifier.offset(x = (18 * i).dp).size(28.dp).border(2.dp, c.background, CircleShape),
                    description = f.name,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Amigos ouvindo", style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                if (now.isNotEmpty()) "${lead.name} · ${lead.title}" else "${lead.name} ouviu ${ago(lead)}",
                color = c.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
    }
}

/**
 * "Amigos ouvindo": what each friend plays or played last, as Spotify's
 * desktop app lists it. Tap the song to play it, its playlist or album to
 * open it. Refreshed every minute while open.
 */
@Composable
fun FriendsScreen(actions: AppActions, list: LazyListState, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val friends = rememberLoaded("friends") { actions.core.friendActivity(it) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            friends.refresh()
        }
    }
    val state = friends.state

    LazyColumn(
        modifier,
        state = list,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = LocalBottomSpace.current),
    ) {
        item(key = "header") {
            Column(Modifier.padding(bottom = 12.dp)) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", { actions.back() }, container = c.background)
                }
                Text("Amigos ouvindo", Modifier.padding(top = 14.dp), fontSize = 34.sp, lineHeight = 40.sp, style = MaterialTheme.typography.headlineLarge)
                Text("O que quem tu segues toca no Spotify", color = c.onSurfaceVariant, fontSize = 14.sp)
            }
        }
        val value = state.value
        when {
            value == null && state.error != null -> item(key = "error") {
                Column {
                    EmptyState(
                        Icons.Rounded.Group,
                        "Não disponível agora",
                        "O Spotify não mandou a atividade dos amigos para este app.",
                        Modifier.padding(top = 24.dp),
                    )
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PillButton("Tentar de novo", friends.refresh, filled = false)
                    }
                }
            }
            value == null -> items(4, key = { "bone$it" }) { ListRowSkeleton(it) }
            value.isEmpty() -> item(key = "empty") {
                EmptyState(
                    Icons.Rounded.Group,
                    "Ninguém por aqui",
                    "Quando quem tu segues ouvir algo com a atividade aberta, aparece aqui.",
                    Modifier.padding(top = 24.dp),
                )
            }
            else -> items(value, key = { it.userId }) { f -> FriendRow(f, actions) }
        }
    }
}

@Composable
private fun FriendRow(f: FriendActivity, actions: AppActions) {
    val c = MaterialTheme.colorScheme
    val accent = accentColor()
    val haptics = rememberHaptics()
    val play = { haptics.tick(); actions.play(listOf(f.asTrack())) }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box {
            UserAvatar(f.face(), Modifier.size(44.dp), description = f.name)
            // A live dot on whoever is listening right now.
            if (f.listeningNow) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape)
                        .background(c.background).padding(2.dp).clip(CircleShape).background(accent),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    ago(f),
                    color = if (f.listeningNow) accent else c.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = if (f.listeningNow) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            Row(
                Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = play),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(f.coverUrl, Modifier.size(40.dp), 8.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(f.artists, color = c.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val route = contextRoute(f.contextUri)
            val contextName = f.contextName
            if (contextName != null) {
                Row(
                    Modifier.padding(top = 4.dp).clip(CircleShape)
                        .clickable(enabled = route != null) { route?.let { haptics.tick(); actions.open(it, Peek(contextName)) } }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(14.dp), tint = c.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(contextName, color = c.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
