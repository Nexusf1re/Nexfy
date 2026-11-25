package dev.nexfy.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.nexfy.app.Connectivity
import dev.nexfy.app.nexfyDataDir
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * A check of playing with no network, for development: started with
 * `-Dnexfy.offlineCheck=pl:<id>` (a kept playlist, or `al:<id>`), it waits
 * for the computer to lose its network, plays that list the way its page's
 * play button does, and writes what the player does every two seconds to
 * `offline-check.txt` in the data directory, until the network is back.
 *
 * The network going is the one step it cannot take itself, and whoever
 * takes it has no network to watch the window with; the file is read after.
 */
private val listKey: String? = System.getProperty("nexfy.offlineCheck")

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
internal fun OfflineCheckRunner(actions: AppActions) {
    val key = listKey ?: return
    LaunchedEffect(Unit) {
        val file = nexfyDataDir().resolve("offline-check.txt")
        file.writeText("")
        fun log(line: String) {
            val stamped = "${LocalTime.now().format(clock)}  $line"
            println("offline-check: $stamped")
            file.appendText(stamped + "\n")
        }
        val id = key.substringAfter(':')
        val route = if (key.startsWith("al:")) Route.Album(id) else Route.Playlist(id)
        val uris = runCatching { actions.services.keptTracks(key) }.getOrDefault(emptyList())
        log("list $key: ${uris.size} songs, ${actions.core.cachedCount(uris)} on this computer; waiting for the network to go")
        Connectivity.online.first { !it }
        log("network gone")
        delay(2_000)
        actions.playCollection(route)
        log("asked to play $key")
        val bridge = actions.services.bridge
        while (!Connectivity.online.value) {
            val s = bridge.state.value
            log(
                "playing=${s.playing} loading=${s.loading} offline=${actions.core.playingOffline()} " +
                    "at=${s.currentPositionMs() / 1000}s song=${s.track?.title} error=${s.error}",
            )
            delay(2_000)
        }
        log("network back")
        if (bridge.state.value.playing) actions.playPause()
        log("paused; done")
    }
}
