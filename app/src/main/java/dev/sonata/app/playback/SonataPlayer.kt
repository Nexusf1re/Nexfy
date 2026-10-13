package dev.sonata.app.playback

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uniffi.sonata_core.QueueEntry
import uniffi.sonata_core.RepeatMode
import uniffi.sonata_core.SonataCore

/**
 * Media3's view of the Spotify Connect playback in the Rust core: the media
 * session, notification, lock screen and headset buttons drive playback
 * through this player, and it mirrors Spotify's queue as [CoreBridge]
 * reports it (previous songs, the current one, then what comes next).
 */
@OptIn(UnstableApi::class)
class SonataPlayer(
    private val core: SonataCore,
    private val bridge: CoreBridge,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * What the user asked for. The core confirms with Playing/Paused events;
     * until then this keeps the notification from flickering back.
     */
    private var playWhenReady = false

    init {
        scope.launch {
            bridge.state.collect { snapshot ->
                if (snapshot.playing) playWhenReady = true
                if (snapshot.stopped) playWhenReady = false
                invalidateState()
            }
        }
    }

    /** The queue as Media3 items: previous, current, next. */
    private fun entries(): Pair<List<QueueEntry>, Int> {
        val q = bridge.state.value.queue
        val current = q.current ?: return emptyList<QueueEntry>() to 0
        return (q.previous + current + q.next) to q.previous.size
    }

    override fun getState(): State {
        val s = bridge.state.value
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
            .setRepeatMode(
                when (s.queue.repeat) {
                    RepeatMode.OFF -> REPEAT_MODE_OFF
                    RepeatMode.ALL -> REPEAT_MODE_ALL
                    RepeatMode.ONE -> REPEAT_MODE_ONE
                },
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        val s = bridge.state.value
        return runCore {
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
        playWhenReady = true
        return runCore {
            core.playTracks(mediaItems.map { it.mediaId }, startIndex.coerceAtLeast(0).toUInt(), false)
        }
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val (entries, current) = entries()
        val target = positionMs.coerceAtLeast(0).toUInt()
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

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> =
        runCore { core.setShuffle(shuffleModeEnabled) }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = runCore {
        core.setRepeat(
            when (repeatMode) {
                REPEAT_MODE_ALL -> RepeatMode.ALL
                REPEAT_MODE_ONE -> RepeatMode.ONE
                else -> RepeatMode.OFF
            },
        )
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
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
    private fun runCore(action: suspend () -> Unit): ListenableFuture<Unit> {
        val done = SettableFuture.create<Unit>()
        scope.launch(Dispatchers.IO) {
            try {
                action()
            } catch (e: Exception) {
                bridge.reportError(e.message ?: e.toString())
            } finally {
                done.set(Unit)
            }
        }
        return done
    }

    private companion object {
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
    }
}
