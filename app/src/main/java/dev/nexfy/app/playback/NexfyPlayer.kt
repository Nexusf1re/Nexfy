package dev.nexfy.app.playback

import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.nexfy_core.Device
import uniffi.nexfy_core.QueueEntry
import uniffi.nexfy_core.RemoteCommand
import uniffi.nexfy_core.RepeatMode
import uniffi.nexfy_core.NexfyCore

/**
 * Media3's view of the Spotify Connect playback in the Rust core: the media
 * session, notification, lock screen and headset buttons drive playback
 * through this player, and it mirrors Spotify's queue as [CoreBridge]
 * reports it (previous songs, the current one, then what comes next).
 *
 * While another device plays, it mirrors that device instead, as remote
 * playback: the controls and the volume keys then act on that device.
 */
@OptIn(UnstableApi::class)
class NexfyPlayer(
    private val core: NexfyCore,
    private val bridge: CoreBridge,
    private val commands: Commands,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * What the user asked for. The core confirms with Playing/Paused events;
     * until then this keeps the notification from flickering back.
     */
    private var playWhenReady = false

    /** Whether the last state given to Media3 was another device's. */
    private var remoteShown = false

    init {
        scope.launch {
            var wasPlaying = false
            bridge.local.collect { snapshot ->
                if (snapshot.playing) playWhenReady = true
                if (snapshot.stopped) playWhenReady = false
                // A pause the core confirmed (from the app, the widget, another
                // device): the notification must stop showing "playing".
                if (wasPlaying && !snapshot.playing && !snapshot.loading) playWhenReady = false
                wasPlaying = snapshot.playing
                invalidateState()
            }
        }
        // The other device, as the app polls it.
        scope.launch {
            bridge.state.collect { if (it.remote != null || remoteShown) invalidateState() }
        }
    }

    /** The device playing elsewhere, if any: commands go to it. */
    private val remote get() = bridge.state.value.remote

    /** The queue as Media3 items: previous, current, next. */
    private fun entries(): Pair<List<QueueEntry>, Int> {
        val q = bridge.local.value.queue
        val current = q.current ?: return emptyList<QueueEntry>() to 0
        return (q.previous + current + q.next) to q.previous.size
    }

    override fun getState(): State {
        val shown = bridge.state.value
        shown.remote?.let { device ->
            remoteShown = true
            return remoteState(shown, device)
        }
        remoteShown = false

        val s = bridge.local.value
        val (entries, current) = entries()

        val playlist = entries.mapIndexed { index, entry ->
            val item = MediaItem.Builder().setMediaId(entry.uri)
            val data = MediaItemData.Builder("$index:${entry.uri}")
            // librespot may relink a track to another URI, so match the
            // current item by position, not by URI.
            val track = s.track.takeIf { index == current }
            if (track != null) {
                val metadata = MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artists)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.coverUrl?.let(Uri::parse))
                    .setDurationMs(track.durationMs.toLong())
                    .build()
                item.setMediaMetadata(metadata)
                data.setMediaMetadata(metadata)
                    .setDurationUs(track.durationMs.toLong() * 1000)
                    .setIsSeekable(true)
            }
            data.setMediaItem(item.build()).build()
        }

        val playbackState = when {
            playlist.isEmpty() -> Player.STATE_IDLE
            s.stopped -> Player.STATE_ENDED
            s.loading -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }

        val position = if (s.playing) {
            PositionSupplier.getExtrapolating(s.currentPositionMs(), 1f)
        } else {
            PositionSupplier { s.positionMs }
        }

        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(current)
            .setContentPositionMs(position)
            .setShuffleModeEnabled(s.queue.shuffle)
            .setRepeatMode(playerRepeat(s.queue.repeat))
            .build()
    }

    /**
     * Another device's song. Its queue is not known here, so blank songs
     * stand either side of it: they make Media3 offer next and previous,
     * which then go to that device.
     */
    private fun remoteState(s: PlaybackSnapshot, device: Device): State {
        val track = s.track
        val playlist = if (track == null) emptyList() else {
            val where = "Tocando em ${device.name}"
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                // The notification and lock screen show the artist line, not the subtitle.
                .setArtist("${track.artists} · $where")
                .setSubtitle(where)
                .setAlbumTitle(track.album)
                .setArtworkUri(track.coverUrl?.let(Uri::parse))
                .setDurationMs(track.durationMs.toLong())
                .build()
            val current = MediaItemData.Builder("remote:${track.uri}")
                .setMediaItem(MediaItem.Builder().setMediaId(track.uri).setMediaMetadata(metadata).build())
                .setMediaMetadata(metadata)
                .setDurationUs(track.durationMs.toLong() * 1000)
                .setIsSeekable(true)
                .build()
            listOf(MediaItemData.Builder("remote:previous").build(), current, MediaItemData.Builder("remote:next").build())
        }

        val position = if (s.playing) {
            PositionSupplier.getExtrapolating(s.currentPositionMs(), 1f)
        } else {
            PositionSupplier { s.positionMs }
        }
        val volume = remoteVolume()

        return State.Builder()
            .setAvailableCommands(if (device.volume != null) REMOTE_VOLUME_COMMANDS else COMMANDS)
            .setPlayWhenReady(s.playing, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(if (playlist.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (playlist.isEmpty()) 0 else REMOTE_CURRENT)
            .setContentPositionMs(position)
            .setShuffleModeEnabled(s.queue.shuffle)
            .setRepeatMode(playerRepeat(s.queue.repeat))
            .setDeviceInfo(REMOTE_DEVICE)
            .setDeviceVolume(volume)
            .setIsDeviceMuted(device.volume != null && volume == 0)
            .build()
    }

    private fun playerRepeat(mode: RepeatMode) = when (mode) {
        RepeatMode.OFF -> REPEAT_MODE_OFF
        RepeatMode.ALL -> REPEAT_MODE_ALL
        RepeatMode.ONE -> REPEAT_MODE_ONE
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (remote != null) return onRemote {
            commands.remote(if (playWhenReady) RemoteCommand.Play else RemoteCommand.Pause) { it.copy(playing = playWhenReady) }
        }
        this.playWhenReady = playWhenReady
        val s = bridge.local.value
        val undo = if (s.queue.current != null && s.playing != playWhenReady) commands.showPlaying(playWhenReady) else ({})
        return runCore(undo) {
            when {
                s.queue.current == null -> Unit
                playWhenReady -> core.resume()
                else -> core.pause()
            }
        }
    }

    /** From other controllers (Android Auto, assistants): play these tracks. */
    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        if (remote != null) return onRemote {
            commands.remote(RemoteCommand.PlayTracks(mediaItems.map { it.mediaId }, startIndex.coerceAtLeast(0).toUInt()))
        }
        playWhenReady = true
        return runCore {
            core.playTracks(mediaItems.map { it.mediaId }, startIndex.coerceAtLeast(0).toUInt(), false)
        }
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = positionMs.coerceAtLeast(0).toUInt()
        if (remote != null) return onRemote {
            when (mediaItemIndex) {
                REMOTE_CURRENT + 1 -> commands.next()
                REMOTE_CURRENT - 1 -> commands.previous()
                else -> commands.remote(RemoteCommand.Seek(target)) { it.copy(progressMs = target) }
            }
        }
        val (entries, current) = entries()
        return runCore {
            when {
                mediaItemIndex == C.INDEX_UNSET || mediaItemIndex == current -> core.seek(target)
                mediaItemIndex == current + 1 -> {
                    bridge.preview(forward = true)
                    core.next()
                }
                mediaItemIndex == current - 1 -> {
                    bridge.preview(forward = false)
                    core.previous()
                }
                else -> entries.getOrNull(mediaItemIndex)?.let { core.skipTo(it.uri) }
            }
        }
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (remote != null) return onRemote {
            commands.remote(RemoteCommand.Shuffle(shuffleModeEnabled)) { it.copy(shuffle = shuffleModeEnabled) }
        }
        return runCore { core.setShuffle(shuffleModeEnabled) }
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        val mode = when (repeatMode) {
            REPEAT_MODE_ALL -> RepeatMode.ALL
            REPEAT_MODE_ONE -> RepeatMode.ONE
            else -> RepeatMode.OFF
        }
        if (remote != null) return onRemote { commands.remote(RemoteCommand.Repeat(mode)) { it.copy(repeat = mode) } }
        return runCore { core.setRepeat(mode) }
    }

    /** The volume keys while another device plays: its volume, 0 to 100. */
    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> = setRemoteVolume(deviceVolume)

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> = setRemoteVolume(remoteVolume() + VOLUME_STEP)

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> = setRemoteVolume(remoteVolume() - VOLUME_STEP)

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        if (!muted) return setRemoteVolume(unmutedVolume.takeIf { it > 0 } ?: VOLUME_STEP)
        unmutedVolume = remoteVolume()
        return setRemoteVolume(0)
    }

    /** The remote volume asked for last and when: polls lag behind it for a moment. */
    private var askedVolume: Pair<Int, Long>? = null
    private var unmutedVolume = 0
    private var volumeJob: Job? = null

    /** The volume to show and step from: the one just asked for, until polls catch up. */
    private fun remoteVolume(): Int {
        askedVolume?.let { (volume, at) -> if (SystemClock.elapsedRealtime() - at < 3_000) return volume }
        return remote?.volume?.toInt() ?: 0
    }

    /**
     * Show the new volume at once but send it once the keys settle: each
     * press would otherwise be its own request to Spotify.
     */
    private fun setRemoteVolume(percent: Int): ListenableFuture<*> {
        if (remote == null) return Futures.immediateVoidFuture()
        val volume = percent.coerceIn(0, 100)
        askedVolume = volume to SystemClock.elapsedRealtime()
        bridge.updateRemote { it.copy(device = it.device.copy(volume = volume.toUInt())) }
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(300)
            commands.remote(RemoteCommand.Volume(volume.toUInt()))
        }
        return Futures.immediateVoidFuture()
    }

    /** Another device's commands go through [Commands], which shows their effect at once. */
    private inline fun onRemote(action: () -> Unit): ListenableFuture<*> {
        action()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        if (remote != null) return onRemote { commands.remote(RemoteCommand.Pause) { it.copy(playing = false) } }
        playWhenReady = false
        return runCore { core.pause() }
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    /**
     * Run a core call off the main thread. The returned future completes
     * when it is done, so Media3 reads the state the core reported meanwhile.
     */
    private fun runCore(undo: () -> Unit = {}, action: suspend () -> Unit): ListenableFuture<Unit> {
        val done = SettableFuture.create<Unit>()
        scope.launch(Dispatchers.IO) {
            try {
                action()
            } catch (e: Exception) {
                undo()
                bridge.reportError(e.message ?: e.toString())
            } finally {
                done.set(Unit)
            }
        }
        return done
    }

    private companion object {
        /** Where another device's song sits, between the blank ones. */
        const val REMOTE_CURRENT = 1
        const val VOLUME_STEP = 5

        val REMOTE_DEVICE: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(100).build()

        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_PREPARE,
                COMMAND_STOP,
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_SEEK_TO_DEFAULT_POSITION,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_MEDIA_ITEM,
                COMMAND_SET_SHUFFLE_MODE,
                COMMAND_SET_REPEAT_MODE,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_GET_METADATA,
                COMMAND_SET_MEDIA_ITEM,
                COMMAND_CHANGE_MEDIA_ITEMS,
                COMMAND_RELEASE,
            )
            .build()

        /** Another device that reports its volume: the volume keys change it. */
        val REMOTE_VOLUME_COMMANDS: Player.Commands = COMMANDS.buildUpon()
            .addAll(
                COMMAND_GET_DEVICE_VOLUME,
                COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            )
            .build()
    }
}
