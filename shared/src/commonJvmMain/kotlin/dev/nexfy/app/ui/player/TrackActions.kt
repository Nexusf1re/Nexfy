package dev.nexfy.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import dev.nexfy.app.LocalServices
import dev.nexfy.app.ui.copyText
import dev.nexfy.app.ui.design.GlassSheet
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.shareText
import kotlinx.coroutines.CancellationException
import uniffi.nexfy_core.Track
import uniffi.nexfy_core.TrackInfo

/** The song's page on open.spotify.com, for copying and sharing. */
private fun webLink(uri: String) = "https://open.spotify.com/track/" + uri.substringAfterLast(':')

/**
 * What can be done with the song playing: start its radio, go to its
 * artists or album, copy or share its link, set a sleep timer.
 * [onNavigate] runs before opening a page (the player collapses to show it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheet(track: TrackInfo, controls: PlayerControls, onNavigate: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalPlatformContext.current
    val services = LocalServices.current
    val haptics = rememberHaptics()
    // The player's song carries names only; ids come from its metadata.
    var details by remember(track.uri) { mutableStateOf<Track?>(null) }
    LaunchedEffect(track.uri) {
        details = try {
            controls.tracksInfo(listOf(track.uri)).firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    var adding by remember { mutableStateOf(false) }
    if (adding) {
        dev.nexfy.app.ui.screens.AddToPlaylistSheet(listOf(track.uri)) { adding = false; onDismiss() }
        return
    }
    var sleeping by remember { mutableStateOf(false) }
    if (sleeping) {
        SleepTimerSheet { sleeping = false; onDismiss() }
        return
    }
    val go = { open: () -> Unit ->
        haptics.tick()
        onDismiss()
        onNavigate()
        open()
    }

    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(track.coverUrl, Modifier.size(52.dp), 10.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artists, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                }
            }
            dev.nexfy.app.ui.screens.LikeAction(track.uri, onDismiss)
            Action(Icons.Rounded.LibraryAdd, "Adicionar à playlist") { adding = true }
            if (JamState.current.value != null) {
                Action(Icons.Rounded.Groups, "Adicionar à Jam") {
                    haptics.tick()
                    JamState.queue(services, track.uri, track.title)
                    onDismiss()
                }
            }
            Action(Icons.Rounded.Radio, "Iniciar rádio") {
                go { controls.startRadio(track.uri) }
            }
            val d = details
            // One row per artist (names and ids come in the same order).
            val names = (d?.artists ?: track.artists).split(", ")
            d?.artistIds?.forEachIndexed { i, id ->
                val name = names.getOrNull(i) ?: return@forEachIndexed
                Action(Icons.Rounded.Person, if (d.artistIds.size > 1) "Ir para $name" else "Ir para o artista") {
                    go { controls.openArtist(id, name) }
                }
            }
            d?.albumId?.let { id ->
                Action(Icons.Rounded.Album, "Ir para o álbum") {
                    go { controls.openAlbum(id, d.album, d.artists, d.imageUrl) }
                }
            }
            Action(Icons.Rounded.Link, "Copiar link") {
                copyText(context, webLink(track.uri), track.title)
                services.toast("Link copiado")
                onDismiss()
            }
            Action(Icons.Rounded.Share, "Compartilhar") {
                val text = "${track.title} · ${track.artists}\n${webLink(track.uri)}"
                if (!shareText(context, text)) {
                    copyText(context, text)
                    services.toast("Link copiado")
                }
                onDismiss()
            }
            Action(Icons.Rounded.Bedtime, sleepTimerLabel()) { sleeping = true }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
