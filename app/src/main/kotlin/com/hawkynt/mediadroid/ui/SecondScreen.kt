package com.hawkynt.mediadroid.ui

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.LayoutInflater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.hawkynt.mediadroid.R

/**
 * The external display a video can be sent to — HDMI, a USB-C dock, a wireless display — or null.
 * Follows displays as they are attached and removed.
 */
@Composable
fun rememberExternalDisplay(): Display? {
    val context = LocalContext.current
    val displays = remember { context.getSystemService(DisplayManager::class.java) }
    var display by remember { mutableStateOf(displays.presentationDisplay()) }
    DisposableEffect(displays) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { display = displays.presentationDisplay() }
            override fun onDisplayRemoved(displayId: Int) { display = displays.presentationDisplay() }
            override fun onDisplayChanged(displayId: Int) { display = displays.presentationDisplay() }
        }
        displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displays.unregisterDisplayListener(listener) }
    }
    return display
}

private fun DisplayManager.presentationDisplay(): Display? =
    getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()

/**
 * The video alone, full screen on [display]; the phone keeps the controls. Uses the same player
 * layout as the phone (texture surface), so fit, zoom and picture correction apply there as well.
 */
class VideoPresentation(
    context: Context,
    display: Display,
    private val player: Player,
) : Presentation(context, display) {

    lateinit var playerView: PlayerView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = (LayoutInflater.from(context).inflate(R.layout.player_view, null, false) as PlayerView).apply {
            useController = false
            this.player = this@VideoPresentation.player
        }
        setContentView(playerView)
    }

    /** Hands the picture back: the player stops drawing here before the window goes. */
    fun release() {
        if (::playerView.isInitialized) playerView.player = null
        dismiss()
    }
}
