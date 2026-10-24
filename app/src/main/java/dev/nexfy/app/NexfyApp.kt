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
import android.widget.Toast
import dev.nexfy.app.playback.OutputGain
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
/** How often the library is read ahead on Wi-Fi, how many lists each time, and the largest. */
private const val WARM_EVERY_MS = 86_400_000L
private const val WARM_PER_DAY = 12
private const val WARM_MAX_TRACKS = 200

class NexfyApp : Application(), SingletonImageLoader.Factory, NexfyServices {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val core: NexfyCore by lazy {
        NativeInit.init(this)
        NexfyCore(filesDir.resolve("spotify").path, deviceName())
    }

    override val bridge: CoreBridge by lazy { CoreBridge(core) }

    override val library: LibraryPrefs by lazy { LibraryPrefs(openPrefs(this, "library")) }

    /** Playback commands for the screens and the widget. */
    override val commands: Commands by lazy { Commands(this, scope) }
    override val sleep: SleepTimer by lazy { SleepTimer(this, scope) }
    override val edits: LibraryEdits by lazy { LibraryEdits(this, scope) }
    private val updater: Updates by lazy { Updates(this, scope) }
    override val updates: UpdateOffers get() = updater

    /** Songs played here, for the listening stats. */
    val playLog: PlayLog by lazy { PlayLog(this, scope) { core } }

    override val listens get() = playLog.listens

    /**
     * One gain from the user's own level and the sleep timer's fade. Nothing
     * on the phone sets a level of its own — the volume keys are the system's
     * — so it stays at full and only the fade moves.
     */
    override val output: OutputGain by lazy { OutputGain(core) }

    /**
     * Android says this sort of thing in a toast. It is posted to the main
     * thread because a failure explains itself from whichever thread it
     * happened on, and a toast from another one throws.
     */
    override fun toast(message: String, long: Boolean) {
        scope.launch {
            Toast.makeText(this@NexfyApp, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * A phone stops an app it thinks is idle, and signing in waits on a
     * browser: the work is held by a foreground service while it runs.
     */
    override suspend fun <T> keepingAwake(block: suspend () -> T): T = LoginService.during(this, block)

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
            core.setOnline(last != null)
            network.collect { now ->
                Connectivity.online.value = now != null
                core.setOnline(now != null)
                // What another device plays cannot be read or driven offline: a
                // stale "playing" with dead buttons is worse than none.
                if (now == null) bridge.setRemote(null)
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
                runCatching { updater.checkDaily() }.onFailure { Log.d("Nexfy", "update check: ${it.message}") }
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
        // On Wi-Fi, once a day: read the library's playlists and albums that have
        // no copy on the phone yet, so each opens at once later, offline too.
        // A few a day, one at a time and not the huge ones: every song of a list
        // is read, and Spotify limited the app when all of them went at once.
        scope.launch {
            combine(unmetered, network) { wifi, now -> wifi && now != null }.distinctUntilChanged().collectLatest { wifi ->
                if (!wifi || System.currentTimeMillis() - library.warmedAt < WARM_EVERY_MS || !awaitConnected()) return@collectLatest
                delay(30_000)
                library.warmedAt = System.currentTimeMillis()
                val playlists = runCatching { core.myPlaylists(false) }.getOrNull().orEmpty()
                    .filter { it.trackCount.toInt() <= WARM_MAX_TRACKS && !core.playlistSaved(it.id) }
                val albums = runCatching { core.savedAlbums(false) }.getOrNull().orEmpty().filter { !core.albumSaved(it.id) }
                val reads = playlists.map { p -> suspend { core.playlist(p.id, false); Unit } } +
                    albums.map { a -> suspend { core.album(a.id, false); Unit } }
                for (read in reads.take(WARM_PER_DAY)) {
                    val failed = runCatching { read() }.exceptionOrNull()
                    // Limited by Spotify: the rest waits for tomorrow.
                    if (failed?.message?.contains("limit") == true) break
                    delay(5_000)
                }
            }
        }
        // Back on screen after a long pause: the session is renewed before the
        // first tap needs it. Frozen in the background, it had died unnoticed.
        scope.launch {
            visible.collect { shown ->
                if (!shown) return@collect
                runCatching { core.wake() }
                refreshRemote()
            }
        }
        // The Auto quality streams lower on mobile data.
        scope.launch { unmetered.collect { core.setMetered(!it) } }
        // Songs on the phone: on Wi-Fi (or on mobile data when allowed), download
        // the next songs of the queue and every kept playlist and album.
        scope.launch {
            combine(bridge.local.map { s -> s.queue.next.take(3).map { it.uri } }.distinctUntilChanged(), library.prefetchQueue, downloadable) { next, on, wifi ->
                if (on && wifi) next else emptyList()
            }.collectLatest { next ->
                if (next.isNotEmpty() && awaitConnected()) runCatching { core.cacheTracks(next) }
            }
        }
        scope.launch {
            // Whole collections only while nothing plays here: downloading them
            // alongside a song starved its stream (the player gave up mid song).
            val playing = bridge.local.map { it.playing }.distinctUntilChanged()
            combine(library.kept, downloadable, playing) { kept, wifi, busy -> if (wifi && !busy) kept else emptySet() }.collectLatest { kept ->
                if (!awaitConnected()) return@collectLatest
                // Every kept song first, so the audio cache never makes room by
                // dropping one (and ones no longer kept can go again).
                val songs = kept.associateWith { key ->
                    runCatching { keptTracks(key) }.onFailure { Log.d("Nexfy", "keep $key: ${it.message}") }.getOrNull()
                }
                if (songs.values.none { it == null }) core.setKeptSongs(songs.values.flatMap { it.orEmpty() }.distinct())
                if (kept.isEmpty()) return@collectLatest
                delay(10_000)
                for ((key, tracks) in songs) {
                    if (tracks == null) continue
                    runCatching { core.cacheTracks(tracks) }
                        .onFailure { Log.d("Nexfy", "keep $key: ${it.message}") }
                }
            }
        }
        // One shuffle mode: when the player's is switched (here, on another device,
        // from the notification), the pages' shuffle buttons follow it. Only a
        // switch while the same list plays: the first report after the app
        // opens, or another list starting with its own, used to undo the
        // button the user had left on.
        scope.launch {
            var last: Pair<String?, Boolean>? = null
            bridge.state.filter { it.track != null }.map { it.queue.contextUri to it.queue.shuffle }.distinctUntilChanged()
                .collect { now ->
                    val before = last
                    last = now
                    if (before != null && before.first == now.first && before.second != now.second) library.setShuffle(now.second)
                }
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

    /** Whether songs download now: on Wi-Fi, or on mobile data when the user allows. */
    private val downloadable: StateFlow<Boolean> by lazy {
        combine(unmetered, library.mobileDownloads, network) { wifi, mobile, now -> now != null && (wifi || mobile) }
            .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)
    }

    /** Whether the network is unmetered (Wi-Fi), where songs are downloaded ahead. */
    override val unmetered: StateFlow<Boolean> by lazy {
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
    override suspend fun keptTracks(key: String): List<String> {
        val id = key.substringAfter(':')
        return when {
            key.startsWith("pl:") -> core.playlist(id, false).tracks
            key.startsWith("al:") -> core.album(id, false).tracks
            else -> emptyList()
        }.map { it.uri }
    }

    /** Whether a screen of the app is showing; remote playback is only polled then. */
    override val visible = MutableStateFlow(false)

    /**
     * Read what the account is playing now and mirror it. Not without a
     * network: each try would start a reconnect bound to fail, every few
     * seconds; the network coming back reads it at once.
     */
    override suspend fun refreshRemote() {
        if (!core.isConnected() || !Connectivity.online.value) return
        runCatching { core.remoteState() }
            .onSuccess { bridge.setRemote(it) }
            .onFailure { Log.d("Nexfy", "remote state: ${it.message}") }
    }

    /** Poll again shortly: after a command, Spotify takes a moment to report it. */
    override fun refreshRemoteSoon() {
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
