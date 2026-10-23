package dev.nexfy.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dev.nexfy.app.DesktopServices
import dev.nexfy.app.LocalServices
import dev.nexfy.app.ThemeMode
import dev.nexfy.app.nexfyDataDir
import dev.nexfy.app.ui.DesktopBack
import dev.nexfy.app.ui.NexfyDesktop
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.systemAnimationsOff
import dev.nexfy.app.ui.openInBrowser
import dev.nexfy.app.ui.theme.Look
import dev.nexfy.app.ui.theme.NexfyTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toOkioPath
import uniffi.nexfy_core.NexfyCore

fun main() {
    // Covers fade in as they load and are kept, as they are on the phone:
    // they are revisited constantly and each one is fetched over the network.
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .crossfade(true)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, .25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(nexfyDataDir().resolve("covers").toOkioPath())
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .build()
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Nexfy",
            state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
            // Esc is this machine's back gesture; the innermost screen that
            // wants it takes it, and the window keeps the rest.
            onPreviewKeyEvent = { it.type == KeyEventType.KeyDown && it.key == Key.Escape && DesktopBack.pressed() },
        ) {
            Root()
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
            core.restoreSession()
            services = opened
        } catch (error: Throwable) {
            failure = error.stackTraceToString().lineSequence().take(8).joinToString("\n")
        }
    }

    val running = services
    when {
        failure != null -> Opening { Text(failure!!, style = MaterialTheme.typography.bodySmall) }
        running != null -> App(running)
        else -> Opening { Text("opening the core…", style = MaterialTheme.typography.titleMedium) }
    }
}

/** The window before the core is up, in plain dark so it is never a white flash. */
@Composable
private fun Opening(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { content() }
        }
    }
}

/** The app itself: the settings drive the theme, as they do on the phone. */
@Composable
private fun App(services: DesktopServices) {
    val context: PlatformContext = PlatformContext.INSTANCE
    val library = services.library
    val mode by library.themeMode.collectAsState()
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    // The cover the accent follows; only its URL, so position ticks pass it by.
    val accentArt = remember {
        combine(
            services.bridge.state.map { it.track?.coverUrl }.distinctUntilChanged(),
            library.coverAccent,
        ) { url, on -> url.takeIf { on } }.distinctUntilChanged()
    }
    val reduce by library.reduceMotion.collectAsState()
    val reduced = reduce || remember { systemAnimationsOff(context) }
    SideEffect { Motion.reduced = reduced }
    val backdrop by library.animatedBackdrop.collectAsState()
    val lyricsSize by library.lyricsSize.collectAsState()
    val addedBy by library.showAddedBy.collectAsState()
    val look = remember(backdrop, lyricsSize, addedBy) { Look(backdrop, lyricsSize.scale, addedBy, haptics = false) }
    NexfyTheme(dark = dark, accentArt = accentArt, look = look) {
        CompositionLocalProvider(LocalServices provides services) {
            NexfyDesktop(services, openBrowser = { openInBrowser(context, it) })
        }
    }
}
