package dev.nexfy.app

import android.widget.Toast
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uniffi.nexfy_core.Playlist

/**
 * Changes to the user's Spotify library: likes, saved albums and
 * playlists, and playlist contents. Likes show at once and are undone if
 * Spotify refuses; [version] moves after each change so screens reload.
 */
class LibraryEdits(private val app: NexfyApp, private val scope: CoroutineScope) {
    private val core get() = app.core

    private val _version = MutableStateFlow(0)

    /** Moves after every change that went through. */
    val version: StateFlow<Int> = _version

    /** Whether a URI (song, album, playlist) is saved, once known. */
    val saved = mutableStateMapOf<String, Boolean>()

    /** Looks up the URIs not known yet. */
    suspend fun load(uris: List<String>) {
        val unknown = uris.filter { it !in saved }.distinct()
        if (unknown.isEmpty()) return
        try {
            core.saved(unknown).forEachIndexed { i, on -> saved[unknown[i]] = on }
        } catch (_: Exception) {
            // Unknown stays unknown: the toggle then reads as not saved.
        }
    }

    fun setSaved(uri: String, on: Boolean, done: String) {
        val before = saved[uri]
        saved[uri] = on
        change(done, undo = { if (before == null) saved.remove(uri) else saved[uri] = before }) {
            core.setSaved(listOf(uri), on)
        }
    }

    fun addTo(playlist: Playlist, uris: List<String>) =
        change(if (uris.size == 1) "Adicionada a ${playlist.name}" else "${uris.size} músicas adicionadas a ${playlist.name}") {
            core.addToPlaylist(playlist.id, uris)
        }

    fun removeFrom(playlistId: String, uri: String) =
        change("Removida da playlist") { core.removeFromPlaylist(playlistId, listOf(uri)) }

    fun edit(playlistId: String, name: String, description: String) =
        change("Playlist atualizada") { core.editPlaylist(playlistId, name, description) }

    /** Creates a private playlist, then [then] with it. */
    fun create(name: String, then: (Playlist) -> Unit = {}) {
        scope.launch {
            try {
                val playlist = core.createPlaylist(name, "")
                _version.value++
                toast("Playlist criada: ${playlist.name}")
                then(playlist)
            } catch (e: Exception) {
                toast(e.message ?: "Não deu para criar a playlist")
            }
        }
    }

    private fun change(done: String, undo: () -> Unit = {}, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                _version.value++
                toast(done)
            } catch (e: Exception) {
                undo()
                toast(e.message ?: "Não deu para mudar")
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
}
