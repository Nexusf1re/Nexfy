package dev.nexfy.app.playback

import dev.nexfy.app.elapsedRealtimeMs
import dev.nexfy.app.Listen
import uniffi.nexfy_core.TrackInfo

/**
 * Times how long each song plays on this phone, from the snapshots the
 * bridge folds core events into. A song counts once it played [MIN_MS]
 * (Spotify's own threshold for a stream); it is reported again whenever it
 * played on since, so the log holds the time actually listened.
 */
class ListenTracker {
    /** Receives each counted song; called on core threads. */
    @Volatile
    var onListen: ((Listen) -> Unit)? = null

    private var track: TrackInfo? = null
    private var startedAt = 0L
    private var listened = 0L
    private var reported = 0L

    /** The playlist or album it was played from, for the stats. */
    private var context: String? = null

    /** [elapsedRealtimeMs] since when the song has been playing; null while it is not. */
    private var since: Long? = null

    @Synchronized
    fun update(s: PlaybackSnapshot) {
        val now = elapsedRealtimeMs()
        since?.let { listened += now - it }
        since = null
        if (s.track?.uri != track?.uri) {
            report()
            track = s.track
            context = s.queue.contextUri
            startedAt = System.currentTimeMillis()
            listened = 0
            reported = 0
        }
        if (track != null && s.playing) since = now
        // Pauses and seeks report too, so little is lost if the app is killed.
        report()
    }

    private fun report() {
        val t = track ?: return
        if (listened < MIN_MS || listened - reported < 1_000) return
        reported = listened
        onListen?.invoke(
            Listen(
                t.uri, t.title, t.artists, emptyList(), t.album, t.coverUrl, startedAt, listened,
                contextUri = context, durationMs = t.durationMs.toLong(),
            ),
        )
    }

    private companion object {
        const val MIN_MS = 30_000L
    }
}
