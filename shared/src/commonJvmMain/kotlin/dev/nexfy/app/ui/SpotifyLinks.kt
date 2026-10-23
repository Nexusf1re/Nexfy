package dev.nexfy.app.ui

import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** What a Spotify link points at. */
sealed interface LinkTarget {
    data class Track(val uri: String) : LinkTarget
    data class Album(val id: String) : LinkTarget
    data class Playlist(val id: String) : LinkTarget
    data class Artist(val id: String) : LinkTarget
    data class Show(val id: String) : LinkTarget
    data class Jam(val link: String) : LinkTarget
}

/**
 * Spotify links opened with Nexfy (open.spotify.com, spotify.link, spotify:
 * URIs). Whatever caught the link puts it in [pending] and the app opens it
 * once it is up.
 */
object SpotifyLinks {
    /** The link waiting to be opened, if any. */
    val pending = MutableStateFlow<String?>(null)

    /** What [link] points at; short links are followed to the full one first. */
    suspend fun resolve(link: String): LinkTarget? {
        val full = when (host(link)) {
            "spotify.link", "spotify.app.link" -> follow(link) ?: return null
            else -> link
        }
        return parse(full)
    }

    private fun host(link: String) = runCatching { URI(link).host }.getOrNull()

    internal fun parse(link: String): LinkTarget? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        // spotify:album:<id>, or open.spotify.com/intl-pt/album/<id>?si=...
        val parts = if (uri.scheme == "spotify") {
            uri.schemeSpecificPart.split(':')
        } else {
            uri.path.orEmpty().split('/').filter { it.isNotEmpty() && !it.startsWith("intl-") }
        }
        if (parts.size < 2) return null
        val (kind, id) = parts[0] to parts[1]
        return when (kind) {
            "track" -> LinkTarget.Track("spotify:track:$id")
            "album" -> LinkTarget.Album(id)
            "playlist" -> LinkTarget.Playlist(id)
            "artist" -> LinkTarget.Artist(id)
            "show" -> LinkTarget.Show(id)
            "socialsession" -> LinkTarget.Jam("https://open.spotify.com/socialsession/$id")
            else -> null
        }
    }

    /** The open.spotify.com link a short link redirects to. */
    private suspend fun follow(link: String): String? = withContext(Dispatchers.IO) {
        var url = link
        repeat(5) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            try {
                val location = connection.getHeaderField("Location") ?: return@withContext null
                url = URL(URL(url), location).toString()
                if (host(url) == "open.spotify.com") return@withContext url
            } catch (_: Exception) {
                return@withContext null
            } finally {
                connection.disconnect()
            }
        }
        null
    }
}
