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

/** How long a remote skip shown ahead outlasts polls still naming the old song. */
private const val REMOTE_SKIP_HOLD_MS = 4000L

/** How long a toggle shown ahead waits for the player to confirm it. */
private const val EXPECT_MS = 4000L

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
    /** The clock runs only while the song plays: not paused, and not while it loads. */
    fun currentPositionMs(now: Long = SystemClock.elapsedRealtime()): Long =
        if (playing && !loading) positionMs + (now - positionAt) else positionMs
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

    /** How long each song plays here, for the listening stats. */
    val listening = ListenTracker()

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
    fun setRemote(report: RemoteState?) {
        val now = SystemClock.elapsedRealtime()
        if (now < holdUntil) return
        // This phone playing means it is the active device, whatever a late
        // report about another device says.
        val r = report.takeUnless { _local.value.playing }?.let { heldRemote(it, now) }
        // After a skip shown ahead, a late poll may still name the old song:
        // keep the new one until the device confirms or the hold runs out.
        skipShown?.let { (oldUri, until) ->
            if (now < until && r?.track?.uri == oldUri) return
            if (now >= until || (r?.track != null && r.track?.uri != oldUri)) skipShown = null
        }
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

    /**
     * A toggle shown ahead (shuffle, repeat, play/pause), and until when it
     * overrides reports that do not have it yet: the first report after a
     * command often still carries the old state, which made the toggle bounce.
     */
    private var expectedRemote: Pair<(RemoteState) -> RemoteState, Long>? = null
    private var expectedQueue: Pair<(QueueState) -> QueueState, Long>? = null

    /** Hold [change] over remote reports until one has it, or [EXPECT_MS] passes. */
    @Synchronized
    fun expectRemote(change: (RemoteState) -> RemoteState) {
        expectedRemote = change to SystemClock.elapsedRealtime() + EXPECT_MS
    }

    /** The same for this phone's queue (shuffle, repeat). */
    @Synchronized
    fun expectQueue(change: (QueueState) -> QueueState) {
        expectedQueue = change to SystemClock.elapsedRealtime() + EXPECT_MS
    }

    /** A report with the change still expected applied, clearing it once the report has it. */
    private fun heldRemote(r: RemoteState, now: Long): RemoteState {
        val (change, until) = expectedRemote ?: return r
        if (now >= until) {
            expectedRemote = null
            return r
        }
        val held = change(r)
        if (held == r) expectedRemote = null
        return held
    }

    private fun heldQueue(q: QueueState, now: Long): QueueState {
        val (change, until) = expectedQueue ?: return q
        if (now >= until) {
            expectedQueue = null
            return q
        }
        val held = change(q)
        if (held == q) expectedQueue = null
        return held
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

    /** Moves with every core event, so an undo knows whether the core spoke since. */
    @Volatile
    private var heard = 0L

    /**
     * Show a command's effect on this phone at once, before the core
     * confirms it. Returns the undo for when the core refuses; it does
     * nothing once a real event has replaced the guess.
     */
    @Synchronized
    fun optimistic(change: (PlaybackSnapshot) -> PlaybackSnapshot): () -> Unit {
        val before = _local.value
        val at = heard
        val after = change(before)
        _local.value = after
        publish()
        return {
            synchronized(this) {
                val shown = _local.value
                when {
                    heard == at -> _local.value = before
                    // Events about the old song came meanwhile, but the song
                    // shown ahead is still up loading: take back just that.
                    after.loading && shown.loading && shown.track == after.track && after.track != before.track ->
                        _local.value = shown.copy(track = before.track, loading = before.loading)
                    else -> return@synchronized
                }
                publish()
            }
        }
    }

    override fun onEvent(event: CoreEvent) {
        val now = SystemClock.elapsedRealtime()
        heard++
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
                is CoreEvent.QueueChanged -> s.copy(queue = heldQueue(event.queue, now))
                is CoreEvent.Error -> s.copy(error = event.message)
            }
        }
        listening.update(_local.value)
        publish()
    }

    /** Surface a failed command; the core only reports errors from its own events. */
    fun reportError(message: String) {
        _local.update { it.copy(error = message) }
        publish()
    }

    /** The other device's songs either side of its current one, fetched ahead. */
    @Volatile
    private var remoteNeighbours: Pair<Track?, Track?> = null to null

    /** The song a remote skip replaced, and until when late polls naming it are ignored. */
    private var skipShown: Pair<String, Long>? = null

    fun setRemoteNeighbours(previous: Track?, next: Track?) {
        remoteNeighbours = previous to next
    }

    /** A skip on the other device, shown at once: its confirmation takes a second or more. */
    @Synchronized
    private fun previewRemote(forward: Boolean) {
        val target = (if (forward) remoteNeighbours.second else remoteNeighbours.first) ?: return
        val oldUri = remote?.first?.track?.uri ?: return
        skipShown = oldUri to SystemClock.elapsedRealtime() + REMOTE_SKIP_HOLD_MS
        remoteNeighbours = null to null
        updateRemote { it.copy(track = target, progressMs = 0u) }
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
    fun preview(forward: Boolean): () -> Unit {
        if (remote != null) {
            previewRemote(forward)
            return {}
        }
        val target = (if (forward) neighbours.first else neighbours.second) ?: return {}
        val now = SystemClock.elapsedRealtime()
        return optimistic {
            it.copy(
                track = TrackInfo(target.uri, target.title, target.artists, target.album, target.imageUrl, target.durationMs),
                loading = true,
                positionMs = 0,
                positionAt = now,
            )
        }
    }
}
