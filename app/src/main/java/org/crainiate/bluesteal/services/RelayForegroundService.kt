package org.crainiate.bluesteal.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.crainiate.bluesteal.R
import org.crainiate.bluesteal.ui.MainActivity

/**
 * Keeps the relay (scan / advertise / tunnel) alive while the app is
 * backgrounded or the screen is locked — the "pocketed peer" case.
 *
 * A started foreground service holds the process at foreground priority so the
 * OS doesn't suspend it; a partial wake lock keeps the CPU awake so the
 * WebSocket and BLE keep running screen-off. The relay logic itself lives on
 * [org.crainiate.bluesteal.BluestealApp] singletons, so this service owns only
 * the foreground lifecycle — [start] it when a relay session begins, [stop] it
 * when the user disconnects.
 */
class RelayForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val channelId = ensureChannel()
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Blue Steal relay active")
            .setContentText("Relaying while backgrounded")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bluesteal:relay").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Relay", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Blue Steal relay is active"
                    },
                )
            }
        }
        return CHANNEL_ID
    }

    companion object {
        private const val CHANNEL_ID = "bluesteal.relay"
        private const val NOTIF_ID = 1001

        /** Start from the foreground (a user tap) so the FGS-start is allowed. */
        fun start(context: Context) {
            val intent = Intent(context, RelayForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayForegroundService::class.java))
        }
    }
}
