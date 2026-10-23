package dev.nexfy.app.ui.screens

import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import dev.nexfy.app.ui.Route
import dev.nexfy.app.ui.Peek
import dev.nexfy.app.ui.AppActions
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nexfy.app.ui.copyText
import dev.nexfy.app.ui.design.GlassSheet
import dev.nexfy.app.LocalServices
import dev.nexfy.app.ui.design.Cover
import dev.nexfy.app.ui.design.ListRowSkeleton
import dev.nexfy.app.ui.shareText
import dev.nexfy.app.ui.theme.accentColor
import uniffi.nexfy_core.Playlist

@Composable
private fun services() = LocalServices.current

/** Like or unlike a song, for its menus. */
@Composable
fun LikeAction(uri: String, onDone: () -> Unit) {
    val edits = services().edits
    LaunchedEffect(uri) { edits.load(listOf(uri)) }
    val liked = edits.saved[uri] == true
    Row(
        Modifier.fillMaxWidth().clickable {
            edits.setSaved(uri, !liked, if (liked) "Removida das curtidas" else "Adicionada às curtidas")
            onDone()
        }.padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (liked) accentColor() else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(18.dp))
        Text(if (liked) "Remover das curtidas" else "Curtir", style = MaterialTheme.typography.bodyLarge)
    }
}

/** The user's own playlists to add [uris] to, a new one first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(uris: List<String>, onDismiss: () -> Unit) {
    val app = services()
    val c = MaterialTheme.colorScheme
    var mine by remember { mutableStateOf<List<Playlist>?>(null) }
    var naming by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val me = app.core.username()
        mine = try {
            app.core.myPlaylists(false).filter { it.ownerId == me }
        } catch (_: Exception) {
            emptyList()
        }
    }
    if (naming) {
        NameDialog("Nova playlist", "", "Criar", onDismiss = { naming = false }) { name ->
            naming = false
            onDismiss()
            app.edits.create(name) { app.edits.addTo(it, uris) }
        }
    }
    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Adicionar à playlist", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            PlaylistChoice(null, "Nova playlist", null) { naming = true }
            val list = mine
            if (list == null) {
                repeat(4) { ListRowSkeleton(it, Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) }
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.id }) { p ->
                        PlaylistChoice(p.imageUrl, p.name, "${p.trackCount} faixas") {
                            app.edits.addTo(p, uris)
                            onDismiss()
                        }
                    }
                }
                if (list.isEmpty()) {
                    Text("Nenhuma playlist tua ainda.", Modifier.padding(24.dp), color = c.onSurfaceVariant, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun PlaylistChoice(imageUrl: String?, title: String, subtitle: String?, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (subtitle == null) {
            Box(Modifier.size(48.dp).background(c.surfaceVariant, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, null)
            }
        } else {
            Cover(imageUrl, Modifier.size(48.dp), 8.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = c.onSurfaceVariant, fontSize = 13.sp) }
        }
    }
}

/** Asks for a name (and optionally a description). */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    initialDescription: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) = NameDialogFull(title, initial, confirm, initialDescription, onDismiss) { name, _ -> onConfirm(name) }

@Composable
fun NameDialogFull(
    title: String,
    initial: String,
    confirm: String,
    initialDescription: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var description by remember { mutableStateOf(initialDescription.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nome") }, singleLine = true)
                if (initialDescription != null) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Descrição") }, maxLines = 3)
                }
            }
        },
        confirmButton = {
            TextButton({ onConfirm(name.trim(), description.trim()) }, enabled = name.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}

/** A page's own menu (playlist, album): its cover and name over [content]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageMenu(title: String, imageUrl: String?, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(imageUrl, Modifier.size(52.dp), 10.dp)
                Spacer(Modifier.width(14.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            content()
        }
    }
}

/**
 * Shares an open.spotify.com link, e.g. `playlist/<id>`. Where the system
 * has no share sheet the link goes to the clipboard and [say] tells so.
 */
fun shareLink(context: PlatformContext, title: String, path: String, say: (String) -> Unit = {}) {
    val text = "$title\nhttps://open.spotify.com/$path"
    if (shareText(context, text)) return
    copyText(context, text)
    say("Link copiado")
}

/** Asks before something that cannot be undone from the app. */
@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(body) },
    confirmButton = { TextButton(onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
    dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
)

/**
 * A playlist, album or podcast of the library, as its menu needs it.
 * [key] is the pinning key ("pl:id"); [own] marks a playlist the user made,
 * which is deleted rather than removed.
 */
data class Shelved(
    val key: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val route: Route,
    val peek: Peek,
    val uri: String,
    val own: Boolean = false,
)

/**
 * What holding a library item offers: pin it to the top, open it, and take
 * it out of the library (deleting an own playlist, after a confirmation).
 */
@Composable
fun ShelfMenu(item: Shelved, actions: AppActions, onDismiss: () -> Unit) {
    val library = actions.services.library
    val pinned by library.pinned.collectAsState()
    var deleting by remember { mutableStateOf(false) }
    if (deleting) {
        ConfirmDialog("Excluir playlist?", "“${item.title}” sai da tua biblioteca no Spotify.", "Excluir", { deleting = false }) {
            deleting = false
            onDismiss()
            actions.services.edits.setSaved(item.uri, false, "Playlist excluída")
        }
        return
    }
    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(item.imageUrl, Modifier.size(52.dp), 10.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                }
            }
            SheetAction(Icons.Rounded.PushPin, if (item.key in pinned) "Desafixar" else "Fixar no topo") {
                library.togglePin(item.key)
                onDismiss()
            }
            SheetAction(Icons.Rounded.OpenInNew, "Abrir") {
                onDismiss()
                actions.open(item.route, item.peek)
            }
            if (item.own) {
                SheetAction(Icons.Rounded.DeleteOutline, "Excluir playlist") { deleting = true }
            } else {
                SheetAction(Icons.Rounded.RemoveCircleOutline, "Remover da biblioteca") {
                    onDismiss()
                    actions.services.edits.setSaved(item.uri, false, "Removida da biblioteca")
                }
            }
        }
    }
}
