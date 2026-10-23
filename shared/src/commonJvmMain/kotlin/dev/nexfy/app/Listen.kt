package dev.nexfy.app

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
    /** The playlist or album it played from, when known; entries from before this is null. */
    val contextUri: String? = null,
    /** How long the song is, for telling a song heard through from one skipped; 0 when unknown. */
    val durationMs: Long = 0,
)
