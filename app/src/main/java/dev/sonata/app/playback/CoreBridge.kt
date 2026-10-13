package dev.sonata.app.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import uniffi.sonata_core.CoreEvent
import uniffi.sonata_core.CoreListener
import uniffi.sonata_core.QueueState
import uniffi.sonata_core.RepeatMode
import uniffi.sonata_core.SonataCore
import uniffi.sonata_core.Track
import uniffi.sonata_core.TrackInfo

val EmptyQueue = QueueState(null, emptyList(), null, emptyList(), false, RepeatMode.OFF)

/** What the core last reported. Positions are extrapolated while playing. */
data class PlaybackSnapshot(
    val track: TrackInfo? = null,
    val queue: QueueState = EmptyQueue,
    val playing: Boolean = false,
    val loading: Boolean = false,
    /** Playback stopped here: the context ended or another device took over. */
    val stopped: Boolean = false,
    val positionMs: Long = 0,
    /** [SystemClock.elapsedRealtime] when [positionMs] was reported. */
    val positionAt: Long = 0,
    val error: String? = null,
) {
    fun currentPositionMs(now: Long = SystemClock.elapsedRealtime()): Long =
        if (playing) positionMs + (now - positionAt) else positionMs
}

/**
 * Receives core events (on core threads) and folds them into a [StateFlow]
 * the playback service and the UI observe.
 */
class CoreBridge(core: SonataCore) : CoreListener {
    private val _state = MutableStateFlow(PlaybackSnapshot())
    val state: StateFlow<PlaybackSnapshot> = _state

    init {
        core.setListener(this)
    }

    override fun onEvent(event: CoreEvent) {
        val now = SystemClock.elapsedRealtime()
        _state.update { s ->
            when (event) {
                is CoreEvent.TrackChanged -> s.copy(track = event.track, stopped = false, error = null)
                is CoreEvent.Playing -> s.copy(
                    playing = true, loading = false, stopped = false,
                    positionMs = event.positionMs.toLong(), positionAt = now,
                )
                is CoreEvent.Paused -> s.copy(
                    playing = false, loading = false,
                    positionMs = event.positionMs.toLong(), positionAt = now,
                )
                is CoreEvent.Loading -> s.copy(loading = true, stopped = false)
                is CoreEvent.Stopped -> s.copy(
                    playing = false, loading = false, stopped = true,
                    positionMs = s.currentPositionMs(now), positionAt = now,
                )
                is CoreEvent.QueueChanged -> s.copy(queue = event.queue)
                is CoreEvent.Error -> s.copy(error = event.message)
            }
        }
    }

    /** Surface a failed command; the core only reports errors from its own events. */
    fun reportError(message: String) = _state.update { it.copy(error = message) }

    /** Metadata of the songs either side of the current one, fetched ahead. */
    @Volatile
    private var neighbours: Pair<Track?, Track?> = null to null

    fun setNeighbours(next: Track?, previous: Track?) {
        neighbours = next to previous
    }

    /**
     * Show the next or previous song right away, before Spotify confirms
     * the skip; the real TrackChanged event then replaces it.
     */
    fun preview(forward: Boolean) {
        val s = _state.value
        // Going back past 3 s only restarts the current song.
        if (!forward && s.currentPositionMs() > 3000) return
        val target = (if (forward) neighbours.first else neighbours.second) ?: return
        val now = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                track = TrackInfo(target.uri, target.title, target.artists, target.album, target.imageUrl, target.durationMs),
                loading = true,
                positionMs = 0,
                positionAt = now,
            )
        }
    }
}
