package dev.nexfy.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uniffi.nexfy_core.MediaButton
import uniffi.nexfy_core.MediaButtonListener
import uniffi.nexfy_core.SystemMediaControls

/**
 * What plays, in the system's own media controls: the volume flyout, the
 * lock screen, the media keys of a keyboard or a headset with the window in
 * the background. The desktop's counterpart of the phone's media session,
 * and like it, it follows another device playing as well as this one.
 */
class SystemMedia(private val services: NexfyServices, private val scope: CoroutineScope) {
    /**
     * Ties the controls to the window [handle] (its HWND). False where the
     * system has none; the media keys then reach the app only with its
     * window focused, through the shell.
     */
    fun attach(handle: Long): Boolean {
        val controls = runCatching { SystemMediaControls(handle.toULong(), Buttons()) }.getOrNull() ?: return false
        val bridge = services.bridge
        // The song: only when it changes, so a position tick sends nothing.
        scope.launch {
            bridge.state.map { it.track }.distinctUntilChanged { a, b -> a?.uri == b?.uri && a?.coverUrl == b?.coverUrl }
                .collect { track ->
                    if (track == null) {
                        controls.clear()
                    } else {
                        controls.show(track.title, track.artists, track.album, track.coverUrl, track.durationMs.toULong())
                    }
                }
        }
        // Playing or not, and from where: the flyout runs its own clock from
        // there, so only a play, a pause or a seek is told.
        scope.launch {
            bridge.state.distinctUntilChanged { a, b ->
                a.track?.uri == b.track?.uri && a.playing == b.playing && a.positionMs == b.positionMs
            }.collect { s ->
                if (s.track != null) controls.setPlaying(s.playing, s.currentPositionMs().coerceAtLeast(0).toULong())
            }
        }
        return true
    }

    /** The system calls in on a thread of its own; the commands are thread-safe. */
    private inner class Buttons : MediaButtonListener {
        override fun pressed(button: MediaButton) {
            scope.launch {
                val commands = services.commands
                val s = services.bridge.state.value
                when (button) {
                    MediaButton.Play -> if (!s.playing) commands.playPause()
                    MediaButton.Pause -> if (s.playing) commands.playPause()
                    MediaButton.Toggle -> commands.playPause()
                    MediaButton.Next -> commands.next()
                    MediaButton.Previous -> commands.previous()
                    is MediaButton.SeekBy -> commands.seek(s.currentPositionMs() + button.deltaMs)
                    is MediaButton.SeekTo -> commands.seek(button.positionMs.toLong())
                }
            }
        }
    }
}
