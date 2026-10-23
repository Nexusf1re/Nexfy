package dev.nexfy.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.nexfy.app.DesktopServices
import dev.nexfy.app.LocalServices
import dev.nexfy.app.nexfyDataDir
import dev.nexfy.app.ui.NowPlayingProbe
import uniffi.nexfy_core.NexfyCore

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Nexfy",
        state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) { Root() }
        }
    }
}

/**
 * Opens the Rust core on this computer, builds the services the shared
 * screens read, and hands them the window. The core registers as a Connect
 * device, so a phone can push a playlist here.
 */
@Composable
private fun Root() {
    val scope = rememberCoroutineScope()
    var services by remember { mutableStateOf<DesktopServices?>(null) }
    var username by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            // The same directory core/examples/desktop_smoke.rs used, so the
            // credential it stored is still the one this opens with.
            val core = NexfyCore(nexfyDataDir().absolutePath, "Nexfy PC")
            // The bridge must hear the core's events from the first one, so the
            // services are built before the session is asked for.
            val opened = DesktopServices(core, scope)
            opened.start()
            username = core.restoreSession()
            services = opened
        } catch (error: Throwable) {
            failure = error.stackTraceToString().lineSequence().take(8).joinToString("\n")
        }
    }

    val running = services
    when {
        failure != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(failure!!, style = MaterialTheme.typography.bodySmall)
        }
        running != null -> CompositionLocalProvider(LocalServices provides running) {
            NowPlayingProbe(running.core, username)
        }
        else -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("opening the core…", style = MaterialTheme.typography.titleMedium)
        }
    }
}
