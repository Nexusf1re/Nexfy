package dev.nexfy.app

import androidx.compose.runtime.staticCompositionLocalOf
import dev.nexfy.app.playback.Commands
import dev.nexfy.app.playback.CoreBridge
import dev.nexfy.app.playback.SleepTimer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uniffi.nexfy_core.NexfyCore

/**
 * Everything the screens reach outside themselves: the core, playback, the
 * library and the settings. The phone's `NexfyApp` is one of these (it is
 * also an `android.app.Application`, which the screens must not know) and
 * the desktop has its own, so the same screens run on both.
 */
interface NexfyServices {
    val core: NexfyCore

    /** What plays, here or on another device of the account. */
    val bridge: CoreBridge

    /** Playback commands, each shown at once and taken back if it fails. */
    val commands: Commands

    /** The settings and the library's own order, kept on this machine. */
    val library: LibraryPrefs

    /** Changes to the Spotify library: likes, saved albums, playlist contents. */
    val edits: LibraryEdits

    /** Pauses playback after a while or at the end of the song. */
    val sleep: SleepTimer

    /** Songs played here, for the listening stats; oldest first. */
    val listens: StateFlow<List<Listen>>

    /** New versions, where the app installs them itself; null on the desktop. */
    val updates: UpdateOffers?

    /** Whether a screen of the app is showing; remote playback is only polled then. */
    val visible: MutableStateFlow<Boolean>

    /** Whether the network is unmetered (Wi-Fi), where songs are downloaded ahead. */
    val unmetered: StateFlow<Boolean>

    /** Read what the account is playing now and mirror it. */
    suspend fun refreshRemote()

    /** Poll again shortly: after a command, Spotify takes a moment to report it. */
    fun refreshRemoteSoon()

    /** The songs of a kept playlist (`pl:<id>`) or album (`al:<id>`). */
    suspend fun keptTracks(key: String): List<String>

    /** A short message the system shows over the app, outside its own windows. */
    fun toast(message: String)
}

/**
 * The services of the running app. Set once, around the whole UI; a screen
 * reads it instead of being handed the app through every call.
 */
val LocalServices = staticCompositionLocalOf<NexfyServices> {
    error("No NexfyServices: the UI must be wrapped in a services provider")
}
