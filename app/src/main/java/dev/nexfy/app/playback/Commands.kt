package dev.nexfy.app.playback

import dev.nexfy.app.NexfyApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uniffi.nexfy_core.RemoteCommand
import uniffi.nexfy_core.RemoteState

/**
 * Playback commands shared by the screens and the home-screen widget: to
 * this phone through the core, or to the device playing elsewhere.
 */
class Commands(private val app: NexfyApp, private val scope: CoroutineScope) {
    private val core get() = app.core
    private val bridge get() = app.bridge

    /** The device playing elsewhere, if any: commands go to it. */
    val remote get() = bridge.state.value.remote

    /** Run a command off the main thread, reporting failures. */
    fun run(action: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                // The widget can wake the app with no session yet.
                if (!core.isConnected()) core.restoreSession()
                action()
            } catch (e: Exception) {
                bridge.reportError(e.message ?: e.toString())
            }
        }
    }

    /** Send a command to the remote device, showing its effect at once. */
    fun remote(cmd: RemoteCommand, show: ((RemoteState) -> RemoteState)? = null) {
        show?.let(bridge::updateRemote)
        run { core.remoteCommand(cmd) }
        app.refreshRemoteSoon()
    }

    fun playPause() {
        val playing = bridge.state.value.playing
        if (remote != null) {
            remote(if (playing) RemoteCommand.Pause else RemoteCommand.Play) { it.copy(playing = !playing) }
            return
        }
        run { core.playPause() }
    }

    fun next() {
        if (remote != null) return remote(RemoteCommand.Next)
        bridge.preview(forward = true)
        run { core.next() }
    }

    fun previous() {
        if (remote != null) return remote(RemoteCommand.Previous)
        bridge.preview(forward = false)
        run { core.previous() }
    }
}
