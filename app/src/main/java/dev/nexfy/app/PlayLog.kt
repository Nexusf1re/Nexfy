package dev.nexfy.app

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import uniffi.nexfy_core.NexfyCore

/** One song played: when it started and how long it played (the whole song for plays elsewhere). */
data class Listen(
    val uri: String,
    val title: String,
    val artists: String,
    val artistIds: List<String>,
    val album: String,
    val imageUrl: String?,
    /** Wall-clock start, ms; also the entry's key. */
    val at: Long,
    val ms: Long,
)

/**
 * Every song played here for 30 s or more, for the listening stats. The
 * Web API only remembers the last 50 plays, so Nexfy keeps its own log in
 * app-private storage: one JSON object per line, appended as a song plays
 * on (a later line for the same start replaces the earlier one).
 */
class PlayLog(context: Context, private val scope: CoroutineScope, private val core: () -> NexfyCore) {
    private val file = File(context.filesDir, "listens.jsonl")

    // Loads and writes run one after another, in order.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)

    private val entries = LinkedHashMap<Long, Listen>()

    private val _listens = MutableStateFlow<List<Listen>>(emptyList())

    /** Oldest first. */
    val listens: StateFlow<List<Listen>> = _listens

    init {
        scope.launch(io) { load() }
    }

    /** A song played long enough to count, or played on since it was last recorded. */
    fun record(listen: Listen) {
        scope.launch(io) {
            val known = entries[listen.at]?.artistIds?.takeIf { it.isNotEmpty() }
                ?: entries.values.lastOrNull { it.uri == listen.uri && it.artistIds.isNotEmpty() }?.artistIds
            var entry = if (listen.artistIds.isEmpty() && known != null) listen.copy(artistIds = known) else listen
            if (entry.artistIds.isEmpty()) {
                // The player reports artist names only; the ids open their pages from the stats.
                runCatching { core().tracksInfo(listOf(entry.uri)).firstOrNull() }.getOrNull()?.let { track ->
                    entry = entry.copy(artistIds = track.artistIds)
                }
            }
            entries[entry.at] = entry
            publish()
            runCatching { file.appendText(encode(entry).toString() + "\n") }
                .onFailure { Log.d("Nexfy", "play log: ${it.message}") }
        }
    }

    /** Spotify history read up to here (Unix ms of the newest play taken). */
    private val cursor = File(file.parentFile, "listens.cursor")

    /**
     * Plays on the account's other devices (a PC, a speaker), from Spotify's
     * history. It keeps only the last 50, so this runs each time the app
     * opens. Spotify gives no listened time, so these count as the whole
     * song; one already logged here (same song, same moment) is skipped.
     */
    fun importElsewhere() {
        scope.launch(io) {
            val after = runCatching { cursor.readText().trim().toLong() }.getOrDefault(0L)
            val plays = runCatching { core().playsSince(after.toULong()) }
                .onFailure { Log.d("Nexfy", "play history: ${it.message}") }
                .getOrNull() ?: return@launch
            var newest = after
            // Only recent phone plays can be the same listen.
            val near = entries.values.filter { it.at > after - DAY_MS }
            var added = 0
            for (play in plays) {
                val end = runCatching { java.time.Instant.parse(play.playedAt).toEpochMilli() }.getOrNull() ?: continue
                newest = maxOf(newest, end)
                val t = play.track
                val ms = t.durationMs.toLong()
                val start = end - ms
                if (near.any { it.uri == t.uri && kotlin.math.abs(it.at - start) < ms + SAME_PLAY_SLACK_MS }) continue
                var key = start
                while (entries.containsKey(key)) key++
                val listen = Listen(t.uri, t.title, t.artists, t.artistIds, t.album, t.imageUrl, key, ms)
                entries[key] = listen
                runCatching { file.appendText(encode(listen).toString() + "\n") }
                added++
            }
            if (added > 0) {
                trim()
                publish()
            }
            if (newest > after) runCatching { cursor.writeText(newest.toString()) }
        }
    }

    private fun load() {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        for (line in lines) {
            val listen = runCatching { decode(JSONObject(line)) }.getOrNull() ?: continue
            entries[listen.at] = listen
        }
        trim()
        publish()
        // Rewritten once replaced lines pile up, so the file stays near its real size.
        if (lines.size > entries.size * 2 + 200) {
            runCatching {
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(entries.values.joinToString("") { encode(it).toString() + "\n" })
                temp.renameTo(file)
            }
        }
    }

    private fun trim() {
        val extra = entries.size - MAX_ENTRIES
        if (extra > 0) entries.keys.take(extra).forEach(entries::remove)
    }

    private fun publish() {
        _listens.value = entries.values.sortedBy { it.at }
    }

    private fun encode(l: Listen) = JSONObject()
        .put("uri", l.uri)
        .put("title", l.title)
        .put("artists", l.artists)
        .put("artistIds", JSONArray(l.artistIds))
        .put("album", l.album)
        .put("image", l.imageUrl ?: JSONObject.NULL)
        .put("at", l.at)
        .put("ms", l.ms)

    private fun decode(o: JSONObject): Listen {
        val ids = o.optJSONArray("artistIds")
        return Listen(
            uri = o.getString("uri"),
            title = o.optString("title"),
            artists = o.optString("artists"),
            artistIds = (0 until (ids?.length() ?: 0)).map { ids!!.getString(it) },
            album = o.optString("album"),
            imageUrl = if (o.isNull("image")) null else o.optString("image"),
            at = o.getLong("at"),
            ms = o.getLong("ms"),
        )
    }

    private companion object {
        /** Years of listening at a few hours a day. */
        const val MAX_ENTRIES = 60_000

        const val DAY_MS = 86_400_000L

        /** How far apart a phone play and a history play of one song can start and be the same listen. */
        const val SAME_PLAY_SLACK_MS = 120_000L
    }
}
