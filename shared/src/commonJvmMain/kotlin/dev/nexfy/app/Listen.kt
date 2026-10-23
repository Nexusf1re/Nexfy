package dev.nexfy.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One song played: when it started and how long it played (the whole song
 * for plays elsewhere). The names on the wire are the short ones the log
 * file has always used, so a log written by an older Nexfy still reads.
 */
@Serializable
data class Listen(
    val uri: String,
    val title: String = "",
    val artists: String = "",
    val artistIds: List<String> = emptyList(),
    val album: String = "",
    @SerialName("image") val imageUrl: String? = null,
    /** Wall-clock start, ms; also the entry's key. */
    val at: Long,
    val ms: Long,
    /** The playlist or album it played from, when known; entries from before this is null. */
    @SerialName("ctx") val contextUri: String? = null,
    /** How long the song is, for telling a song heard through from one skipped; 0 when unknown. */
    @SerialName("dur") val durationMs: Long = 0,
)
