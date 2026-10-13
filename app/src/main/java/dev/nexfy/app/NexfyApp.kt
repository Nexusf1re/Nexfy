package dev.nexfy.app

import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import androidx.core.content.ContextCompat
import dev.nexfy.app.playback.CoreBridge
import dev.nexfy.app.playback.PlaybackService
import dev.nexfy.core.NativeInit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import android.util.Log
import uniffi.nexfy_core.NexfyCore

/** Owns the single Rust core, shared by the UI and the playback service. */
class NexfyApp : Application(), SingletonImageLoader.Factory {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val core: NexfyCore by lazy {
        NativeInit.init(this)
        NexfyCore(filesDir.resolve("spotify").path, deviceName())
    }

    val bridge: CoreBridge by lazy { CoreBridge(core) }

    val library: LibraryPrefs by lazy { LibraryPrefs(this) }

    /** Covers fade in as they load instead of popping over their placeholder. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).crossfade(true).build()

    override fun onCreate() {
        super.onCreate()
        // Playback can start from another device (Spotify Connect) while no
        // screen is open: bring up the media service so it gets a
        // notification and stays alive.
        scope.launch {
            bridge.local.distinctUntilChangedBy { it.playing }.collect { s ->
                if (s.playing && !PlaybackService.running) {
                    runCatching {
                        ContextCompat.startForegroundService(this@NexfyApp, Intent(this@NexfyApp, PlaybackService::class.java))
                    }
                }
            }
        }
        // While a screen is open, follow what the account plays on other
        // devices: faster while one of them is playing.
        scope.launch {
            visible.collectLatest { shown ->
                if (!shown) return@collectLatest
                while (true) {
                    refreshRemote()
                    delay(if (bridge.state.value.remote != null) 2_500 else 5_000)
                }
            }
        }
        // Fetch the songs either side of the current one ahead, so a skip
        // can show the new song at once.
        scope.launch {
            bridge.local
                .map { it.queue.next.firstOrNull()?.uri to it.queue.previous.lastOrNull()?.uri }
                .distinctUntilChanged()
                .collect { (next, previous) ->
                    val uris = listOfNotNull(next, previous)
                    if (uris.isEmpty()) return@collect
                    runCatching { core.tracksInfo(uris) }.onSuccess { tracks ->
                        bridge.setNeighbours(tracks.find { it.uri == next }, tracks.find { it.uri == previous })
                    }
                }
        }
    }

    /** Whether a screen of the app is showing; remote playback is only polled then. */
    val visible = MutableStateFlow(false)

    /** Read what the account is playing now and mirror it. */
    suspend fun refreshRemote() {
        if (!core.isConnected()) return
        runCatching { core.remoteState() }
            .onSuccess { bridge.setRemote(it) }
            .onFailure { Log.d("Nexfy", "remote state: ${it.message}") }
    }

    /** Poll again shortly: after a command, Spotify takes a moment to report it. */
    fun refreshRemoteSoon() {
        scope.launch {
            delay(700)
            refreshRemote()
            delay(1_500)
            refreshRemote()
        }
    }

    /** How the phone appears in Spotify's device list: "Nexfy · Galaxy S23+". */
    private fun deviceName(): String {
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        return "Nexfy · $name"
    }
}
