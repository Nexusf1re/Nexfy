package dev.nexfy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Holds the process in the foreground while the browser covers Nexfy during a
 * sign-in. Without it some phones freeze the backgrounded app, so Spotify's
 * redirect to the loopback listener hangs and the page loads forever.
 */
class LoginService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Login", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Aguardando o login do Spotify")
            .setOngoing(true)
            .build()
        startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "login"
        private const val ID = 7

        /** Run [block] with the process kept awake. */
        suspend fun <T> during(context: Context, block: suspend () -> T): T {
            val intent = Intent(context, LoginService::class.java)
            context.startForegroundService(intent)
            try {
                return block()
            } finally {
                context.stopService(intent)
            }
        }
    }
}
