package dev.nexfy.app

import android.Manifest
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.nexfy.app.playback.PlaybackService
import dev.nexfy.app.ui.NexfyRoot
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

        enableEdgeToEdge()
        jank = trackJank(window)
        // The cover the accent follows; only its URL, so position ticks pass it by.
        val accentArt = combine(
            app.bridge.state.map { it.track?.coverUrl }.distinctUntilChanged(),
            app.library.coverAccent,
        ) { url, on -> url.takeIf { on } }.distinctUntilChanged()
        setContent {
            NexfyTheme(accentArt = accentArt) {
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

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(this))
        (application as NexfyApp).visible.value = true
        jank?.isTrackingEnabled = true
    }

    override fun onStop() {
        (application as NexfyApp).visible.value = false
        jank?.isTrackingEnabled = false
        controller?.release()
        controller = null
        super.onStop()
    }
}
