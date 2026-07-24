package com.riiiiiiiley.discourse.features.call

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.riiiiiiiley.discourse.app.MainActivity

/**
 * Keeps a live call running while the app is backgrounded: without a
 * foreground service Android 12+ cuts mic/camera capture and freezes the
 * process, the MatrixRTC delayed-leave heartbeat stalls, the server fires the
 * leave, and other participants see the user drop — the failure mode the iOS
 * `audio` background mode + held capture session prevents.
 */
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val roomName = intent?.getStringExtra(EXTRA_ROOM_NAME) ?: "Call"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ongoing calls", NotificationManager.IMPORTANCE_LOW),
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("Call — $roomName")
            .setContentText("Tap to return to the call")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(contentIntent)
            .build()

        // Only claim the capture types whose runtime permissions are granted:
        // starting with an ungranted type throws on Android 14+.
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (granted(Manifest.permission.CAMERA)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types) }
        return START_NOT_STICKY
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CHANNEL_ID = "call_ongoing"
        private const val NOTIFICATION_ID = 2
        private const val EXTRA_ROOM_NAME = "roomName"

        fun start(context: Context, roomName: String) {
            runCatching {
                context.startForegroundService(
                    Intent(context, CallForegroundService::class.java)
                        .putExtra(EXTRA_ROOM_NAME, roomName),
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, CallForegroundService::class.java))
            }
        }
    }
}
