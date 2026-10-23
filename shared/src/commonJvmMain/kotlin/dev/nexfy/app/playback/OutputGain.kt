package dev.nexfy.app.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uniffi.nexfy_core.NexfyCore

/**
 * The one gain on the player's output, out of two independent reasons to
 * lower it: the volume the user set, and the sleep timer fading the song
 * out. The core takes a single number, so they are multiplied here —
 * otherwise a fade ending would put the volume back to full.
 *
 * A phone has its own volume keys and leaves [volume] at 1; a computer's
 * window carries a slider, since the system mixer is a trip away.
 */
class OutputGain(private val core: NexfyCore, volume: Double = 1.0) {
    private val _volume = MutableStateFlow(volume.coerceIn(0.0, 1.0))

    /** What the user set, 0 to 1. */
    val volume: StateFlow<Double> = _volume

    private var fade = 1.0

    @Synchronized
    fun setVolume(value: Double) {
        _volume.value = value.coerceIn(0.0, 1.0)
        apply()
    }

    /** The sleep timer's fade, 0 to 1; it comes back to 1 when the fade ends. */
    @Synchronized
    fun setFade(value: Double) {
        fade = value.coerceIn(0.0, 1.0)
        apply()
    }

    private fun apply() = core.setFade(_volume.value * fade)
}
