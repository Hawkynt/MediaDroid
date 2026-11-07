package com.hawkynt.mediadroid.player

import kotlin.math.roundToInt

/**
 * Where the player's picture goes: in the window's pixels, as the Compose canvas draws it and as the picture window
 * (PictureWindow, the native presentation) does; and the overlay window the controls sit in over that picture window.
 */
object PicturePlacement {

    /**
     * The picture's rectangle (left, top, width, height) in a window of [windowWidth] × [windowHeight] pixels: drawn
     * at its shown size [shownWidth] × [shownHeight] (what the stage drew it at; 0 for a picture straight from the
     * decoder, fitted then by its own shape [imageWidth] × [imageHeight]), centred, moved by the burn-in drift.
     */
    fun shown(
        windowWidth: Int,
        windowHeight: Int,
        shownWidth: Int,
        shownHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        driftX: Int,
        driftY: Int,
    ): IntArray {
        val scale = minOf(windowWidth.toFloat() / imageWidth, windowHeight.toFloat() / imageHeight)
        val w = if (shownWidth > 0) shownWidth.toFloat() else imageWidth * scale
        val h = if (shownHeight > 0) shownHeight.toFloat() else imageHeight * scale
        return intArrayOf(
            ((windowWidth - w) / 2).toInt() + driftX,
            ((windowHeight - h) / 2).toInt() + driftY,
            w.toInt(),
            h.toInt(),
        )
    }

    /**
     * The overlay over a window's content: [x], [y], [width] × [height] on the screen in the window system's units,
     * and the [pixelWidth] × [pixelHeight] the picture window inside it is drawn at.
     */
    data class Overlay(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val pixelWidth: Int,
        val pixelHeight: Int,
    ) {
        /** Whether nothing of the window's content shows (a window shrunk to its title bar). */
        val isEmpty: Boolean get() = width <= 0 || height <= 0
    }

    /**
     * The overlay for a window at [x], [y] of [width] × [height] with its frame's insets ([top], [left], [bottom],
     * [right]: title bar and borders) at [scale] pixels per unit: the window's content, which the picture window fills.
     */
    fun overlay(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
        scale: Double,
    ): Overlay {
        val w = (width - left - right).coerceAtLeast(0)
        val h = (height - top - bottom).coerceAtLeast(0)
        return Overlay(x + left, y + top, w, h, (w * scale).roundToInt(), (h * scale).roundToInt())
    }
}
