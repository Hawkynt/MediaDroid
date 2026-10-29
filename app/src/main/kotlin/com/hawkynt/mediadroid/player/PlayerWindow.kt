package com.hawkynt.mediadroid.player

import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the activity needs to know about the player for picture in picture: whether a video is playing
 * (leaving the app then shrinks it into a small window, unless it is on a second screen) and its shape. The activity reports back
 * whether the small window is showing, so the player can hide everything but the picture.
 */
object PlayerWindow {
    /** A video is playing right now. */
    var playing = false

    /**
     * The video is on a second screen: leaving the app then opens no small window — the film plays on
     * there in the background and the phone's whole screen stays free.
     */
    var elsewhere = false

    /** The video's shape for the small window, or null while unknown. */
    var aspect: Rational? = null

    /** The activity is in picture-in-picture mode. */
    var inPictureInPicture by mutableStateOf(false)

    fun params(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        aspect?.let { builder.setAspectRatio(it) }
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(playing && !elsewhere)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }
}
