package com.hawkynt.mediadroyd

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hawkynt.mediadroyd.data.PictureDecoder
import com.hawkynt.mediadroyd.data.stateStoreOf
import com.hawkynt.mediadroyd.player.PlayerWindow
import com.hawkynt.mediadroyd.ui.MediaDroydApp
import com.hawkynt.mediadroyd.ui.theme.MediaDroydTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PictureDecoder.install()
        val store = stateStoreOf(this)
        setContent {
            MediaDroydTheme {
                MediaDroydApp(store)
            }
        }
    }

    /** Before Android 12 the small window is entered by hand when the user leaves mid-film. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && PlayerWindow.playing && !PlayerWindow.elsewhere) {
            runCatching { enterPictureInPictureMode(PlayerWindow.params()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PlayerWindow.inPictureInPicture = isInPictureInPictureMode
    }
}
