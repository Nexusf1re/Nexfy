package dev.nexfy.app.playback

import dev.nexfy.app.NexfyServices
import dev.nexfy.app.elapsedRealtimeMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.nexfy_core.RemoteCommand

/** What the sleep timer waits for. */
sealed interface Sleep {
    /** [deadline] is in [elapsedRealtimeMs] time, which counts deep sleep too. */
    data class After(val minutes: Int, val deadline: Long) : Sleep

    data object EndOfSong : Sleep
}

/**
 * Pauses playback after a while or when the song ends, fading out first.
 * It lives with the app rather than a screen so it keeps going with the
 * screen off; on the phone the playback service's wake lock keeps the CPU
 * up meanwhile.
 */
class SleepTimer(private val app: NexfyServices, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<Sleep?>(null)
    val state: StateFlow<Sleep?> = _state

    private var job: Job? = null

    fun after(minutes: Int) = arm(Sleep.After(minutes, elapsedRealtimeMs() + minutes * 60_000L))

    fun endOfSong() = arm(Sleep.EndOfSong)

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = null
    }

    private fun arm(sleep: Sleep) {
        job?.cancel()
        _state.value = sleep
        job = scope.launch {
            try {
                when (sleep) {
                    is Sleep.After -> untilDeadline(sleep.deadline)
                    Sleep.EndOfSong -> untilSongEnds()
                }
            } finally {
                // Cancelled mid-fade, or done: either way the volume comes back.
                app.output.setFade(1.0)
            }
            _state.compareAndSet(sleep, null)
        }
    }

    private suspend fun untilDeadline(deadline: Long) {
        while (true) {
            val left = deadline - elapsedRealtimeMs()
            if (left <= FADE_MS) break
            delay(minOf(left - FADE_MS, 30_000))
        }
        val remote = app.bridge.state.value.remote != null
        if (remote) return pauseRemote()
        if (!app.bridge.local.value.playing) return
        fade(FADE_MS)
        pauseHere(nextSong = false)
    }

    /** A skip moves the stop to the end of the new song. */
    private suspend fun untilSongEnds() {
        var song = ""
        var left = 0L
        while (true) {
            val s = app.bridge.state.value
            val track = s.track
            if (track == null || !s.playing) {
                delay(1_000)
                continue
            }
            song = track.uri
            left = track.durationMs.toLong() - s.currentPositionMs()
            if (left <= FADE_MS) break
            delay(minOf(left - FADE_MS, 5_000))
        }
        if (app.bridge.state.value.remote != null) {
            delay(left.coerceAtLeast(0))
            return pauseRemote()
        }
        // Fade over the song's last seconds; the next one may then start
        // (silent), so stop as soon as it does.
        fade(left.coerceAtLeast(500))
        val moved = withTimeoutOrNull(4_000) {
            app.bridge.local.first { it.track?.uri != song || !it.playing }
        }
        pauseHere(nextSong = moved?.playing == true)
    }

    /** Gain falls squared, which sounds even to the ear. */
    private suspend fun fade(ms: Long) {
        val steps = (ms / 100).coerceAtLeast(1)
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            app.output.setFade((1 - t) * (1 - t))
            delay(ms / steps)
        }
    }

    /**
     * Pause this phone, then wait for it to stop before the volume comes
     * back. A next song that began meanwhile goes back to its start.
     */
    private suspend fun pauseHere(nextSong: Boolean) {
        try {
            app.core.pause()
            withTimeoutOrNull(3_000) { app.bridge.local.first { !it.playing } }
            if (nextSong) app.core.seek(0u)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            app.bridge.reportError(e.message ?: e.toString())
        }
    }

    private fun pauseRemote() = app.commands.remote(RemoteCommand.Pause) { it.copy(playing = false) }

    private companion object {
        const val FADE_MS = 8_000L
    }
}
