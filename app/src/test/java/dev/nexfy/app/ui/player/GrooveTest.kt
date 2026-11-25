package dev.nexfy.app.ui.player

import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.nexfy_core.AudioPulse

class GrooveTest {
    private val frame = 1f / 120f

    private fun Groove.feed(pulse: AudioPulse, seconds: Float) = repeat((seconds / frame).toInt()) { step(pulse, frame) }

    @Test
    fun aSteadyBassLineDoesNotKick() {
        val groove = Groove()
        groove.feed(AudioPulse(.2f, .3f, .05f), 4f)
        assertTrue(groove.kick < .05f)
        assertTrue(groove.live > .95f)
    }

    @Test
    fun aDrumHitKicksAtOnceAndFadesAfter() {
        val groove = Groove()
        groove.feed(AudioPulse(.1f, .3f, .05f), 4f)
        groove.feed(AudioPulse(.4f, .4f, .05f), .06f)
        val peak = groove.kick
        assertTrue(peak > .6f)
        groove.feed(AudioPulse(.1f, .3f, .05f), 1f)
        assertTrue(groove.kick < peak * .2f)
    }

    @Test
    fun silenceLetsEverythingRest() {
        val groove = Groove()
        groove.feed(AudioPulse(.3f, .3f, .1f), 2f)
        groove.feed(AudioPulse(0f, 0f, 0f), 10f)
        assertTrue(groove.resting)
    }
}
