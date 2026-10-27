package dev.nexfy.desktop

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.window.WindowDraggableArea
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dev.nexfy.app.ui.WindowFrame
import dev.nexfy.app.DesktopServices
import dev.nexfy.app.LocalServices
import dev.nexfy.app.SystemMedia
import dev.nexfy.app.ThemeMode
import dev.nexfy.app.nexfyDataDir
import dev.nexfy.app.ui.DesktopBack
import dev.nexfy.app.ui.DesktopKeys
import dev.nexfy.app.ui.NexfyDesktop
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.systemAnimationsOff
import dev.nexfy.app.ui.openInBrowser
import dev.nexfy.app.ui.theme.Look
import dev.nexfy.app.ui.theme.NexfyTheme
import androidx.compose.ui.awt.ComposeWindow
import java.awt.Taskbar
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
        // Where the window was last time, if that still suits the screens
        // attached now; read before the state so the window is never placed
        // twice, once by the OS and once by us.
        val remembered = remember { readWindowGeometry() }
        val windowState = rememberWindowState(
            size = remembered?.size ?: DpSize(1100.dp, 760.dp),
            position = remembered?.position ?: WindowPosition.PlatformDefault,
            // Compose sizes the window before it is shown and maximizes it
            // after, so the bounds above become the ones it unmaximizes to.
            placement = if (remembered?.maximized == true) {
                WindowPlacement.Maximized
            } else {
                WindowPlacement.Floating
            },
        )
        val geometry = rememberWindowGeometry(windowState, remembered)
        // Closing is the one move with no 250ms afterwards to write in.
        val quit = remember(geometry) { { geometry.flush(); exitApplication() } }
        Window(
            onCloseRequest = quit,
            title = "Nexfy",
            state = windowState,
            // The app draws its own frame, so the window wears the theme
            // instead of the system's grey strip.
            undecorated = true,
            // Esc is this machine's back gesture; the innermost screen that
            // wants it takes it, and the window keeps the rest. The media
            // keys drive playback wherever the focus is.
            onPreviewKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else if (event.key == Key.Escape) {
                    DesktopBack.pressed()
                } else {
                    DesktopKeys.pressed(event)
                }
            },
            // What the focused element let through: Space, the arrows with a
            // modifier and the letters reach the player only from here.
            onKeyEvent = { event -> event.type == KeyEventType.KeyDown && DesktopKeys.unhandled(event) },
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = MinWindowSize
                // Every size the icon is drawn at, so the taskbar, Alt+Tab
                // and the title of a dialog each take a sharp one.
                window.iconImages = appIcons()
                // Undecorated, the window had lost the shell's own motion,
                // its rounded corners and its resizing border.
                dressWindow(window)
            }
            val scope = this
            val frame = remember(scope, windowState, quit) {
                object : WindowFrame {
                    override val maximized get() = windowState.placement == WindowPlacement.Maximized

                    override fun minimize() {
                        windowState.isMinimized = true
                    }

                    override fun toggleMaximize() {
                        windowState.placement =
                            if (maximized) WindowPlacement.Floating else WindowPlacement.Maximized
                    }

                    override fun close() = quit()

                    @Composable
                    override fun DragArea(content: @Composable () -> Unit) {
                        with(scope) {
                            WindowDraggableArea(
                                // Double-click fills the screen, as on every window.
                                Modifier.pointerInput(Unit) {
                                    detectTapGestures(onDoubleTap = { toggleMaximize() })
                                },
                            ) { content() }
                        }
                    }
                }
            }
            Root(frame, window)
        }
    }
}

/**
 * Opens the Rust core on this computer, builds the services the shared
 * screens read, and hands them the window. The core registers as a Connect
 * device, so a phone can push a playlist here.
 */
@Composable
private fun Root(frame: WindowFrame, window: ComposeWindow) {
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
        running != null -> App(running, frame, window)
        else -> Opening { Text("opening the core…", style = MaterialTheme.typography.titleMedium) }
    }
}

/**
 * How far the song has gone, drawn on the app's taskbar button, as the
 * system draws a download's: the bar runs while it plays and stands paused,
 * in the system's paused colour, where it stopped; nothing playing, none.
 */
private suspend fun showProgressOnTaskbar(services: DesktopServices, window: ComposeWindow) {
    val taskbar = Taskbar.getTaskbar().takeIf {
        Taskbar.isTaskbarSupported() && it.isSupported(Taskbar.Feature.PROGRESS_STATE_WINDOW)
    } ?: return
    services.bridge.state.collectLatest { s ->
        val duration = s.track?.durationMs?.toLong() ?: 0L
        if (duration <= 0L) {
            taskbar.setWindowProgressState(window, Taskbar.State.OFF)
            return@collectLatest
        }
        taskbar.setWindowProgressState(window, if (s.playing) Taskbar.State.NORMAL else Taskbar.State.PAUSED)
        while (true) {
            val percent = (s.currentPositionMs().coerceIn(0L, duration) * 100 / duration).toInt()
            taskbar.setWindowProgressValue(window, percent)
            if (!s.playing) break
            // One step of the bar is a hundredth of the song.
            delay((duration / 100).coerceIn(1_000L, 5_000L))
        }
    }
}

/** The icon at each size icons/MakeIcons.java draws it. */
private fun appIcons(): List<java.awt.Image> = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256).mapNotNull { size ->
    object {}.javaClass.getResourceAsStream("/icons/nexfy-$size.png")?.use(ImageIO::read)
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
private fun App(services: DesktopServices, frame: WindowFrame, window: ComposeWindow) {
    val context: PlatformContext = PlatformContext.INSTANCE
    val library = services.library
    // The system's media controls, the phone's notification and watch here.
    // With them on, they take the media keys too, so the window leaves them.
    val scope = rememberCoroutineScope()
    LaunchedEffect(services) {
        DesktopKeys.systemMedia = SystemMedia(services, scope).attach(window.windowHandle)
    }
    LaunchedEffect(services) { showProgressOnTaskbar(services, window) }
    // Coming back to the window is the desktop's way of opening the app.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { services.visible.value = focused }
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
            NexfyDesktop(services, openBrowser = { openInBrowser(context, it) }, frame = frame)
        }
    }
}
