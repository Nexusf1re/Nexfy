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
import uniffi.sonata_core.TrackInfo

/** What the core last reported. Positions are extrapolated while playing. */
data class PlaybackSnapshot(
    val track: TrackInfo? = null,
    val queue: QueueState = QueueState(emptyList(), 0u, false, RepeatMode.OFF),
    val playing: Boolean = false,
    val loading: Boolean = false,
    val ended: Boolean = false,
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
                is CoreEvent.TrackChanged -> s.copy(track = event.track, ended = false, error = null)
                is CoreEvent.Playing -> s.copy(
                    playing = true, loading = false, ended = false,
                    positionMs = event.positionMs.toLong(), positionAt = now,
                )
                is CoreEvent.Paused -> s.copy(
                    playing = false, loading = false,
                    positionMs = event.positionMs.toLong(), positionAt = now,
                )
                is CoreEvent.Loading -> s.copy(loading = true, ended = false)
                is CoreEvent.Ended -> s.copy(
                    playing = false, loading = false, ended = true,
                    positionMs = s.currentPositionMs(now), positionAt = now,
                )
                is CoreEvent.QueueChanged -> s.copy(queue = event.queue)
                is CoreEvent.Error -> s.copy(error = event.message)
            }
        }
    }

    /** Surface a failed command; the core only reports errors from its own events. */
    fun reportError(message: String) = _state.update { it.copy(error = message) }
}
