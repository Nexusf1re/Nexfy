package dev.nexfy.app

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uniffi.nexfy_core.Playlist

/** A short message over the dock; removals carry a way back. */
data class Notice(val id: Long, val message: String, val undo: (() -> Unit)? = null)

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

    private val _notice = MutableStateFlow<Notice?>(null)

    /** What the last change said, until it times out or is dismissed. */
    val notice: StateFlow<Notice?> = _notice

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

    /** Saves or removes; a removal can be undone from its notice. */
    fun setSaved(uri: String, on: Boolean, done: String) {
        val before = saved[uri]
        saved[uri] = on
        change(
            done,
            undo = { if (before == null) saved.remove(uri) else saved[uri] = before },
            back = if (on) null else ({ setSaved(uri, true, "Restaurado") }),
        ) {
            core.setSaved(listOf(uri), on)
        }
    }

    fun addTo(playlist: Playlist, uris: List<String>) =
        change(if (uris.size == 1) "Adicionada a ${playlist.name}" else "${uris.size} músicas adicionadas a ${playlist.name}") {
            core.addToPlaylist(playlist.id, uris, null)
        }

    /** [position]: where it was, so undoing puts it back there. */
    fun removeFrom(playlistId: String, uri: String, position: Int?) =
        change("Removida da playlist", back = {
            change("Restaurada na playlist") { core.addToPlaylist(playlistId, listOf(uri), position?.toUInt()) }
        }) {
            core.removeFromPlaylist(playlistId, listOf(uri))
        }

    fun edit(playlistId: String, name: String, description: String) =
        change("Playlist atualizada") { core.editPlaylist(playlistId, name, description) }

    /** Creates a private playlist, then [then] with it. */
    fun create(name: String, then: (Playlist) -> Unit = {}) {
        scope.launch {
            try {
                val playlist = core.createPlaylist(name, "")
                _version.value++
                say("Playlist criada: ${playlist.name}")
                then(playlist)
            } catch (e: Exception) {
                say(e.message ?: "Não deu para criar a playlist")
            }
        }
    }

    fun dismiss(id: Long) {
        if (_notice.value?.id == id) _notice.value = null
    }

    /** [undo] reverts the screen if Spotify refuses; [back] is the user's "Desfazer". */
    private fun change(done: String, undo: () -> Unit = {}, back: (() -> Unit)? = null, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                _version.value++
                say(done, back)
            } catch (e: Exception) {
                undo()
                say(e.message ?: "Não deu para mudar")
            }
        }
    }

    private fun say(message: String, back: (() -> Unit)? = null) {
        _notice.value = Notice(System.nanoTime(), message, back)
    }
}
