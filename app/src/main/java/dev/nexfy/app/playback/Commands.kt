package dev.nexfy.app.playback

import android.os.SystemClock
import dev.nexfy.app.Connectivity
import dev.nexfy.app.NexfyApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import uniffi.nexfy_core.RemoteCommand
import uniffi.nexfy_core.RemoteState
import uniffi.nexfy_core.RepeatMode

/** A command that did not go through: what to tell, and a new [id] each time. */
data class Refusal(val id: Long, val message: String)

/** The last refused command; the play controls shake when it changes. */
object Refusals {
    private val _last = MutableStateFlow<Refusal?>(null)
    val last: StateFlow<Refusal?> = _last

    fun post(message: String) {
        _last.value = Refusal(System.nanoTime(), message)
    }
}

/** Long enough for a reconnect, short enough that a dead network never leaves a tap hanging. */
private const val COMMAND_TIMEOUT_MS = 12_000L

/** How long a song may load before the wait is explained. */
private const val LOADING_PATIENCE_MS = 8_000L

/**
 * Playback commands shared by the screens and the home-screen widget: to
 * this phone through the core, or to the device playing elsewhere. Each
 * shows its effect at once and takes it back if the command fails.
 */
class Commands(private val app: NexfyApp, private val scope: CoroutineScope) {
    private val core get() = app.core
    private val bridge get() = app.bridge

    /** The device playing elsewhere, if any: commands go to it. */
    val remote get() = bridge.state.value.remote

    /**
     * Run a command off the main thread. On failure [undo] takes back what
     * was shown ahead and the refusal is told in words a person reads.
     */
    fun run(undo: () -> Unit = {}, connect: Boolean = true, action: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                withTimeout(COMMAND_TIMEOUT_MS) {
                    // The widget can wake the app with no session yet. Offline
                    // there is none to start: the songs on the phone play without.
                    if (connect && online() && !core.isConnected()) core.restoreSession()
                    action()
                }
            } catch (e: Exception) {
                undo()
                refuse(e)
            }
        }
    }

    private fun refuse(e: Exception) = refuse(
        when {
            !online() -> "Sem conexão: só toca o que está baixado"
            e is TimeoutCancellationException -> "O Spotify não respondeu. Tente de novo"
            else -> e.message ?: "Não deu certo"
        },
    )

    private fun refuse(message: String) {
        bridge.reportError(message)
        app.edits.tell(message)
        Refusals.post(message)
    }

    private fun online() = Connectivity.online.value

    private var loadingWatch: Job? = null

    /**
     * A skip the core accepts can still hang: with no network the song
     * never arrives and the player waits without an error. Say so once it
     * has loaded too long; it still starts if the network comes back.
     */
    fun watchLoading() {
        loadingWatch?.cancel()
        loadingWatch = scope.launch {
            delay(LOADING_PATIENCE_MS)
            if (remote == null && bridge.local.value.loading) {
                refuse(if (online()) "A música está demorando para carregar" else "Sem conexão: essa música não está baixada")
            }
        }
    }

    /** Send a command to the remote device, showing its effect at once. */
    fun remote(cmd: RemoteCommand, hold: Boolean = false, show: ((RemoteState) -> RemoteState)? = null) {
        show?.let(bridge::updateRemote)
        // A toggle stays as shown until the device confirms it, not undone by a late report.
        if (hold) show?.let(bridge::expectRemote)
        run { core.remoteCommand(cmd) }
        app.refreshRemoteSoon()
    }

    /**
     * Play the radio of a song or artist ([seedUri]): here, or on the device
     * playing elsewhere. The core finds it over the session; both places
     * then play it as a context (or, failing that, a list of songs).
     */
    /** [onPlaylist] gets the radio's playlist id, to open its page as Spotify does. */
    fun startRadio(seedUri: String, onPlaylist: (String) -> Unit = {}) {
        android.widget.Toast.makeText(app, "Iniciando rádio", android.widget.Toast.LENGTH_SHORT).show()
        // The radio is read once (the core keeps it a day) and opened as soon as it is known.
        scope.launch {
            val radio = try {
                core.radio(seedUri)
            } catch (e: Exception) {
                refuse(e)
                return@launch
            }
            radio.contextUri?.takeIf { it.startsWith("spotify:playlist:") }?.substringAfterLast(':')?.let(onPlaylist)
            playRadio(seedUri, radio)
        }
    }

    private fun playRadio(seedUri: String, radio: uniffi.nexfy_core.Radio) {
        if (remote == null) {
            run { core.startRadio(seedUri) }
            return
        }
        run {
            core.remoteCommand(RemoteCommand.Shuffle(false))
            val context = radio.contextUri
            if (context != null) {
                core.remoteCommand(RemoteCommand.PlayContext(context, null))
            } else {
                core.remoteCommand(RemoteCommand.PlayTracks(radio.trackUris, 0u))
            }
        }
        app.refreshRemoteSoon()
    }

    fun playPause() {
        val playing = bridge.state.value.playing
        if (remote != null) {
            remote(if (playing) RemoteCommand.Pause else RemoteCommand.Play, hold = true) { it.copy(playing = !playing) }
            return
        }
        val undo = showPlaying(!playing)
        run(undo) { if (playing) core.pause() else core.resume() }
    }

    /** The play button turns and the clock stops or starts in the same frame as the tap. */
    fun showPlaying(playing: Boolean): () -> Unit {
        val now = SystemClock.elapsedRealtime()
        return bridge.optimistic { it.copy(playing = playing, positionMs = it.currentPositionMs(now), positionAt = now) }
    }

    fun next() {
        val undo = bridge.preview(forward = true)
        if (remote != null) return remote(RemoteCommand.Next)
        run(undo) { core.next() }
        watchLoading()
    }

    fun previous() {
        val undo = bridge.preview(forward = false)
        if (remote != null) return remote(RemoteCommand.Previous)
        run(undo) { core.previous() }
        watchLoading()
    }

    fun seek(positionMs: Long) {
        val at = positionMs.coerceAtLeast(0).toUInt()
        if (remote != null) return remote(RemoteCommand.Seek(at)) { it.copy(progressMs = at) }
        val now = SystemClock.elapsedRealtime()
        val undo = bridge.optimistic { it.copy(positionMs = at.toLong(), positionAt = now) }
        run(undo) { core.seek(at) }
    }

    fun setShuffle(on: Boolean) {
        if (remote != null) return remote(RemoteCommand.Shuffle(on), hold = true) { it.copy(shuffle = on) }
        val undo = bridge.optimistic { it.copy(queue = it.queue.copy(shuffle = on)) }
        bridge.expectQueue { it.copy(shuffle = on) }
        run(undo) { core.setShuffle(on) }
    }

    fun setRepeat(mode: RepeatMode) {
        if (remote != null) return remote(RemoteCommand.Repeat(mode), hold = true) { it.copy(repeat = mode) }
        val undo = bridge.optimistic { it.copy(queue = it.queue.copy(repeat = mode)) }
        bridge.expectQueue { it.copy(repeat = mode) }
        run(undo) { core.setRepeat(mode) }
    }
}
