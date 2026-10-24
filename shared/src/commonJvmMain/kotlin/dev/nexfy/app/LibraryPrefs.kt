package dev.nexfy.app

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

/** Light or dark: following the system, or fixed. */
enum class ThemeMode(val label: String) {
    System("Sistema"),
    Light("Claro"),
    Dark("Escuro"),
}

/** How big the lyrics are: [scale] multiplies their normal sizes. */
enum class LyricsSize(val label: String, val scale: Float) {
    Small("Pequeno", .85f),
    Medium("Médio", 1f),
    Large("Grande", 1.2f),
}

/**
 * Library choices kept on this phone: pinned playlists and albums (in the
 * order they were pinned), each shelf's sort, and how often each playlist
 * or album was played in Nexfy, and what is kept on the phone. Also the
 * appearance and behaviour settings, which screens follow live.
 */
class LibraryPrefs(private val prefs: Prefs) {

    private val _pinned = MutableStateFlow(
        prefs.string(PINNED)?.split('\n')?.filter(String::isNotBlank).orEmpty(),
    )

    /** Keys like `pl:<id>` and `al:<id>`, oldest pin first. */
    val pinned: StateFlow<List<String>> = _pinned

    private val _playlistSort = MutableStateFlow(
        PlaylistSort.entries.firstOrNull { it.name == prefs.string(PLAYLIST_SORT) } ?: PlaylistSort.MostPlayed,
    )
    val playlistSort: StateFlow<PlaylistSort> = _playlistSort

    private val _albumSort = MutableStateFlow(
        AlbumSort.entries.firstOrNull { it.name == prefs.string(ALBUM_SORT) } ?: AlbumSort.MostPlayed,
    )
    val albumSort: StateFlow<AlbumSort> = _albumSort

    private val _plays = MutableStateFlow(
        prefs.string(PLAYS)?.lines()?.mapNotNull { line ->
            val (key, count) = line.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            count.toIntOrNull()?.let { key to it }
        }?.toMap().orEmpty(),
    )

    /** Plays started in Nexfy, by library key. */
    val plays: StateFlow<Map<String, Int>> = _plays

    private val _kept = MutableStateFlow(prefs.strings(KEPT).toSet())

    /** Playlists and albums kept on the phone (`pl:<id>`, `al:<id>`): downloaded on Wi-Fi. */
    val kept: StateFlow<Set<String>> = _kept

    private val _prefetchQueue = MutableStateFlow(prefs.boolean(PREFETCH_QUEUE, true))

    /** Download the next songs of the queue ahead, on Wi-Fi. */
    val prefetchQueue: StateFlow<Boolean> = _prefetchQueue

    private val _shuffle = MutableStateFlow(prefs.boolean(SHUFFLE, false))

    /** Shuffle mode, as the shuffle buttons show it: "Tocar" plays shuffled while it is on. */
    val shuffle: StateFlow<Boolean> = _shuffle

    fun setShuffle(on: Boolean) {
        _shuffle.value = on
        prefs.putBoolean(SHUFFLE, on)
    }

    /**
     * The player's own volume, 0 to 100. A phone leaves it at 100 and uses
     * its volume keys; a window carries a slider.
     */
    var volumePercent: Int
        get() = prefs.long(VOLUME, 100).toInt().coerceIn(0, 100)
        set(value) = prefs.putLong(VOLUME, value.coerceIn(0, 100).toLong())

    private val _wideSidebar = MutableStateFlow(prefs.boolean(WIDE_SIDEBAR, false))

    /**
     * On a computer, the library column keeps its names even where the
     * window is too narrow for three full columns: it then takes the room
     * the window can spare rather than folding to covers alone.
     */
    val wideSidebar: StateFlow<Boolean> = _wideSidebar

    fun setWideSidebar(on: Boolean) {
        _wideSidebar.value = on
        prefs.putBoolean(WIDE_SIDEBAR, on)
    }

    private val _coverAccent = MutableStateFlow(prefs.boolean(COVER_ACCENT, true))

    /** The accent takes the colour of the playing song's cover ("Cor da música"). */
    val coverAccent: StateFlow<Boolean> = _coverAccent

    fun setCoverAccent(on: Boolean) {
        _coverAccent.value = on
        prefs.putBoolean(COVER_ACCENT, on)
    }

    private val _themeMode = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == prefs.string(THEME_MODE) } ?: ThemeMode.System,
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.putString(THEME_MODE, mode.name)
    }

    private val _animatedBackdrop = MutableStateFlow(prefs.boolean(ANIMATED_BACKDROP, true))

    /** The cover colours drift behind the player and pages; off, they hold still. */
    val animatedBackdrop: StateFlow<Boolean> = _animatedBackdrop

    fun setAnimatedBackdrop(on: Boolean) {
        _animatedBackdrop.value = on
        prefs.putBoolean(ANIMATED_BACKDROP, on)
    }

    private val _reduceMotion = MutableStateFlow(prefs.boolean(REDUCE_MOTION, false))

    /** Shorter, simpler transitions ("Reduzir animações"). */
    val reduceMotion: StateFlow<Boolean> = _reduceMotion

    fun setReduceMotion(on: Boolean) {
        _reduceMotion.value = on
        prefs.putBoolean(REDUCE_MOTION, on)
    }

    private val _lyricsSize = MutableStateFlow(
        LyricsSize.entries.firstOrNull { it.name == prefs.string(LYRICS_SIZE) } ?: LyricsSize.Medium,
    )
    val lyricsSize: StateFlow<LyricsSize> = _lyricsSize

    fun setLyricsSize(size: LyricsSize) {
        _lyricsSize.value = size
        prefs.putString(LYRICS_SIZE, size.name)
    }

    private val _showAddedBy = MutableStateFlow(prefs.boolean(SHOW_ADDED_BY, true))

    /** Faces of who added each song, in shared playlists and the queue. */
    val showAddedBy: StateFlow<Boolean> = _showAddedBy

    fun setShowAddedBy(on: Boolean) {
        _showAddedBy.value = on
        prefs.putBoolean(SHOW_ADDED_BY, on)
    }

    private val _haptics = MutableStateFlow(prefs.boolean(HAPTICS, true))

    /** Vibration on touches and gestures. */
    val haptics: StateFlow<Boolean> = _haptics

    fun setHaptics(on: Boolean) {
        _haptics.value = on
        prefs.putBoolean(HAPTICS, on)
    }

    private val _translateEmail = MutableStateFlow(prefs.string(TRANSLATE_EMAIL).orEmpty())

    /** Sent with each lyric translation, which raises the free service's daily allowance. */
    val translateEmail: StateFlow<String> = _translateEmail

    fun setTranslateEmail(email: String) {
        _translateEmail.value = email.trim()
        prefs.putString(TRANSLATE_EMAIL, email.trim())
    }

    private val _swipeBack = MutableStateFlow(prefs.boolean(SWIPE_BACK, true))

    /** A sideways drag anywhere on a page goes back, as in Telegram. */
    val swipeBack: StateFlow<Boolean> = _swipeBack

    fun setSwipeBack(on: Boolean) {
        _swipeBack.value = on
        prefs.putBoolean(SWIPE_BACK, on)
    }

    fun toggleKept(key: String) {
        val now = _kept.value.let { if (key in it) it - key else it + key }
        _kept.value = now
        prefs.putStrings(KEPT, now)
    }

    /** The track order last chosen for a playlist, album or liked songs (`pl:<id>`, `al:<id>`, `liked`). */
    fun trackSort(key: String): String? = prefs.string("sort:$key")

    fun setTrackSort(key: String, sort: String) = prefs.putString("sort:$key", sort)

    /** The library shelf last shown (Playlists or Albums). */
    var shelf: String?
        get() = prefs.string(SHELF)
        set(value) = prefs.putString(SHELF, value)

    /** Whether the library shows a grid of covers instead of a list. */
    var libraryGrid: Boolean
        get() = prefs.boolean(LIBRARY_GRID, false)
        set(value) = prefs.putBoolean(LIBRARY_GRID, value)

    /** Whether the player showed lyrics when last used. */
    var lyricsOpen: Boolean
        get() = prefs.boolean(LYRICS_OPEN, false)
        set(value) = prefs.putBoolean(LYRICS_OPEN, value)

    private val _recentSearches = MutableStateFlow(prefs.string(RECENT_SEARCHES)?.split("\n")?.filter(String::isNotBlank).orEmpty())

    /** Searches that found something, newest first. */
    val recentSearches: StateFlow<List<String>> = _recentSearches

    fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val now = (listOf(q) + _recentSearches.value.filterNot { it.equals(q, ignoreCase = true) }).take(8)
        _recentSearches.value = now
        prefs.putString(RECENT_SEARCHES, now.joinToString("\n"))
    }

    fun removeRecentSearch(query: String) {
        val now = _recentSearches.value - query
        _recentSearches.value = now
        prefs.putString(RECENT_SEARCHES, now.joinToString("\n"))
    }

    fun setPrefetchQueue(on: Boolean) {
        _prefetchQueue.value = on
        prefs.putBoolean(PREFETCH_QUEUE, on)
    }

    private val _mobileDownloads = MutableStateFlow(prefs.boolean(MOBILE_DOWNLOADS, false))

    /** Songs download on mobile data too, not only on Wi-Fi. */
    val mobileDownloads: StateFlow<Boolean> = _mobileDownloads

    fun setMobileDownloads(on: Boolean) {
        _mobileDownloads.value = on
        prefs.putBoolean(MOBILE_DOWNLOADS, on)
    }

    /** When the library's lists were last read ahead on Wi-Fi (epoch ms). */
    var warmedAt: Long
        get() = prefs.long(WARMED_AT, 0L)
        set(value) = prefs.putLong(WARMED_AT, value)

    fun togglePin(key: String) {
        val now = _pinned.value.let { if (key in it) it - key else it + key }
        _pinned.value = now
        prefs.putString(PINNED, now.joinToString("\n"))
    }

    fun setPlaylistSort(sort: PlaylistSort) {
        _playlistSort.value = sort
        prefs.putString(PLAYLIST_SORT, sort.name)
    }

    fun setAlbumSort(sort: AlbumSort) {
        _albumSort.value = sort
        prefs.putString(ALBUM_SORT, sort.name)
    }

    fun recordPlay(key: String) {
        val now = _plays.value + (key to (_plays.value[key] ?: 0) + 1)
        _plays.value = now
        prefs.putString(PLAYS, now.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    private companion object {
        const val PINNED = "pinned"
        const val PLAYLIST_SORT = "playlist_sort"
        const val ALBUM_SORT = "album_sort"
        const val PLAYS = "plays"
        const val KEPT = "kept"
        const val PREFETCH_QUEUE = "prefetch_queue"
        const val MOBILE_DOWNLOADS = "mobile_downloads"
        const val WARMED_AT = "warmed_at"
        const val COVER_ACCENT = "cover_accent"
        const val WIDE_SIDEBAR = "wide_sidebar"
        const val SHUFFLE = "shuffle"
        const val VOLUME = "volume"
        const val SHELF = "shelf"
        const val LYRICS_OPEN = "lyrics_open"
        const val LIBRARY_GRID = "library_grid"
        const val RECENT_SEARCHES = "recent_searches"
        const val THEME_MODE = "theme_mode"
        const val ANIMATED_BACKDROP = "animated_backdrop"
        const val REDUCE_MOTION = "reduce_motion"
        const val LYRICS_SIZE = "lyrics_size"
        const val SHOW_ADDED_BY = "show_added_by"
        const val HAPTICS = "haptics"
        const val SWIPE_BACK = "swipe_back"
        const val TRANSLATE_EMAIL = "translate_email"
    }
}
