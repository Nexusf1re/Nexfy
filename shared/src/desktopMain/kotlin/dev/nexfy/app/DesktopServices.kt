package dev.nexfy.app

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import dev.nexfy.app.playback.Commands
import dev.nexfy.app.playback.CoreBridge
import dev.nexfy.app.playback.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uniffi.nexfy_core.NexfyCore

/**
 * The app on a computer: the same services the screens read on the phone,
 * over a core opened in the per-user data directory.
 *
 * Two of them have no counterpart here. There is no self-updater ([updates]
 * is null, and the version rows do not appear); and the listening log is
 * not kept yet, so [listens] is empty and the stats page has nothing to
 * show until it is.
 */
class DesktopServices(
    override val core: NexfyCore,
    private val scope: CoroutineScope,
) : NexfyServices {
    private val context: PlatformContext = PlatformContext.INSTANCE

    override val bridge: CoreBridge = CoreBridge(core)

    override val library: LibraryPrefs = LibraryPrefs(openPrefs(context, "library"))

    override val commands: Commands by lazy { Commands(this, scope) }
    override val edits: LibraryEdits by lazy { LibraryEdits(this, scope) }
    override val sleep: SleepTimer by lazy { SleepTimer(this, scope) }

    override val listens: StateFlow<List<Listen>> = MutableStateFlow(emptyList())

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
     * either side of the current one, and keep the session awake. The phone
     * does the same from its Application; it also downloads songs ahead,
     * which this does not do yet.
     */
    fun start() {
        scope.launch {
            // Nothing tells a desktop app the network went away the way Android
            // does; the core's own failures are what shows it here.
            core.setOnline(true)
            var seen = 0uL
            while (true) {
                refreshRemote()
                // Woken by the cluster Spotify pushes, not by a timer: nothing runs
                // between changes. The timeout covers a session with no cluster yet.
                seen = core.awaitRemoteChange(seen, 10_000u)
            }
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

    override suspend fun keptTracks(key: String): List<String> {
        val id = key.substringAfter(':')
        return when {
            key.startsWith("pl:") -> core.playlist(id, false).tracks
            key.startsWith("al:") -> core.album(id, false).tracks
            else -> emptyList()
        }.map { it.uri }
    }
}
