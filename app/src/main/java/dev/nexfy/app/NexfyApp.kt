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
import dev.nexfy.app.widget.NexfyWidgets
import dev.nexfy.core.NativeInit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filter
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
/** Whether the phone reaches the internet now: kept by [NexfyApp], read by the screens. */
object Connectivity {
    val online = MutableStateFlow(true)
}

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
    val updates: Updates by lazy { Updates(this, scope) }

    /** Songs played here, for the listening stats. */
    val playLog: PlayLog by lazy { PlayLog(this, scope) { core } }

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
        bridge.listening.onListen = playLog::record
        // The network back, or a different one: the session comes back at once
        // and what the account plays is read again, not at the next tap.
        scope.launch {
            var last = network.value
            Connectivity.online.value = last != null
            network.collect { now ->
                Connectivity.online.value = now != null
                if (now != null && now != last) {
                    runCatching { core.networkBack() }.onFailure { Log.d("Nexfy", "network back: ${it.message}") }
                    refreshRemote()
                }
                last = now
            }
        }
        // Plays on the account's other devices join the log each time the app opens:
        // Spotify keeps only the last 50. A moment first, for the sign-in to come back.
        scope.launch {
            visible.collectLatest { shown ->
                if (!shown) return@collectLatest
                delay(3_000)
                playLog.importElsewhere()
                // And whether a newer Nexfy is out, once a day.
                runCatching { updates.checkDaily() }.onFailure { Log.d("Nexfy", "update check: ${it.message}") }
            }
        }
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
        // Another device playing gets the notification too. Android lets the
        // service start only while a screen is open; once up, it follows that
        // device by itself in the background.
        scope.launch {
            combine(bridge.state.map { it.remote != null && it.playing }, visible) { there, shown -> there && shown }
                .distinctUntilChanged()
                .collect { start ->
                    if (start && !PlaybackService.running) {
                        runCatching {
                            ContextCompat.startForegroundService(this@NexfyApp, Intent(this@NexfyApp, PlaybackService::class.java))
                        }
                    }
                }
        }
        // While a screen is open, follow what the account plays on other
        // devices, as their changes arrive.
        scope.launch {
            visible.collectLatest { shown ->
                if (!shown) return@collectLatest
                var seen = 0uL
                while (true) {
                    refreshRemote()
                    // Woken by the cluster Spotify pushes, not by a timer: nothing runs
                    // between changes. The timeout covers a session with no cluster yet.
                    seen = core.awaitRemoteChange(seen, 10_000u)
                }
            }
        }
        // Songs on the phone: on Wi-Fi, download the next songs of the queue
        // and every kept playlist and album into the audio cache.
        scope.launch {
            combine(bridge.local.map { s -> s.queue.next.take(3).map { it.uri } }.distinctUntilChanged(), library.prefetchQueue, unmetered) { next, on, wifi ->
                if (on && wifi) next else emptyList()
            }.collectLatest { next ->
                if (next.isNotEmpty() && awaitConnected()) runCatching { core.cacheTracks(next) }
            }
        }
        scope.launch {
            // Whole collections only while nothing plays here: downloading them
            // alongside a song starved its stream (the player gave up mid song).
            val playing = bridge.local.map { it.playing }.distinctUntilChanged()
            combine(library.kept, unmetered, playing) { kept, wifi, busy -> if (wifi && !busy) kept else emptySet() }.collectLatest { kept ->
                if (kept.isEmpty() || !awaitConnected()) return@collectLatest
                delay(10_000)
                for (key in kept) {
                    runCatching { core.cacheTracks(keptTracks(key)) }
                        .onFailure { Log.d("Nexfy", "keep $key: ${it.message}") }
                }
            }
        }
        // One shuffle mode: when the player's changes (here, on another device, from
        // the notification), the pages' shuffle buttons follow it.
        scope.launch {
            bridge.state.filter { it.track != null }.map { it.queue.shuffle }.distinctUntilChanged()
                .collect { library.setShuffle(it) }
        }
        // Keep the home-screen widgets in step with what plays (here or elsewhere).
        NexfyWidgets.follow(this, scope)
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
        // The same for another device playing: its songs either side, from the cluster.
        scope.launch {
            bridge.state
                .map { s -> s.track?.uri.takeIf { s.remote != null } }
                .distinctUntilChanged()
                .collectLatest { uri ->
                    bridge.setRemoteNeighbours(null, null)
                    if (uri == null) return@collectLatest
                    // The cluster can still be on the song before (a skip shown
                    // ahead): read again until it names this one in the middle.
                    repeat(4) {
                        delay(1500)
                        val (previous, next) = runCatching { core.remoteNeighbours() }.getOrNull() ?: return@repeat
                        if (previous?.uri == uri || next?.uri == uri) return@repeat
                        bridge.setRemoteNeighbours(previous, next)
                        val images = SingletonImageLoader.get(this@NexfyApp)
                        listOfNotNull(previous, next).mapNotNull { it.imageUrl }.forEach { url ->
                            images.enqueue(ImageRequest.Builder(this@NexfyApp).data(url).build())
                        }
                        return@collectLatest
                    }
                }
        }
    }

    /**
     * The network the phone uses while it reaches the internet, null without
     * one: a Wi-Fi with no internet behind it counts as none. Another value
     * is another network (Wi-Fi to mobile data).
     */
    private val network: StateFlow<Network?> by lazy {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        fun usable(network: Network?, caps: NetworkCapabilities?) = network?.takeIf {
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val now = connectivity.activeNetwork.let { usable(it, connectivity.getNetworkCapabilities(it)) }
        callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                var current: Network? = null

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    current = network
                    trySend(usable(network, caps))
                }

                // Only the one in use: the old Wi-Fi can be reported lost
                // after mobile data has already taken over.
                override fun onLost(network: Network) {
                    if (network != current) return
                    current = null
                    trySend(null)
                }
            }
            connectivity.registerDefaultNetworkCallback(callback)
            awaitClose { connectivity.unregisterNetworkCallback(callback) }
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, now)
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

    /**
     * Read what the account is playing now and mirror it. Not without a
     * network: each try would start a reconnect bound to fail, every few
     * seconds; the network coming back reads it at once.
     */
    suspend fun refreshRemote() {
        if (!core.isConnected() || !Connectivity.online.value) return
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
