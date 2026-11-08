package com.hawkynt.mediadroyd.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerNotificationManager

/** The media notification: what plays, with play/pause, previous and next, in the shade and on the lock screen. */
@OptIn(UnstableApi::class)
object PlaybackNotification {

    const val CHANNEL_ID = "playback"
    const val NOTIFICATION_ID = 1

    /**
     * The notification for [player] under [session]: [smallIcon] in the status bar, [open] when it is touched, each
     * one posted handed to [onPosted] (the foreground service shows the latest).
     */
    fun attach(
        context: Context,
        player: Player,
        session: MediaSession,
        smallIcon: Int,
        open: PendingIntent,
        title: () -> String,
        subtitle: () -> String?,
        posterPath: () -> String?,
        onPosted: (Notification) -> Unit,
    ): PlayerNotificationManager {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW),
        )
        val adapter = object : PlayerNotificationManager.MediaDescriptionAdapter {
            override fun getCurrentContentTitle(player: Player): CharSequence = title()
            override fun createCurrentContentIntent(player: Player): PendingIntent = open
            override fun getCurrentContentText(player: Player): CharSequence? = subtitle()
            override fun getCurrentLargeIcon(
                player: Player,
                callback: PlayerNotificationManager.BitmapCallback,
            ): Bitmap? =
                posterPath()?.let { path ->
                    runCatching {
                        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })
                    }.getOrNull()
                }
        }
        return PlayerNotificationManager.Builder(context, NOTIFICATION_ID, CHANNEL_ID)
            .setMediaDescriptionAdapter(adapter)
            .setSmallIconResourceId(smallIcon)
            .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                override fun onNotificationPosted(id: Int, notification: Notification, ongoing: Boolean) =
                    onPosted(notification)
            })
            .build()
            .apply {
                setMediaSessionToken(session.platformToken)
                setUseFastForwardAction(false)
                setUseRewindAction(false)
                setPlayer(player)
            }
    }
}
