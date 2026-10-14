package dev.nexfy.app.playback

import kotlin.math.abs
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import uniffi.nexfy_core.CoreEvent
import uniffi.nexfy_core.CoreListener
import uniffi.nexfy_core.Device
import uniffi.nexfy_core.QueueState
import uniffi.nexfy_core.RemoteState
import uniffi.nexfy_core.RepeatMode
import uniffi.nexfy_core.NexfyCore
import uniffi.nexfy_core.Track
import uniffi.nexfy_core.TrackInfo

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
    /** Another device is playing: the snapshot mirrors it and controls go to it. */
    val remote: Device? = null,
) {
    fun currentPositionMs(now: Long = SystemClock.elapsedRealtime()): Long =
        if (playing) positionMs + (now - positionAt) else positionMs
}

/**
 * Receives core events (on core threads) and folds them into [local], this
 * phone's own playback, which the media session and service follow. [state]
 * is what the UI shows: [local], or the device playing elsewhere when
 * another one is (polled through [setRemote]).
 */
class CoreBridge(core: NexfyCore) : CoreListener {
    private val _local = MutableStateFlow(PlaybackSnapshot())
    val local: StateFlow<PlaybackSnapshot> = _local

    private val _state = MutableStateFlow(PlaybackSnapshot())
    val state: StateFlow<PlaybackSnapshot> = _state

    /** The other device playing and when it was read; null when it is this phone or nothing. */
    private var remote: Pair<RemoteState, Long>? = null

    /** Remote reports are ignored until then: playback was just moved here. */
    private var holdUntil = 0L

    init {
        core.setListener(this)
    }

    @Synchronized
    private fun publish() {
        val r = remote
        _state.value = if (r == null) _local.value else remoteSnapshot(r.first, r.second)
    }

    private fun remoteSnapshot(r: RemoteState, at: Long): PlaybackSnapshot {
        val t = r.track
        return PlaybackSnapshot(
            track = t?.let { TrackInfo(it.uri, it.title, it.artists, it.album, it.imageUrl, it.durationMs) },
            queue = EmptyQueue.copy(contextUri = r.contextUri, shuffle = r.shuffle, repeat = r.repeat),
            playing = r.playing,
            positionMs = r.progressMs.toLong(),
            positionAt = at,
            error = _local.value.error,
            remote = r.device,
        )
    }

    /** What the account is playing, from a poll: another device, this phone or nothing. */
    @Synchronized
    fun setRemote(r: RemoteState?) {
        val now = SystemClock.elapsedRealtime()
        if (now < holdUntil) return
        val old = remote
        if (r != null && !r.device.isSelf && old != null) {
            // A poll that only confirms the extrapolated position changes
            // nothing: publishing it would recompose every screen for naught.
            val (o, at) = old
            val expected = if (o.playing) o.progressMs.toLong() + (now - at) else o.progressMs.toLong()
            if (o.copy(progressMs = 0u) == r.copy(progressMs = 0u) && abs(r.progressMs.toLong() - expected) < 1500) return
        }
        remote = if (r == null || r.device.isSelf) null else r to now
        publish()
    }

    /** Playback was moved to this phone: show it at once, before polls catch up. */
    @Synchronized
    fun movedHere() {
        holdUntil = SystemClock.elapsedRealtime() + 6000
        remote = null
        publish()
    }

    /** Apply a command to the remote view right away; the next poll confirms it. */
    @Synchronized
    fun updateRemote(change: (RemoteState) -> RemoteState) {
        val (r, at) = remote ?: return
        val now = SystemClock.elapsedRealtime()
        val position = if (r.playing) r.progressMs.toLong() + (now - at) else r.progressMs.toLong()
        remote = change(r.copy(progressMs = position.coerceAtLeast(0).toUInt())) to now
        publish()
    }

    override fun onEvent(event: CoreEvent) {
        val now = SystemClock.elapsedRealtime()
        _local.update { s ->
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
        publish()
    }

    /** Surface a failed command; the core only reports errors from its own events. */
    fun reportError(message: String) {
        _local.update { it.copy(error = message) }
        publish()
    }

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
        if (remote != null) return
        val s = _local.value
        // Going back past 3 s only restarts the current song.
        if (!forward && s.currentPositionMs() > 3000) return
        val target = (if (forward) neighbours.first else neighbours.second) ?: return
        val now = SystemClock.elapsedRealtime()
        _local.update {
            it.copy(
                track = TrackInfo(target.uri, target.title, target.artists, target.album, target.imageUrl, target.durationMs),
                loading = true,
                positionMs = 0,
                positionAt = now,
            )
        }
        publish()
    }
}
