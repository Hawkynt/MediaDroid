package com.hawkynt.mediadroid.player

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.hawkynt.mediadroid.R

/**
 * Keeps the app alive while a film plays on a second screen with the app in the background: a media
 * playback foreground service showing the playback notification. Started while the app is in front
 * (when the video moves to the other screen), stopped when it comes back.
 */
class PlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = latest ?: Notification.Builder(this, PlaybackNotification.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_movie)
            .setContentTitle(getString(R.string.app_name))
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                PlaybackNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(PlaybackNotification.NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // The notification stays: it belongs to the player's notification manager.
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }

    companion object {
        /** The playback notification as last posted, to show while in the foreground. */
        @Volatile
        var latest: Notification? = null

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, PlaybackService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
