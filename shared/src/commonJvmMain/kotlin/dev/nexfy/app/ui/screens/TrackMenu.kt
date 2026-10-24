package dev.nexfy.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.ui.design.GlassMenu
import dev.nexfy.app.ui.design.GlassSheet
import dev.nexfy.app.ui.design.LocalMenuDense
import dev.nexfy.app.ui.design.MenuSeparator
import dev.nexfy.app.ui.AppActions
import dev.nexfy.app.ui.Peek
import dev.nexfy.app.ui.Route
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.player.JamState
import dev.nexfy.app.ui.theme.accentColor
import uniffi.nexfy_core.Track

/**
 * Actions for a song: queue it, or open its album or artist.
 *
 * Asked for at a point — a right-click, a `⋯` under a pointer — it opens
 * there, as a menu on a computer does. Asked for without one, as a
 * long-press is, it rises as a sheet to meet the thumb. One list of
 * actions either way; only the room they are given differs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenu(
    track: Track,
    actions: AppActions,
    /** The user's own playlist this menu was opened from: offers removing the song. */
    playlistId: String? = null,
    /** Where the song is in that playlist, so removing it can be undone in place. */
    position: Int? = null,
    /**
     * Where the menu was asked for, in window pixels. Given one, the menu
     * stands there instead of rising from the bottom of the screen.
     */
    anchor: Offset? = null,
    onDismiss: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    if (adding) {
        AddToPlaylistSheet(listOf(track.uri)) { adding = false; onDismiss() }
        return
    }
    if (anchor != null) {
        // No cover and title at the top here: the menu stands on the row it
        // was opened from, which says which song better than a header would.
        GlassMenu(anchor, onDismiss) {
            TrackActions(track, actions, playlistId, position, { adding = true }, onDismiss)
        }
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
            TrackActions(track, actions, playlistId, position, { adding = true }, onDismiss)
        }
    }
}

/**
 * The actions themselves, in three groups: what to do with the song, where
 * to go from it, and what to undo. A separator is drawn only when the group
 * under it has something in it, so a song with no album and no playlist to
 * be removed from never shows a stray or doubled line.
 */
@Composable
private fun TrackActions(
    track: Track,
    actions: AppActions,
    playlistId: String?,
    position: Int?,
    onAddToPlaylist: () -> Unit,
    onDismiss: () -> Unit,
) {
    LikeItem(track.uri, actions, onDismiss)
    MenuAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Adicionar à fila") {
        actions.addToQueue(track)
        onDismiss()
    }
    if (JamState.current.value != null) {
        MenuAction(Icons.Rounded.Groups, "Adicionar à Jam") {
            JamState.queue(actions.services, track.uri, track.title)
            onDismiss()
        }
    }
    MenuAction(Icons.Rounded.LibraryAdd, "Adicionar à playlist") { onAddToPlaylist() }
    MenuAction(Icons.Rounded.Radio, "Iniciar rádio") {
        actions.startRadio(track.uri)
        onDismiss()
    }
    val albumId = track.albumId
    val artistId = track.artistIds.firstOrNull()
    if (albumId != null || artistId != null) MenuSeparator()
    albumId?.let { id ->
        MenuAction(Icons.Rounded.Album, "Ir para o álbum") {
            onDismiss()
            actions.open(Route.Album(id), Peek(track.album, track.artists, track.imageUrl))
        }
    }
    artistId?.let { id ->
        MenuAction(Icons.Rounded.Person, "Ir para o artista") {
            onDismiss()
            actions.open(Route.Artist(id), Peek(track.artists.substringBefore(", ")))
        }
    }
    playlistId?.let { id ->
        // Last and behind a line of its own: the one row here that takes
        // something away should not sit a pixel from "go to the album".
        MenuSeparator()
        MenuAction(Icons.Rounded.RemoveCircleOutline, "Remover desta playlist", danger = true) {
            actions.services.edits.removeFrom(id, track.uri, position)
            onDismiss()
        }
    }
}

/**
 * Like or unlike, as a row of this menu. [LikeAction] draws the same thing
 * at a sheet's size only, and a row of a menu at the pointer has to follow
 * the menu it is in.
 */
@Composable
private fun LikeItem(uri: String, actions: AppActions, onDone: () -> Unit) {
    val edits = actions.services.edits
    LaunchedEffect(uri) { edits.load(listOf(uri)) }
    val liked = edits.saved[uri] == true
    MenuAction(
        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        if (liked) "Remover das curtidas" else "Curtir",
        tint = if (liked) accentColor() else null,
    ) {
        edits.setSaved(uri, !liked, if (liked) "Removida das curtidas" else "Adicionada às curtidas")
        onDone()
    }
}

/**
 * One action of a menu, at the size the menu it is in wants: a sheet's row
 * is reached by a thumb, a pointer menu's is read at a desk and matches the
 * app's other menus.
 */
@Composable
internal fun MenuAction(
    icon: ImageVector,
    label: String,
    /** Takes something away: the row is drawn in the error colour. */
    danger: Boolean = false,
    /** A glyph that carries its own colour (a lit heart); otherwise the row's. */
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val ink = if (danger) c.error else c.onSurface
    if (!LocalMenuDense.current) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = tint ?: ink)
            Spacer(Modifier.width(18.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, color = ink)
        }
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(10.dp))
            // The keyboard's walk has to be as plain as the pointer's: the row
            // the arrows reached is lit exactly as a hovered one is.
            .background(if (hovered || focused) c.onSurface.copy(alpha = .08f) else Color.Transparent)
            .hoverable(interaction)
            // No ripple: a menu row answers at once and is gone before one
            // would finish spreading.
            .clickable(interaction, null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint ?: ink)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
