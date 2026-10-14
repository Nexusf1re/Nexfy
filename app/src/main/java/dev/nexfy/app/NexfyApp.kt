package dev.nexfy.app

import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import coil3.request.crossfade
import androidx.core.content.ContextCompat
import dev.nexfy.app.playback.Commands
import dev.nexfy.app.playback.CoreBridge
import dev.nexfy.app.playback.PlaybackService
import dev.nexfy.app.playback.SleepTimer
import dev.nexfy.app.widget.NexfyWidget
import androidx.glance.appwidget.updateAll
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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

    /** Playback commands for the screens and the widget. */
    val commands: Commands by lazy { Commands(this, scope) }
    val sleep: SleepTimer by lazy { SleepTimer(this, scope) }
    val edits: LibraryEdits by lazy { LibraryEdits(this, scope) }

    /** Covers fade in as they load instead of popping over their placeholder. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .crossfade(true)
            // Covers are revisited constantly: keep many in memory and on disk.
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, .25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("covers").toOkioPath()).maxSizeBytes(512L * 1024 * 1024).build() }
            .build()

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
                    // Each poll spends the Web API quota Spotify gives development apps:
                    // often only while another device plays; this phone's own playback
                    // already reports when another device takes over.
                    val s = bridge.state.value
                    delay(
                        when {
                            s.remote != null -> if (s.playing) 5_000 else 15_000
                            s.playing -> 45_000
                            else -> 20_000
                        },
                    )
                }
            }
        }
        // Songs on the phone: on Wi-Fi, download the next songs of the queue
        // and every kept playlist and album into the audio cache.
        scope.launch {
            combine(bridge.local.map { s -> s.queue.next.take(5).map { it.uri } }.distinctUntilChanged(), library.prefetchQueue, unmetered) { next, on, wifi ->
                if (on && wifi) next else emptyList()
            }.collectLatest { next ->
                if (next.isNotEmpty() && awaitConnected()) runCatching { core.cacheTracks(next) }
            }
        }
        scope.launch {
            combine(library.kept, unmetered) { kept, wifi -> if (wifi) kept else emptySet() }.collectLatest { kept ->
                if (kept.isEmpty() || !awaitConnected()) return@collectLatest
                delay(3_000)
                for (key in kept) {
                    runCatching { core.cacheTracks(keptTracks(key)) }
                        .onFailure { Log.d("Nexfy", "keep $key: ${it.message}") }
                }
            }
        }
        // Keep the home-screen widget in step with what plays (here or elsewhere).
        scope.launch {
            bridge.state
                .map { s -> listOf(s.track?.uri, s.track?.coverUrl, s.playing, s.remote?.name) }
                .distinctUntilChanged()
                .collectLatest {
                    delay(250)
                    runCatching { NexfyWidget().updateAll(this@NexfyApp) }
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
                        // Their covers too, so a skip shows the new art at once.
                        val images = SingletonImageLoader.get(this@NexfyApp)
                        tracks.mapNotNull { it.imageUrl }.forEach { url ->
                            images.enqueue(ImageRequest.Builder(this@NexfyApp).data(url).build())
                        }
                    }
                }
        }
    }

    /** Whether the network is unmetered (Wi-Fi), where songs are downloaded ahead. */
    val unmetered: StateFlow<Boolean> by lazy {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                }

                override fun onLost(network: Network) {
                    trySend(false)
                }
            }
            connectivity.registerDefaultNetworkCallback(callback)
            awaitClose { connectivity.unregisterNetworkCallback(callback) }
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)
    }

    /** Wait for the Spotify session (restored at start); false if it never comes. */
    private suspend fun awaitConnected(): Boolean {
        repeat(60) {
            if (core.isConnected()) return true
            delay(2_000)
        }
        return false
    }

    /** The songs of a kept playlist (`pl:<id>`) or album (`al:<id>`). */
    suspend fun keptTracks(key: String): List<String> {
        val id = key.substringAfter(':')
        return when {
            key.startsWith("pl:") -> core.playlist(id, false).tracks
            key.startsWith("al:") -> core.album(id, false).tracks
            else -> emptyList()
        }.map { it.uri }
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
