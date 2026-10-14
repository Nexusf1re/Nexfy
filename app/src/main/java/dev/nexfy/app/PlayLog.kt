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

/** One song played on this phone: when it started and how long it really played. */
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
    }
}
