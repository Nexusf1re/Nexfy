package dev.nexfy.app.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import android.graphics.Bitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.nexfy.app.MainActivity
import dev.nexfy.app.NexfyApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps playback alive in the background and publishes it to the system:
 * notification, lock screen, Bluetooth and headset buttons. Media3 promotes
 * the service to the foreground while [NexfyPlayer] is playing.
 *
 * SimpleBasePlayer does none of ExoPlayer's housekeeping, so this service
 * also handles audio focus, "becoming noisy" (headphones unplugged) and the
 * wake lock that keeps the stream going with the screen off.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: NexfyPlayer
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private lateinit var wakeLock: PowerManager.WakeLock

    private var hasFocus = false
    private var resumeOnFocusGain = false

    /** The session mirrors another device: no sound here, so no focus, locks or noisy pause. */
    private val remote get() = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && !remote) player.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        val app = application as NexfyApp
        player = NexfyPlayer(app.core, app.bridge, app.commands) { url -> coverBytes(url) }

        audioManager = getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(::onAudioFocusChange)
            .build()

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nexfy:playback")
            .apply { setReferenceCounted(false) }

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (remote) return
                if (playWhenReady) requestFocus() else if (!resumeOnFocusGain) abandonFocus()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!remote) holdLocks(isPlaying)
            }

            /** Playback moved to another device or back here. */
            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                if (remote) {
                    resumeOnFocusGain = false
                    abandonFocus()
                    holdLocks(false)
                } else {
                    if (player.playWhenReady) requestFocus()
                    holdLocks(player.isPlaying)
                }
            }
        })
        followRemote(app)

        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            // No position pushed every 3 s: Media3 republished the whole
            // playback state each time, and every republish went to the watch
            // over Bluetooth and to the car or headset profile, with nothing
            // changed. Controllers work the position out from the speed, and a
            // seek, a new song or a pause still reaches them at once.
            .setPeriodicPositionUpdateEnabled(false)
            .build()
        followButtons(app)
    }

    /**
     * Like and shuffle beside the transport controls: on the watch's two
     * upper slots, in the notification and on the lock screen. Each shows the
     * state of the song shown (here or on another device) and changes with it.
     */
    private fun buttons(app: NexfyApp): List<CommandButton> {
        val s = app.bridge.state.value
        val uri = s.track?.uri ?: return emptyList()
        val liked = app.edits.saved[uri] == true
        val shuffle = s.queue.shuffle
        return listOf(
            CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(if (liked) "Descurtir" else "Curtir")
                .setSessionCommand(LIKE)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(if (shuffle) "Desligar aleatório" else "Aleatório")
                .setSessionCommand(SHUFFLE)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    private fun showButtons(app: NexfyApp) = session.setMediaButtonPreferences(buttons(app))

    /** The buttons follow the song, its shuffle, and every like saved anywhere in the app. */
    private fun followButtons(app: NexfyApp) = lifecycleScope.launch {
        combine(
            app.bridge.state.map { it.track?.uri to it.queue.shuffle }.distinctUntilChanged(),
            app.edits.version,
        ) { shown, _ -> shown }.collectLatest { (uri, _) ->
            showButtons(app)
            // A song not looked up yet reads as not liked until Spotify answers.
            if (uri != null && uri !in app.edits.saved) {
                app.edits.load(listOf(uri))
                showButtons(app)
            }
        }
    }

    /**
     * Media3 refuses items without a playable URI unless the session says
     * how to resolve them. Ours are Spotify track URIs in `mediaId`, which
     * the core resolves itself, so they pass through unchanged.
     */
    private inner class SessionCallback : MediaSession.Callback {
        /**
         * Every controller may drive playback, not only read it. Media3 gives a
         * controller that is not part of the system the read-only commands
         * unless the app says otherwise, and the Galaxy Watch reaches the
         * phone through such a controller (the watch plugin, an ordinary app):
         * it found no play, pause or skip, told the watch so, and the watch
         * greyed its buttons and dropped every press. Spotify, on the older
         * session API, never met the cut.
         */
        private fun everything(session: MediaSession): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(LIKE).add(SHUFFLE).build(),
                )
                .build()

        /** The like and shuffle buttons, from the watch, the notification or the lock screen. */
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val app = application as NexfyApp
            val s = app.bridge.state.value
            when (customCommand.customAction) {
                LIKE.customAction -> {
                    val uri = s.track?.uri ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                    val liked = app.edits.saved[uri] == true
                    app.edits.setSaved(uri, !liked, if (liked) "Removida das curtidas" else "Adicionada às curtidas")
                }
                SHUFFLE.customAction -> app.commands.setShuffle(!s.queue.shuffle)
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            showButtons(app)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) = everything(session)

        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> = Futures.immediateFuture(everything(session))

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems)

        /** Play pressed with nothing queued (e.g. a headset button): nothing to resume yet. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    /**
     * A cover as JPEG bytes for the session, from the image cache the screens
     * already filled: 320 px is what a watch draws, and keeps every update of
     * the session small.
     */
    private suspend fun coverBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(this@PlaybackService).data(url).size(COVER_PX).allowHardware(false).build()
        val bitmap = SingletonImageLoader.get(this@PlaybackService).execute(request).image?.toBitmap() ?: return@withContext null
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    /**
     * With no screen open nothing polls the other device, so follow it from
     * here for the session to mirror it, waking only when the Connect
     * cluster Spotify pushes changes (no network, no timer). Once
     * that device has been paused a while, or stopped, end the service:
     * Android would not let it come back to the foreground from the
     * background anyway.
     */
    private fun followRemote(app: NexfyApp) = lifecycleScope.launch {
        var pausedSince = 0L
        var followed = false
        var seen = 0uL
        while (true) {
            val s = app.bridge.state.value
            val now = SystemClock.elapsedRealtime()
            if (s.remote == null || s.playing) pausedSince = 0L else if (pausedSince == 0L) pausedSince = now
            if (!app.visible.value && !app.bridge.local.value.playing) {
                val lingered = pausedSince != 0L && now - pausedSince > REMOTE_PAUSED_LINGER_MS
                when {
                    lingered || (followed && s.remote == null && player.mediaItemCount == 0) -> {
                        // A controller still bound keeps the service up: start over then.
                        pausedSince = 0L
                        followed = false
                        stopSelf()
                    }
                    s.remote != null -> {
                        followed = true
                        app.refreshRemote()
                    }
                }
            }
            seen = app.core.awaitRemoteChange(seen, 30_000u)
        }
    }

    /**
     * Swiping the app away from recents stops the music here and ends the
     * service. A song on another device keeps playing there.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!remote) runCatching { (application as NexfyApp).core.pause() }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        unregisterReceiver(noisyReceiver)
        abandonFocus()
        holdLocks(false)
        session.release()
        player.release()
        super.onDestroy()
    }

    private fun requestFocus() {
        if (hasFocus) return
        hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    private fun onAudioFocusChange(change: Int) {
        val core = (application as NexfyApp).core
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                core.setDucked(false)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    player.play()
                }
            }
            // A call or a voice assistant: pause, and resume when it ends.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (player.playWhenReady) {
                    resumeOnFocusGain = true
                    player.pause()
                }
            }
            // Navigation or a notification sound: play quieter meanwhile.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> core.setDucked(true)
            // Another player took over for good.
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                hasFocus = false
                player.pause()
            }
        }
    }

    companion object {
        /** Whether the service exists, so the app can start it when playback begins remotely. */
        @Volatile
        var running = false
            private set

        /** As long as Media3 keeps a paused session in the foreground. */
        private const val REMOTE_PAUSED_LINGER_MS = 10 * 60_000L

        /** The buttons beside the transport controls. */
        private val LIKE = SessionCommand("dev.nexfy.LIKE", Bundle.EMPTY)
        private val SHUFFLE = SessionCommand("dev.nexfy.SHUFFLE", Bundle.EMPTY)

        /** The cover handed to the session, in pixels a side. */
        private const val COVER_PX = 320
    }

    /**
     * Keeps the processor awake while a song plays here. No Wi-Fi lock: the
     * one this held asked for the high-performance mode, which Android has
     * ignored since 12 (`high_perf_active_time_ms` stays 0), and Wi-Fi power
     * saving does not starve a buffered stream.
     */
    private fun holdLocks(hold: Boolean) {
        if (hold) {
            if (!wakeLock.isHeld) wakeLock.acquire()
        } else {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }
}
