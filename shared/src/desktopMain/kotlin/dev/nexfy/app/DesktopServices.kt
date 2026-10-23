package dev.nexfy.app

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import dev.nexfy.app.playback.Commands
import dev.nexfy.app.playback.CoreBridge
import dev.nexfy.app.playback.OutputGain
import dev.nexfy.app.playback.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uniffi.nexfy_core.NexfyCore

/** How often the library is read ahead, how many lists each time, and the largest. */
private const val WARM_EVERY_MS = 86_400_000L
private const val WARM_PER_DAY = 12
private const val WARM_MAX_TRACKS = 200

/**
 * The app on a computer: the same services the screens read on the phone,
 * over a core opened in the per-user data directory.
 *
 * One thing has no counterpart here: there is no self-updater, so
 * [updates] is null and the version rows do not appear.
 */
class DesktopServices(
    override val core: NexfyCore,
    private val scope: CoroutineScope,
) : NexfyServices {
    private val context: PlatformContext = PlatformContext.INSTANCE

    override val bridge: CoreBridge = CoreBridge(core)

    override val library: LibraryPrefs = LibraryPrefs(openPrefs(context, "library"))

    override val output = OutputGain(core, library.volumePercent / 100.0)

    override val commands: Commands by lazy { Commands(this, scope) }
    override val edits: LibraryEdits by lazy { LibraryEdits(this, scope) }
    override val sleep: SleepTimer by lazy { SleepTimer(this, scope) }

    /** Songs played here, for the listening stats. */
    private val playLog = PlayLog(context, scope) { core }

    override val listens: StateFlow<List<Listen>> = playLog.listens

    override val updates: UpdateOffers? = null

    /** A window is up for as long as the app runs, so the account is always followed. */
    override val visible = MutableStateFlow(true)

    /** A computer is on a wired or home network: songs are read ahead freely. */
    override val unmetered: StateFlow<Boolean> = MutableStateFlow(true)

    /** No system notification here: the message goes over the dock, where the app's own do. */
    override fun toast(message: String) = edits.tell(message)

    /** Nothing freezes a window on a computer. */
    override suspend fun <T> keepingAwake(block: suspend () -> T): T = block()

    /**
     * Follow what the account plays on its other devices, fetch the songs
     * either side of the current one, and read songs and lists ahead so
     * they open and play without the network. The phone's Application does
     * the same; the conditions it reads them under (Wi-Fi, metering) have
     * no counterpart here, so a computer reads them whenever it can.
     */
    fun start() {
        // The volume the user left is the one the player opens at, and
        // every change to it is kept.
        scope.launch {
            output.setVolume(output.volume.value)
            output.volume.collect { library.volumePercent = (it * 100).toInt() }
        }
        // Each song played long enough joins the log.
        bridge.listening.onListen = playLog::record
        scope.launch {
            // Nothing tells a desktop app the network went away the way Android
            // does; the core's own failures are what shows it here.
            core.setOnline(true)
            // Nothing here is a metered connection the way mobile data is.
            core.setMetered(false)
            var seen = 0uL
            while (true) {
                refreshRemote()
                // Woken by the cluster Spotify pushes, not by a timer: nothing runs
                // between changes. The timeout covers a session with no cluster yet.
                seen = core.awaitRemoteChange(seen, 10_000u)
            }
        }
        // Plays on the account's other devices join the log each time the app
        // opens: Spotify keeps only the last 50. A moment first, for the
        // sign-in to come back.
        scope.launch {
            delay(3_000)
            playLog.importElsewhere()
        }
        // The songs either side of the current one, so a skip shows the new one at once.
        scope.launch {
            bridge.local
                .map { it.queue.next.firstOrNull()?.uri to it.queue.previous.lastOrNull()?.uri }
                .distinctUntilChanged()
                .collect { (next, previous) ->
                    val uris = listOfNotNull(next, previous)
                    if (uris.isEmpty()) return@collect
                    runCatching { core.tracksInfo(uris) }.onSuccess { tracks ->
                        bridge.setNeighbours(tracks.find { it.uri == next }, tracks.find { it.uri == previous })
                        prefetchCovers(tracks.mapNotNull { it.imageUrl })
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
                        prefetchCovers(listOfNotNull(previous, next).mapNotNull { it.imageUrl })
                        return@collectLatest
                    }
                }
        }
        // The next songs of the queue, so they play without the network.
        scope.launch {
            combine(
                bridge.local.map { s -> s.queue.next.take(3).map { it.uri } }.distinctUntilChanged(),
                library.prefetchQueue,
            ) { next, on -> if (on) next else emptyList() }.collectLatest { next ->
                if (next.isNotEmpty() && awaitConnected()) runCatching { core.cacheTracks(next) }
            }
        }
        // Whole kept collections, only while nothing plays here: downloading
        // them alongside a song starved its stream on the phone, and the
        // same single core feeds both.
        scope.launch {
            val playing = bridge.local.map { it.playing }.distinctUntilChanged()
            combine(library.kept, playing) { kept, busy -> if (busy) emptySet() else kept }.collectLatest { kept ->
                if (!awaitConnected()) return@collectLatest
                // Every kept song first, so the audio cache never makes room by
                // dropping one (and ones no longer kept can go again).
                val songs = kept.associateWith { key -> runCatching { keptTracks(key) }.getOrNull() }
                if (songs.values.none { it == null }) {
                    core.setKeptSongs(songs.values.flatMap { it.orEmpty() }.distinct())
                }
                if (kept.isEmpty()) return@collectLatest
                delay(10_000)
                for (tracks in songs.values) {
                    if (tracks == null) continue
                    runCatching { core.cacheTracks(tracks) }
                }
            }
        }
        // Once a day: read the library's playlists and albums that have no copy
        // here yet, so each opens at once later. A few a day, one at a time and
        // not the huge ones: every song of a list is read, and Spotify limited
        // the app when all of them went at once.
        scope.launch {
            if (System.currentTimeMillis() - library.warmedAt < WARM_EVERY_MS || !awaitConnected()) return@launch
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
        // One shuffle mode: when the player's is switched (here, on another device),
        // the pages' shuffle buttons follow it, but only while the same list plays.
        scope.launch {
            var last: Pair<String?, Boolean>? = null
            bridge.state.map { it.queue.contextUri to it.queue.shuffle }.distinctUntilChanged()
                .collect { now ->
                    val before = last
                    last = now
                    if (before != null && before.first == now.first && before.second != now.second) {
                        library.setShuffle(now.second)
                    }
                }
        }
    }

    private fun prefetchCovers(urls: List<String>) {
        val images = SingletonImageLoader.get(context)
        urls.forEach { images.enqueue(ImageRequest.Builder(context).data(it).build()) }
    }

    override suspend fun refreshRemote() {
        if (!core.isConnected()) return
        runCatching { core.remoteState() }.onSuccess { bridge.setRemote(it) }
    }

    override fun refreshRemoteSoon() {
        scope.launch {
            delay(700)
            refreshRemote()
            delay(1_500)
            refreshRemote()
        }
    }

    /** Wait for the Spotify session (restored at start); false if it never comes. */
    private suspend fun awaitConnected(): Boolean {
        repeat(60) {
            if (core.isConnected()) return true
            delay(2_000)
        }
        return false
    }

    override suspend fun keptTracks(key: String): List<String> {
        val id = key.substringAfter(':')
        return when {
            key.startsWith("pl:") -> core.playlist(id, false).tracks
            key.startsWith("al:") -> core.album(id, false).tracks
            else -> emptyList()
        }.map { it.uri }
    }
}
