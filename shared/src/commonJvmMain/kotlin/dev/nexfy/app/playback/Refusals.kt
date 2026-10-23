package dev.nexfy.app.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
