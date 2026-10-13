package dev.nexfy.app

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uniffi.nexfy_core.Album
import uniffi.nexfy_core.Playlist
import uniffi.nexfy_core.RecentPlay

/** [items] with the pinned ones first (in [pinned] order), the rest after. */
fun <T> pinnedFirst(items: List<T>, pinned: List<String>, key: (T) -> String): List<T> {
    val (top, rest) = items.partition { key(it) in pinned }
    return top.sortedBy { pinned.indexOf(key(it)) } + rest
}

/** Library key for a Spotify context URI: `pl:<id>`, `al:<id>`, `ar:<id>`. */
fun contextKey(uri: String?): String? {
    val parts = uri?.split(':') ?: return null
    if (parts.size < 3) return null
    return when (parts[1]) {
        "playlist" -> "pl:${parts[2]}"
        "album" -> "al:${parts[2]}"
        "artist" -> "ar:${parts[2]}"
        "collection" -> "liked"
        else -> null
    }
}

/** How often each library item was played: Nexfy's own count plus Spotify's recent plays. */
fun playScores(local: Map<String, Int>, recent: List<RecentPlay>): Map<String, Int> {
    val scores = local.toMutableMap()
    recent.mapNotNull { contextKey(it.contextUri) }.forEach { scores[it] = (scores[it] ?: 0) + 1 }
    return scores
}

enum class PlaylistSort(val label: String) {
    MostPlayed("Mais ouvidas"),
    Recent("Recentes"),
    Name("Nome"),
    Creator("Criador"),
    Size("Mais faixas"),
    ;

    /** Ties keep Spotify's order (stable sorts). */
    fun apply(list: List<Playlist>, scores: Map<String, Int>): List<Playlist> = when (this) {
        MostPlayed -> list.sortedByDescending { scores["pl:${it.id}"] ?: 0 }
        Recent -> list
        Name -> list.sortedBy { it.name.lowercase() }
        Creator -> list.sortedWith(compareBy({ it.owner.lowercase() }, { it.name.lowercase() }))
        Size -> list.sortedByDescending { it.trackCount }
    }
}

enum class AlbumSort(val label: String) {
    MostPlayed("Mais ouvidos"),
    Recent("Recentes"),
    Name("Nome"),
    Artist("Artista"),
    Year("Lançamento"),
    ;

    fun apply(list: List<Album>, scores: Map<String, Int>): List<Album> = when (this) {
        MostPlayed -> list.sortedByDescending { scores["al:${it.id}"] ?: 0 }
        Recent -> list
        Name -> list.sortedBy { it.name.lowercase() }
        Artist -> list.sortedWith(compareBy({ it.artists.lowercase() }, { it.year.orEmpty() }))
        Year -> list.sortedByDescending { it.year.orEmpty() }
    }
}

/**
 * Library choices kept on this phone: pinned playlists and albums (in the
 * order they were pinned), each shelf's sort, and how often each playlist
 * or album was played in Nexfy.
 */
class LibraryPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    private val _pinned = MutableStateFlow(
        prefs.getString(PINNED, null)?.split('\n')?.filter(String::isNotBlank).orEmpty(),
    )

    /** Keys like `pl:<id>` and `al:<id>`, oldest pin first. */
    val pinned: StateFlow<List<String>> = _pinned

    private val _playlistSort = MutableStateFlow(
        PlaylistSort.entries.firstOrNull { it.name == prefs.getString(PLAYLIST_SORT, null) } ?: PlaylistSort.MostPlayed,
    )
    val playlistSort: StateFlow<PlaylistSort> = _playlistSort

    private val _albumSort = MutableStateFlow(
        AlbumSort.entries.firstOrNull { it.name == prefs.getString(ALBUM_SORT, null) } ?: AlbumSort.MostPlayed,
    )
    val albumSort: StateFlow<AlbumSort> = _albumSort

    private val _plays = MutableStateFlow(
        prefs.getString(PLAYS, null)?.lines()?.mapNotNull { line ->
            val (key, count) = line.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            count.toIntOrNull()?.let { key to it }
        }?.toMap().orEmpty(),
    )

    /** Plays started in Nexfy, by library key. */
    val plays: StateFlow<Map<String, Int>> = _plays

    fun togglePin(key: String) {
        val now = _pinned.value.let { if (key in it) it - key else it + key }
        _pinned.value = now
        prefs.edit { putString(PINNED, now.joinToString("\n")) }
    }

    fun setPlaylistSort(sort: PlaylistSort) {
        _playlistSort.value = sort
        prefs.edit { putString(PLAYLIST_SORT, sort.name) }
    }

    fun setAlbumSort(sort: AlbumSort) {
        _albumSort.value = sort
        prefs.edit { putString(ALBUM_SORT, sort.name) }
    }

    fun recordPlay(key: String) {
        val now = _plays.value + (key to (_plays.value[key] ?: 0) + 1)
        _plays.value = now
        prefs.edit { putString(PLAYS, now.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
    }

    private companion object {
        const val PINNED = "pinned"
        const val PLAYLIST_SORT = "playlist_sort"
        const val ALBUM_SORT = "album_sort"
        const val PLAYS = "plays"
    }
}
