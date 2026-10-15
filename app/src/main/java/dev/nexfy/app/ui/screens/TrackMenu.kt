package dev.nexfy.app.ui.screens

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
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.ui.design.GlassSheet
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.Peek
import dev.nexfy.app.ui.Route
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.player.JamState
import uniffi.nexfy_core.Track

/** Long-press actions for a song: queue it, or open its album or artist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenu(
    track: Track,
    actions: AppActions,
    /** The user's own playlist this menu was opened from: offers removing the song. */
    playlistId: String? = null,
    /** Where the song is in that playlist, so removing it can be undone in place. */
    position: Int? = null,
    onDismiss: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    if (adding) {
        AddToPlaylistSheet(listOf(track.uri)) { adding = false; onDismiss() }
        return
    }
    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(track.imageUrl, Modifier.size(52.dp), 10.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artists, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                }
            }
            LikeAction(track.uri, onDismiss)
            MenuAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Adicionar à fila") {
                actions.addToQueue(track)
                onDismiss()
            }
            if (JamState.current.value != null) {
                MenuAction(Icons.Rounded.Groups, "Adicionar à Jam") {
                    JamState.queue(actions.app, track.uri, track.title)
                    onDismiss()
                }
            }
            MenuAction(Icons.Rounded.LibraryAdd, "Adicionar à playlist") { adding = true }
            MenuAction(Icons.Rounded.Radio, "Iniciar rádio") {
                actions.startRadio(track.uri)
                onDismiss()
            }
            playlistId?.let { id ->
                MenuAction(Icons.Rounded.RemoveCircleOutline, "Remover desta playlist") {
                    actions.app.edits.removeFrom(id, track.uri, position)
                    onDismiss()
                }
            }
            track.albumId?.let { id ->
                MenuAction(Icons.Rounded.Album, "Ir para o álbum") {
                    onDismiss()
                    actions.open(Route.Album(id), Peek(track.album, track.artists, track.imageUrl))
                }
            }
            track.artistIds.firstOrNull()?.let { id ->
                MenuAction(Icons.Rounded.Person, "Ir para o artista") {
                    onDismiss()
                    actions.open(Route.Artist(id), Peek(track.artists.substringBefore(", ")))
                }
            }
        }
    }
}

@Composable
internal fun MenuAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
