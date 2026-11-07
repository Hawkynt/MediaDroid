package com.hawkynt.mediadroid.desktop

import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment

/**
 * The monitor a window shows on: [key], what its setup is kept under (DesktopStore.screenSetup), a [name] to show,
 * the panel's [aspect] — its width over its height — and its [height] in pixels.
 */
data class OutputScreen(val key: String, val name: String, val aspect: Float, val height: Int = 1080) {
    companion object {
        /** A screen when the window's is unknown: no setup of its own, 16:9. */
        val Unknown = OutputScreen("default", "This screen", 16f / 9f)

        /** The monitor [window] is on now. */
        fun of(window: java.awt.Window): OutputScreen = window.graphicsConfiguration?.let(::of) ?: Unknown

        /**
         * Every monitor attached now, each under the key a window on it keeps its setup by; [Unknown] alone where
         * there is no screen to ask (headless).
         */
        fun all(): List<OutputScreen> {
            if (GraphicsEnvironment.isHeadless()) return listOf(Unknown)
            return runCatching {
                GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { of(it.defaultConfiguration) }
            }.getOrNull()?.distinctBy { it.key }?.ifEmpty { null } ?: listOf(Unknown)
        }

        private fun of(configuration: GraphicsConfiguration): OutputScreen {
            val bounds = configuration.bounds
            if (bounds.width <= 0 || bounds.height <= 0) return Unknown
            val device = configuration.device
            val id = device.iDstring.orEmpty().ifEmpty { Unknown.key }
            // Two monitors of one model differ by id; the size tells the user which is which.
            // The device's own pixels: the bounds are scaled by the system's zoom.
            val pixels = (bounds.height * configuration.defaultTransform.scaleY).toInt()
            return OutputScreen(
                id, "Screen ${bounds.width} × ${bounds.height}", bounds.width.toFloat() / bounds.height, pixels,
            )
        }
    }
}
