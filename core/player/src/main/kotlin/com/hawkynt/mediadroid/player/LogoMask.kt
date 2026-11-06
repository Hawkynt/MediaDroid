package com.hawkynt.mediadroid.player

import kotlin.math.roundToInt

/**
 * A station logo or burned-in bug hidden: a rectangle in one of the picture's corners, picked once per show and
 * remembered for its folder, filled from the picture around it ([Mode.BLUR]), greyed out ([Mode.GREY]), or kept no
 * brighter than the picture around it would be there ([Mode.DIM]) — the half-transparent logo that brightens what is
 * under it fades into it, the picture's own darker detail stays.
 *
 * [Mode.BLUR] is FFmpeg's delogo (GPL-2.0-or-later: its behaviour written down and implemented anew, AGENTS.md rung 2,
 * and held to FFmpeg's own output by `LogoMaskTest`): every pixel of the rectangle a weighted mean of the four sides
 * of the ring of pixels just around it — on each side the three ring pixels nearest its row or column, each side
 * weighed by how near the pixel is to it and how far it lies between the other two. DScaler's logo killer (GPL-2.0)
 * gave the idea of greying and of holding the logo's brightness down; no code of either is taken.
 */
data class LogoMask(val corner: Corner, val size: Float, val mode: Mode) {

    /** The corner the logo sits in. */
    enum class Corner(val label: String, val right: Boolean, val bottom: Boolean) {
        TOP_LEFT("Top left", false, false),
        TOP_RIGHT("Top right", true, false),
        BOTTOM_LEFT("Bottom left", false, true),
        BOTTOM_RIGHT("Bottom right", true, true),
    }

    /** How the rectangle is hidden. */
    enum class Mode(val label: String, val description: String) {
        BLUR("Blur", "The logo filled in from the picture around it (FFmpeg's delogo)."),
        GREY("Grey", "The logo's corner greyed out: calm, and nothing of the logo left."),
        DIM("Dim", "The logo held no brighter than the picture around it: its glow gone, the picture's detail kept."),
    }

    /**
     * The rectangle at [width] × [height]: [size] of the width across (5 % to 40 %), three fifths of that down, in its
     * [corner] — kept one pixel inside the picture on every side, as the ring around it has to be.
     */
    fun rectangle(width: Int, height: Int): PictureCrop {
        val w = (size.coerceIn(MIN_SIZE, MAX_SIZE) * width).roundToInt().coerceIn(1, maxOf(1, width - 2))
        val h = (size.coerceIn(MIN_SIZE, MAX_SIZE) * width * ASPECT).roundToInt().coerceIn(1, maxOf(1, height - 2))
        val left = if (corner.right) width - 1 - w else 1
        val top = if (corner.bottom) height - 1 - h else 1
        return PictureCrop(left, top, w, h)
    }

    companion object {
        const val MIN_SIZE = 0.05f
        const val MAX_SIZE = 0.4f

        /** A logo's rectangle is three fifths as tall as it is wide. */
        const val ASPECT = 0.6f

        /** The grey [Mode.GREY] fills with: the middle of the 8-bit range. */
        const val GREY = 128

        /**
         * One channel ([plane], [width] × [height], rows from the top) with [rect] hidden by [mode]; [rect] lies at
         * least one pixel inside the picture.
         */
        fun apply(plane: ByteArray, width: Int, height: Int, rect: PictureCrop, mode: Mode): ByteArray {
            require(plane.size == width * height) { "not $width × $height" }
            require(rect.left >= 1 && rect.top >= 1) { "the ring around the rectangle leaves the picture" }
            require(rect.left + rect.width < width && rect.top + rect.height < height) { "the ring leaves the picture" }
            val out = plane.copyOf()
            fun v(x: Int, y: Int): Long = (plane[y * width + x].toInt() and 0xFF).toLong()
            // The ring around the rectangle.
            val x1 = rect.left - 1
            val x2 = rect.left + rect.width
            val y1 = rect.top - 1
            val y2 = rect.top + rect.height
            for (y in rect.top until y2) for (x in rect.left until x2) {
                val filled = if (mode == Mode.GREY) {
                    GREY
                } else {
                    val left = v(x1, y - 1) + v(x1, y) + v(x1, y + 1)
                    val right = v(x2, y - 1) + v(x2, y) + v(x2, y + 1)
                    val top = v(x - 1, y1) + v(x, y1) + v(x + 1, y1)
                    val bottom = v(x - 1, y2) + v(x, y2) + v(x + 1, y2)
                    val across = (x - x1).toLong() * (x2 - x)
                    val down = (y - y1).toLong() * (y2 - y)
                    val wLeft = (x2 - x) * down
                    val wRight = (x - x1) * down
                    val wTop = across * (y2 - y)
                    val wBottom = across * (y - y1)
                    val total = 3 * (wLeft + wRight + wTop + wBottom)
                    val mean = ((left * wLeft + right * wRight + top * wTop + bottom * wBottom + total / 2) / total)
                        .toInt()
                    if (mode == Mode.DIM) minOf(mean, v(x, y).toInt()) else mean
                }
                out[y * width + x] = filled.toByte()
            }
            return out
        }
    }
}
