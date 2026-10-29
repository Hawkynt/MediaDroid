package com.hawkynt.mediadroid.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerNotificationManager
import com.hawkynt.mediadroid.MainActivity
import com.hawkynt.mediadroid.R

/** The media notification: what plays, with play/pause, previous and next, in the shade and on the lock screen. */
@OptIn(UnstableApi::class)
object PlaybackNotification {

    const val CHANNEL_ID = "playback"
    const val NOTIFICATION_ID = 1

    fun attach(
        context: Context,
        player: Player,
        session: MediaSession,
        title: () -> String,
        subtitle: () -> String?,
        posterPath: () -> String?,
    ): PlayerNotificationManager {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
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
            .setSmallIconResourceId(R.drawable.ic_movie)
            .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                override fun onNotificationPosted(id: Int, notification: Notification, ongoing: Boolean) {
                    PlaybackService.latest = notification
                }
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
