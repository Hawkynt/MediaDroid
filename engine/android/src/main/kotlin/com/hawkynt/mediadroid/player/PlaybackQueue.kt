package com.hawkynt.mediadroid.player

import android.app.Notification
import android.app.PendingIntent
import android.content.Context

/**
 * The engine as the media controls see it — Media3's own controls on a [PlayerPanel], the system's media session and
 * the media notification: one file playing, with Next and Previous handed to [onNext] and [onPrevious], which move
 * through the episodes or a movie's parts ([hasNext]: whether there is one after).
 */
class PlaybackQueue(
    engine: AndroidPlaybackEngine,
    hasNext: () -> Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    internal val player = QueuePlayer(engine.exo, hasNext, onNext, onPrevious)

    /**
     * Media controls everywhere: a session (headset, Bluetooth, the system's controls) with [id], and a notification
     * in the shade and on the lock screen showing [title], [subtitle] and the picture at [posterPath], [smallIcon] in
     * the status bar, [open] when touched, each posted one handed to [onPosted]. Closing takes both away.
     */
    fun publish(
        context: Context,
        id: String,
        smallIcon: Int,
        open: PendingIntent,
        title: () -> String,
        subtitle: () -> String?,
        posterPath: () -> String?,
        onPosted: (Notification) -> Unit,
    ): AutoCloseable {
        val session = androidx.media3.session.MediaSession.Builder(context, player).setId(id).build()
        val notifications = PlaybackNotification.attach(
            context, player, session, smallIcon, open, title, subtitle, posterPath, onPosted,
        )
        return AutoCloseable {
            notifications.setPlayer(null)
            session.release()
        }
    }
}
