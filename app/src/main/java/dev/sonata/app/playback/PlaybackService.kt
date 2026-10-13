package dev.sonata.app.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.sonata.app.MainActivity
import dev.sonata.app.SonataApp

/**
 * Keeps playback alive in the background and publishes it to the system:
 * notification, lock screen, Bluetooth and headset buttons. Media3 promotes
 * the service to the foreground while [SonataPlayer] is playing.
 *
 * SimpleBasePlayer does none of ExoPlayer's housekeeping, so this service
 * also handles audio focus, "becoming noisy" (headphones unplugged) and the
 * wake and Wi-Fi locks that keep the stream going with the screen off.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: SonataPlayer
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock

    private var hasFocus = false
    private var resumeOnFocusGain = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        val app = application as SonataApp
        player = SonataPlayer(app.core, app.bridge)

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
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sonata:playback")
            .apply { setReferenceCounted(false) }
        @Suppress("DEPRECATION") // WIFI_MODE_FULL_LOW_LATENCY needs a foreground app.
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Sonata:stream")
            .apply { setReferenceCounted(false) }

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) requestFocus() else if (!resumeOnFocusGain) abandonFocus()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = holdLocks(isPlaying)
        })

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
            .setCallback(SessionCallback)
            .build()
    }

    /**
     * Media3 refuses items without a playable URI unless the session says
     * how to resolve them. Ours are Spotify track URIs in `mediaId`, which
     * the core resolves itself, so they pass through unchanged.
     */
    private object SessionCallback : MediaSession.Callback {
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

    /** Swiping the app away while paused ends the service; while playing it keeps going. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
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
        val core = (application as SonataApp).core
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
    }

    private fun holdLocks(hold: Boolean) {
        if (hold) {
            if (!wakeLock.isHeld) wakeLock.acquire()
            if (!wifiLock.isHeld) wifiLock.acquire()
        } else {
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock.isHeld) wifiLock.release()
        }
    }
}
