package com.hawkynt.mediadroid.player

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pictures to set a screen up by, drawn at the screen's own size so every pixel is one of the screen's, in full-range
 * RGB (0 black, 255 white): levels are given in percent of that range and rounded, as the standards they follow give
 * them in percent of theirs. A level below black (PLUGE's −2 %) has no code in full range and is drawn black.
 * [hint] says what to look for.
 */
enum class TestPattern(val label: String, val hint: String) {
    /**
     * Black and white levels (after ITU-R BT.814's PLUGE): on black, bars at 2 % and 4 % above black; on a white
     * patch, bars at 96 % and 98 %.
     */
    LEVELS(
        "Black and white",
        "Brightness: the left bar (2 %) barely visible, the right one (4 %) clearly. Contrast: both bars on the " +
            "white patch (96 % and 98 %) still told apart from the white.",
    ),

    /** Grey gradation: eleven steps from black to white in tens of percent, a smooth ramp beneath. */
    GREYS(
        "Grey steps",
        "Every step told apart from the next, none tinted; the ramp beneath without bands.",
    ),

    /** SDTV colour bars, EBU 100/0/75/0 (ITU-R BT.471): white at 100 %, the colours at 75 %, black. */
    BARS_SD(
        "Colour bars (SD)",
        "Colour: yellow, cyan, green, magenta, red and blue clean and evenly bright, none glowing or dull.",
    ),

    /**
     * HDTV colour bars after SMPTE RP 219 / ARIB STD-B28: 75 % bars between 40 % grey; 100 % cyan, blue, yellow and
     * red at the sides beneath, 75 % white, a luma ramp, and at the bottom 15 % grey, black, 100 % white and a PLUGE
     * (−2 %, black, +2 %, black, +4 %).
     */
    BARS_HD(
        "Colour bars (HD)",
        "Colour as with the SD bars; at the bottom right the +2 % and +4 % bars visible on black, the −2 % not.",
    ),

    /** Centring and scaling: square cells, a circle, a cross at the middle and a line on the outermost pixels. */
    GRID(
        "Grid",
        "The circle round, the cells square, the cross in the middle and the frame's outermost line visible on " +
            "every side: nothing cut off, nothing stretched. For a projector, the keystone set until the lines are " +
            "straight and parallel.",
    ),

    /**
     * Gamma: five bands, each half lines of black and white (half the light) and half a solid grey at the level a
     * screen of gamma 1.8, 2.0, 2.2, 2.4 and 2.6 (top to bottom) shows as half the light.
     */
    GAMMA(
        "Gamma",
        "From a distance, the band whose grey matches its lines tells the screen's gamma — from the top 1.8, 2.0, " +
            "2.2, 2.4, 2.6. Most screens should match at 2.2 or 2.4.",
    ),
    ;

    /** The pattern at [width] × [height]: RGBA, rows from the top. */
    fun render(width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val rgb = colour(x, y, width, height)
            val at = (y * width + x) * 4
            out[at] = (rgb shr 16).toByte()
            out[at + 1] = (rgb shr 8).toByte()
            out[at + 2] = rgb.toByte()
            out[at + 3] = -1
        }
        return out
    }

    /** The colour of pixel [x], [y] (from the top left) as 0xRRGGBB. */
    fun colour(x: Int, y: Int, width: Int, height: Int): Int = when (this) {
        LEVELS -> levels(x / width.toDouble(), y / height.toDouble())
        GREYS -> greys(x, y, width, height)
        BARS_SD -> barsSd(x, width)
        BARS_HD -> barsHd(x, y, width, height)
        GRID -> grid(x, y, width, height)
        GAMMA -> gamma(x, y, width, height)
    }

    companion object {
        /** The gammas [GAMMA]'s bands are for, top to bottom. */
        val GAMMAS = listOf(1.8, 2.0, 2.2, 2.4, 2.6)

        /**
         * The household's [picture] as the patterns are shown with it: its colour, sharpness, noise and debanding —
         * what the patterns are there to set — but nothing that would change the pattern itself: no deinterlacing
         * (its lines would blend), no smooth motion, no cropping of its black, no 3D eye, the file's own shape, no
         * station logo hidden in a corner. Nothing that weighs the frames around it either — temporal denoising, the
         * dot-crawl comb: a pattern is one still frame, and those show a picture from behind the newest, so the next
         * pattern would never come — and no colouring of a black-and-white picture, which the grey ones would get.
         */
        fun pictureFor(picture: PictureAdjustment): PictureAdjustment = picture.copy(
            deinterlace = PictureAdjustment.Deinterlace.OFF,
            fieldRate = false,
            interpolation = PictureAdjustment.Interpolation.OFF,
            cropBlackBars = false,
            stereo = PictureAdjustment.Stereo.OFF,
            aspect = AspectOverride.AUTO,
            logo = null,
            temporalDenoise = 0f,
            dotCrawl = 0f,
            colorize = PictureAdjustment.Colorize.OFF,
        )

        /** A grey of [percent] of the full range. */
        fun grey(percent: Double): Int {
            val v = level(percent)
            return v shl 16 or (v shl 8) or v
        }

        /** [percent] of the full range as a code, 0..255; below 0 % black. */
        fun level(percent: Double): Int = (percent.coerceIn(0.0, 100.0) * 255.0 / 100.0).roundToInt()

        private fun rgb(r: Double, g: Double, b: Double) = level(r) shl 16 or (level(g) shl 8) or level(b)

        private fun levels(u: Double, v: Double): Int {
            val tall = v >= 0.25 && v < 0.75
            if (tall && u >= 0.125 && u < 0.1875) return grey(2.0)
            if (tall && u >= 0.25 && u < 0.3125) return grey(4.0)
            if (tall && u >= 0.625 && u < 0.9375) {
                val inner = v >= 0.375 && v < 0.625
                if (inner && u >= 0.6875 && u < 0.75) return grey(96.0)
                if (inner && u >= 0.8125 && u < 0.875) return grey(98.0)
                return grey(100.0)
            }
            return 0
        }

        private fun greys(x: Int, y: Int, width: Int, height: Int): Int {
            if (y * 3 >= height * 2) return grey(100.0 * x / (width - 1).coerceAtLeast(1))
            return grey(10.0 * (x * 11 / width))
        }

        // EBU 100/0/75/0: white 100 %, then yellow, cyan, green, magenta, red, blue at 75 %, black.
        private fun barsSd(x: Int, width: Int): Int = when (x * 8 / width) {
            0 -> rgb(100.0, 100.0, 100.0)
            1 -> rgb(75.0, 75.0, 0.0)
            2 -> rgb(0.0, 75.0, 75.0)
            3 -> rgb(0.0, 75.0, 0.0)
            4 -> rgb(75.0, 0.0, 75.0)
            5 -> rgb(75.0, 0.0, 0.0)
            6 -> rgb(0.0, 0.0, 75.0)
            else -> 0
        }

        private fun barsHd(x: Int, y: Int, width: Int, height: Int): Int {
            // RP 219's grid: sides an eighth of the width each, seven bars between; rows of 7, 1, 1 and 3 twelfths.
            val side = width / 8.0
            val bar = (width - 2 * side) / 7.0
            val inBars = (x - side) / bar
            val row = y * 12 / height
            val left = x < side
            val right = x >= width - side
            return when {
                row < 7 -> when {
                    left || right -> grey(40.0)
                    else -> listOf(
                        rgb(75.0, 75.0, 75.0), rgb(75.0, 75.0, 0.0), rgb(0.0, 75.0, 75.0), rgb(0.0, 75.0, 0.0),
                        rgb(75.0, 0.0, 75.0), rgb(75.0, 0.0, 0.0), rgb(0.0, 0.0, 75.0),
                    )[inBars.toInt().coerceIn(0, 6)]
                }
                row < 8 -> when {
                    left -> rgb(0.0, 100.0, 100.0)
                    right -> rgb(0.0, 0.0, 100.0)
                    else -> grey(75.0)
                }
                row < 9 -> when {
                    left -> rgb(100.0, 100.0, 0.0)
                    right -> rgb(100.0, 0.0, 0.0)
                    else -> grey(100.0 * (inBars / 7.0).coerceIn(0.0, 1.0))
                }
                else -> when {
                    left || right -> grey(15.0)
                    inBars < 1.5 -> 0
                    inBars < 3.5 -> grey(100.0)
                    inBars < 3.5 + 5.0 / 6.0 -> 0
                    // The PLUGE: −2 % (black in full range), black, +2 %, black, +4 %, each a third of a bar, then
                    // black for a whole bar.
                    inBars < 14.0 / 3.0 -> grey(-2.0)
                    inBars < 5.0 -> 0
                    inBars < 16.0 / 3.0 -> grey(2.0)
                    inBars < 17.0 / 3.0 -> 0
                    inBars < 6.0 -> grey(4.0)
                    else -> 0
                }
            }
        }

        private fun grid(x: Int, y: Int, width: Int, height: Int): Int {
            val white = grey(100.0)
            // The outermost pixels: a line that shows whether anything is cut off.
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) return white
            val cx = (width - 1) / 2.0
            val cy = (height - 1) / 2.0
            // Square cells, a twelfth of the height, lines through the middle both ways.
            val cell = height / 12.0
            fun onLine(d: Double): Boolean {
                val m = abs(d) % cell
                return m < 1.0 || cell - m < 1.0
            }
            // A small cross at the very middle.
            val arm = height / 24.0
            if ((abs(x - cx) < 1.5 && abs(y - cy) < arm) || (abs(y - cy) < 1.5 && abs(x - cx) < arm)) return white
            // The circle, nine tenths of the height across.
            if (abs(hypot(x - cx, y - cy) - height * 0.45) < 1.0) return white
            if (onLine(x - cx) || onLine(y - cy)) return grey(50.0)
            return 0
        }

        private fun gamma(x: Int, y: Int, width: Int, height: Int): Int {
            val band = (y * GAMMAS.size / height).coerceAtMost(GAMMAS.size - 1)
            // Left: lines of black and white, half the light; right: the grey a screen of the band's gamma shows
            // as half the light.
            if (x * 2 < width) return if (y % 2 == 0) grey(100.0) else 0
            val v = (255.0 * 0.5.pow(1.0 / GAMMAS[band])).roundToInt()
            return v shl 16 or (v shl 8) or v
        }
    }
}
