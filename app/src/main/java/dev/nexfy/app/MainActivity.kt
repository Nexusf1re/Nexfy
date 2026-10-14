package dev.nexfy.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.nexfy.app.ui.design.Motion
import dev.nexfy.app.ui.design.systemAnimationsOff
import dev.nexfy.app.ui.theme.Look
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.nexfy.app.playback.PlaybackService
import dev.nexfy.app.ui.NexfyRoot
import dev.nexfy.app.ui.player.OpenPlayer
import dev.nexfy.app.ui.trackJank
import androidx.metrics.performance.JankStats
import dev.nexfy.app.ui.theme.NexfyTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var jank: JankStats? = null

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        val app = application as NexfyApp
        // Reconnect playback with the saved credential, if any.
        lifecycleScope.launch { runCatching { app.core.restoreSession() } }
        // A widget tap opens the full player; not again on recreation.
        if (savedInstanceState == null) {
            OpenPlayer.from(intent)
            dev.nexfy.app.ui.SpotifyLinks.from(intent)
        }

        enableEdgeToEdge()
        jank = trackJank(window)
        // The cover the accent follows; only its URL, so position ticks pass it by.
        val accentArt = combine(
            app.bridge.state.map { it.track?.coverUrl }.distinctUntilChanged(),
            app.library.coverAccent,
        ) { url, on -> url.takeIf { on } }.distinctUntilChanged()
        val library = app.library
        setContent {
            val mode by library.themeMode.collectAsState()
            val dark = when (mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // The default edge-to-edge styles, but the icons follow the chosen theme, not the system's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    SystemBarStyle.auto(Color.argb(0xe6, 0xFF, 0xFF, 0xFF), Color.argb(0x80, 0x1b, 0x1b, 0x1b)) { dark },
                )
                onDispose { }
            }
            val reduce by library.reduceMotion.collectAsState()
            val reduced = reduce || systemStill
            SideEffect { Motion.reduced = reduced }
            val backdrop by library.animatedBackdrop.collectAsState()
            val lyricsSize by library.lyricsSize.collectAsState()
            val addedBy by library.showAddedBy.collectAsState()
            val haptics by library.haptics.collectAsState()
            val look = remember(backdrop, lyricsSize, addedBy, haptics) { Look(backdrop, lyricsSize.scale, addedBy, haptics) }
            NexfyTheme(dark = dark, accentArt = accentArt, look = look) {
                // Silences the framework's own feedback too (long presses), not just Nexfy's Haptics.
                val feedback = LocalHapticFeedback.current
                CompositionLocalProvider(LocalHapticFeedback provides if (haptics) feedback else NoHaptics) {
                    NexfyRoot(
                        app = app,
                        controller = controller,
                        openBrowser = { url ->
                            CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
                        },
                    )
                }
            }
        }
    }

    /** The system's animations are off; read again each time the app comes back. */
    private var systemStill by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        OpenPlayer.from(intent)
        dev.nexfy.app.ui.SpotifyLinks.from(intent)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(this))
        (application as NexfyApp).visible.value = true
        jank?.isTrackingEnabled = true
        systemStill = systemAnimationsOff(this)
    }

    override fun onStop() {
        (application as NexfyApp).visible.value = false
        jank?.isTrackingEnabled = false
        controller?.release()
        controller = null
        super.onStop()
    }
}

/** Feedback that does nothing: "Vibração" off. */
private object NoHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}
