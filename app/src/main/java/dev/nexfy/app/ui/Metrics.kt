package dev.nexfy.app.ui

import android.util.Log
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState

/**
 * Logs every janky frame (tag `NexfyJank`) with what the app was doing:
 * the screen, whether it was moving between screens, and the player's
 * state. Reading them back while someone uses the app shows where it
 * stutters in real use.
 */
fun trackJank(window: Window): JankStats =
    JankStats.createAndTrack(window) { frame ->
        if (frame.isJank) {
            val states = frame.states.joinToString(" ") { "${it.key}=${it.value}" }
            Log.d("NexfyJank", "%.1f ms %s".format(frame.frameDurationUiNanos / 1e6, states))
        }
    }.apply {
        // Flag frames 1.5x over the refresh budget (12.5 ms at 120 Hz) instead of 2x.
        jankHeuristicMultiplier = 1.5f
    }

/** Tag the frames that follow with `key=value` while [value] holds. */
@Composable
fun MetricsState(key: String, value: String?) {
    val view = LocalView.current
    val holder = remember(view) { PerformanceMetricsState.getHolderForHierarchy(view) }
    LaunchedEffect(holder, value) {
        val state = holder.state ?: return@LaunchedEffect
        if (value == null) state.removeState(key) else state.putState(key, value)
    }
}
