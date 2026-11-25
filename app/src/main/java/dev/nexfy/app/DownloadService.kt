package dev.nexfy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** How far the kept playlists and albums got: [done] of [total] songs still missing when it began. */
data class KeepProgress(val done: Int, val total: Int)

/**
 * Holds the process in the foreground while kept playlists and albums
 * download, with their progress in a notification. Without it the phone
 * freezes the app as soon as it leaves the screen, and a kept list stopped
 * part of the way down.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var following: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Músicas baixando para tocar sem internet"
                setShowBadge(false)
            },
        )
        startForeground(ID, notification(progress.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        following?.cancel()
        following = scope.launch {
            progress.collect { now ->
                if (now == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    manager.notify(ID, notification(now))
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 caps data sync work at six hours a day: the rest waits for the next start. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(now: KeepProgress?): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Baixando para o celular")
            .setContentText(now?.takeIf { it.total > 0 }?.let { "${it.done} de ${it.total} músicas" } ?: "Preparando")
            .setProgress(now?.total ?: 0, now?.done ?: 0, now == null || now.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 8

        private val _progress = MutableStateFlow<KeepProgress?>(null)

        /** Null while nothing downloads. */
        val progress: StateFlow<KeepProgress?> = _progress

        /**
         * Run [block], reporting through [report], with the process held in
         * the foreground. Android only lets that start while the app is on
         * screen (or already in the foreground): otherwise the work still
         * runs, for as long as the phone lets the app.
         */
        suspend fun <T> during(context: Context, block: suspend (report: (KeepProgress) -> Unit) -> T): T {
            _progress.value = KeepProgress(0, 0)
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
            try {
                return block { _progress.value = it }
            } finally {
                _progress.value = null
            }
        }
    }
}
