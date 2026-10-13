package dev.sonata.app

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
import dev.sonata.app.playback.PlaybackService
import dev.sonata.app.ui.SonataRoot
import dev.sonata.app.ui.theme.SonataTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        val app = application as SonataApp
        // Reconnect playback with the saved credential, if any.
        lifecycleScope.launch { runCatching { app.core.restoreSession() } }

        enableEdgeToEdge()
        setContent {
            SonataTheme {
                SonataRoot(
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
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }
}
