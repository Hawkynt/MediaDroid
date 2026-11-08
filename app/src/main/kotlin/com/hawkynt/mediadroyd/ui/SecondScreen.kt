package com.hawkynt.mediadroyd.ui

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hawkynt.mediadroyd.player.AndroidPlaybackEngine
import com.hawkynt.mediadroyd.player.PlayerPanel

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
 * panel as the phone, so fit, zoom and picture correction apply there as well.
 */
class VideoPresentation(
    context: Context,
    display: Display,
    private val engine: AndroidPlaybackEngine,
) : Presentation(context, display) {

    lateinit var playerView: PlayerPanel
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = PlayerPanel.inflate(context, controls = false).apply { show(engine) }
        setContentView(playerView.root)
        // What the picture leaves free is black, whatever colour the window's theme would give it.
        playerView.root.setBackgroundColor(Color.BLACK)
        window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
    }

    /** Hands the picture back: the player stops drawing here before the window goes. */
    fun release() {
        if (::playerView.isInitialized) playerView.show(null)
        dismiss()
    }
}
