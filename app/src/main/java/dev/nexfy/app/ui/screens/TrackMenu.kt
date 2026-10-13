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
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.Route
import dev.nexfy.app.ui.design.Cover
import uniffi.nexfy_core.Track

/** Long-press actions for a song: queue it, or open its album or artist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenu(track: Track, actions: AppActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(track.imageUrl, Modifier.size(52.dp), 10.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artists, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                }
            }
            MenuAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Adicionar à fila") {
                actions.addToQueue(track)
                onDismiss()
            }
            track.albumId?.let { id ->
                MenuAction(Icons.Rounded.Album, "Ir para o álbum") {
                    onDismiss()
                    actions.open(Route.Album(id))
                }
            }
            track.artistIds.firstOrNull()?.let { id ->
                MenuAction(Icons.Rounded.Person, "Ir para o artista") {
                    onDismiss()
                    actions.open(Route.Artist(id))
                }
            }
        }
    }
}

@Composable
private fun MenuAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
