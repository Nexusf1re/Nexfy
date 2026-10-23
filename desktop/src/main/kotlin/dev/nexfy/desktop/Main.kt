package dev.nexfy.desktop

import java.io.File

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.nexfy.app.ui.NowPlayingProbe
import uniffi.nexfy_core.NexfyCore

/// Where the desktop app keeps the credential and the audio cache, the same
/// place core/examples/desktop_smoke.rs uses.
private fun cacheDir(): File {
    val base = System.getenv("LOCALAPPDATA")
        ?: System.getenv("XDG_DATA_HOME")
        ?: (System.getProperty("user.home") + "/.local/share")
    return File(base, "Nexfy").apply { mkdirs() }
}

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
 * Opens the Rust core on this computer and hands it to the shared UI. The
 * core registers as a Connect device, so a phone can push a playlist here.
 */
@Composable
private fun Root() {
    var core by remember { mutableStateOf<NexfyCore?>(null) }
    var username by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val opened = NexfyCore(cacheDir().absolutePath, "Nexfy PC")
            username = opened.restoreSession()
            core = opened
        } catch (error: Throwable) {
            failure = error.stackTraceToString().lineSequence().take(8).joinToString("\n")
        }
    }

    when {
        failure != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(failure!!, style = MaterialTheme.typography.bodySmall)
        }
        core != null -> NowPlayingProbe(core!!, username)
        else -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("opening the core…", style = MaterialTheme.typography.titleMedium)
        }
    }
}
