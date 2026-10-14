package dev.nexfy.app.ui.player

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.nexfy.app.NexfyApp
import dev.nexfy.app.playback.Sleep
import dev.nexfy.app.ui.design.rememberHaptics
import dev.nexfy.app.ui.theme.accentColor
import kotlinx.coroutines.delay

private val Durations = listOf(15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 h")

@Composable
private fun sleepTimer() = (LocalContext.current.applicationContext as NexfyApp).sleep

/** The song menu's row: "Timer de sono", with what is left when it is on. */
@Composable
fun sleepTimerLabel(): String {
    val sleep by sleepTimer().state.collectAsState()
    val left by produceState<String?>(null, sleep) {
        while (true) {
            value = when (val s = sleep) {
                null -> null
                Sleep.EndOfSong -> "fim da música"
                is Sleep.After -> {
                    val ms = (s.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                    "${(ms + 59_999) / 60_000} min"
                }
            }
            if (sleep !is Sleep.After) break
            delay(1_000)
        }
    }
    return left?.let { "Timer de sono · $it" } ?: "Timer de sono"
}

/** When to pause: after a while, or when this song ends. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(onDismiss: () -> Unit) {
    val timer = sleepTimer()
    val sleep by timer.state.collectAsState()
    val haptics = rememberHaptics()
    val pick = { action: () -> Unit ->
        haptics.tick()
        action()
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Timer de sono", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            Durations.forEach { (minutes, label) ->
                Option(label, (sleep as? Sleep.After)?.minutes == minutes) { pick { timer.after(minutes) } }
            }
            Option("Fim desta música", sleep == Sleep.EndOfSong) { pick(timer::endOfSong) }
            if (sleep != null) Option("Desligar timer", false) { pick(timer::cancel) }
        }
    }
}

@Composable
private fun Option(label: String, chosen: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (chosen) accentColor() else MaterialTheme.colorScheme.onSurface)
        if (chosen) Icon(Icons.Rounded.Check, null, tint = accentColor())
    }
}
