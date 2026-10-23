package dev.nexfy.app

/**
 * Milliseconds on a clock that only moves forward and is never set back,
 * for deadlines and for how long ago something was reported. On the phone
 * it counts deep sleep too, so a sleep timer armed with the screen off
 * still fires on time.
 */
expect fun elapsedRealtimeMs(): Long
