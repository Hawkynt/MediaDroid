package com.hawkynt.mediadroid.desktop

/**
 * The monitor a window shows on: [key], what its setup is kept under (DesktopStore.screenSetup), a [name] to show,
 * and the panel's [aspect] — its width over its height.
 */
data class OutputScreen(val key: String, val name: String, val aspect: Float) {
    companion object {
        /** A screen when the window's is unknown: no setup of its own, 16:9. */
        val Unknown = OutputScreen("default", "This screen", 16f / 9f)

        /** The monitor [window] is on now. */
        fun of(window: java.awt.Window): OutputScreen {
            val configuration = window.graphicsConfiguration ?: return Unknown
            val bounds = configuration.bounds
            if (bounds.width <= 0 || bounds.height <= 0) return Unknown
            val device = configuration.device
            val id = device.iDstring.orEmpty().ifEmpty { Unknown.key }
            // Two monitors of one model differ by id; the size tells the user which is which.
            return OutputScreen(id, "Screen ${bounds.width} × ${bounds.height}", bounds.width.toFloat() / bounds.height)
        }
    }
}
