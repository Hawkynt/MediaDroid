package com.hawkynt.mediadroid.player

/**
 * A rectangle on a screen in its texture coordinates — 0 to 1 either way, from the bottom left, as the stage's.
 */
data class ScreenRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    companion object {
        val Whole = ScreenRect(0f, 0f, 1f, 1f)

        /** A [width] × [height] pixel picture centred on a [screenWidth] × [screenHeight] screen. */
        fun centred(width: Int, height: Int, screenWidth: Int, screenHeight: Int): ScreenRect {
            if (screenWidth <= 0 || screenHeight <= 0) return Whole
            val w = width.toFloat() / screenWidth
            val h = height.toFloat() / screenHeight
            return ScreenRect((1f - w) / 2f, (1f - h) / 2f, w, h)
        }
    }
}

/**
 * Keystone correction: a projector aimed up throws a picture wider at the top than at the bottom, one aimed from the
 * side taller on the far side. Drawn as a trapezoid narrowed the other way, it lands as a rectangle. The vertical
 * keystone narrows the top edge (above 0) or the bottom one (below 0) by that share of the width, about the middle;
 * the horizontal one shortens the right edge (above 0) or the left one (below 0) by that share of the height. Both
 * at once make a quadrilateral; a projective map — Heckbert's square-to-quadrilateral ("Fundamentals of Texture
 * Mapping and Image Warping", 1989, §2.2.3) — takes the picture into it, straight lines staying straight.
 */
object Keystone {
    /** How far either keystone goes: 40 % of the edge. */
    const val LIMIT = 0.4f

    /**
     * The corners the picture is drawn into, in the screen's coordinates: bottom left, bottom right, top right, top
     * left, as x and y pairs.
     */
    fun corners(vertical: Float, horizontal: Float): DoubleArray {
        val top = maxOf(vertical, 0f) / 2.0
        val bottom = maxOf(-vertical, 0f) / 2.0
        val right = maxOf(horizontal, 0f) / 2.0
        val left = maxOf(-horizontal, 0f) / 2.0
        return doubleArrayOf(
            bottom, left,
            1.0 - bottom, right,
            1.0 - top, 1.0 - right,
            top, 1.0 - left,
        )
    }

    /**
     * The projective map taking the unit square's corners (0,0), (1,0), (1,1), (0,1) onto [corners] (as [corners]
     * gives them), row by row: x' = (a·u + b·v + c) / (g·u + h·v + 1) and so on.
     */
    fun squareToQuad(corners: DoubleArray): DoubleArray {
        val (x0, y0) = corners[0] to corners[1]
        val (x1, y1) = corners[2] to corners[3]
        val (x2, y2) = corners[4] to corners[5]
        val (x3, y3) = corners[6] to corners[7]
        val sx = x0 - x1 + x2 - x3
        val sy = y0 - y1 + y2 - y3
        if (sx == 0.0 && sy == 0.0) {
            return doubleArrayOf(x1 - x0, x2 - x1, x0, y1 - y0, y2 - y1, y0, 0.0, 0.0, 1.0)
        }
        val dx1 = x1 - x2
        val dx2 = x3 - x2
        val dy1 = y1 - y2
        val dy2 = y3 - y2
        val det = dx1 * dy2 - dx2 * dy1
        val g = (sx * dy2 - dx2 * sy) / det
        val h = (dx1 * sy - sx * dy1) / det
        return doubleArrayOf(
            x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
            y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
            g, h, 1.0,
        )
    }
}

/**
 * What the stage's last pass does to the picture's geometry on one screen: from each point of the screen to the point
 * of the picture it shows — one projective map ([matrix]): mirrored or upside down for a projector, through the
 * keystone's trapezoid, into the rectangle the picture takes on the screen — then the [panorama]'s widening across
 * the picture ([Panorama]). Points the map takes outside the picture are black.
 */
class ScreenWarp private constructor(
    private val rows: DoubleArray,
    /** How many times wider than its shape the picture is shown, panoramic; 1 for not at all. */
    val panorama: Float,
    /** Whether the whole screen is drawn by the stage (a keystone): the picture's place on it is in the map. */
    val wholeScreen: Boolean,
) {
    /** Nothing bent at all: the stage's last pass draws the picture as it is. */
    val isIdentity: Boolean
        get() = panorama <= 1f && rows.indices.all { rows[it] == IDENTITY[it] }

    /** The map as GLSL's mat3 takes it: column by column. */
    val matrix: FloatArray
        get() = FloatArray(9) { i -> rows[(i % 3) * 3 + i / 3].toFloat() }

    /**
     * Where on the picture (0 to 1 either way, from the bottom left) the screen's point [x], [y] shows, or null
     * where it shows none — what the stage's last pass computes for each pixel.
     */
    fun source(x: Double, y: Double): Pair<Double, Double>? {
        val w = rows[6] * x + rows[7] * y + rows[8]
        if (w <= 0.0) return null
        val u = (rows[0] * x + rows[1] * y + rows[2]) / w
        val v = (rows[3] * x + rows[4] * y + rows[5]) / w
        // A point on the picture's edge stays on it however the division rounds.
        if (u < -EDGE || u > 1.0 + EDGE || v < -EDGE || v > 1.0 + EDGE) return null
        val across = u.coerceIn(0.0, 1.0)
        return (if (panorama > 1f) Panorama.source(across, panorama.toDouble()) else across) to v.coerceIn(0.0, 1.0)
    }

    override fun equals(other: Any?): Boolean = other is ScreenWarp && other.rows.contentEquals(rows) &&
        other.panorama == panorama && other.wholeScreen == wholeScreen

    override fun hashCode(): Int = rows.contentHashCode() * 31 + panorama.hashCode()

    companion object {
        private const val EDGE = 1e-6

        private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        val None = ScreenWarp(IDENTITY, 1f, false)

        /**
         * The warp for a screen set up as [setup], the picture taking the rectangle [picture] of it — the whole
         * screen unless [setup] keystones, when the stage draws on all of it — shown [panorama] times wider.
         */
        fun of(setup: ScreenSetup, picture: ScreenRect = ScreenRect.Whole, panorama: Float = 1f): ScreenWarp {
            // The point seen on the wall: the projector mirrors or turns the panel's picture.
            val seen = doubleArrayOf(
                if (setup.mirror) -1.0 else 1.0, 0.0, if (setup.mirror) 1.0 else 0.0,
                0.0, if (setup.flip) -1.0 else 1.0, if (setup.flip) 1.0 else 0.0,
                0.0, 0.0, 1.0,
            )
            // Out of the keystone's quadrilateral into the square the picture's rectangle fills.
            val square = invert(Keystone.squareToQuad(Keystone.corners(setup.keystoneVertical, setup.keystoneHorizontal)))
            val place = if (setup.keystoned) picture else ScreenRect.Whole
            val rect = doubleArrayOf(
                1.0 / place.width, 0.0, -place.x.toDouble() / place.width,
                0.0, 1.0 / place.height, -place.y.toDouble() / place.height,
                0.0, 0.0, 1.0,
            )
            val rows = times(rect, times(square, seen)).let { m -> DoubleArray(9) { m[it] / m[8] } }
            // Exact where nothing bends, so an untouched screen is the identity, not something a rounding away.
            if (!setup.mirror && !setup.flip && !setup.keystoned) return ScreenWarp(IDENTITY, panorama, false)
            return ScreenWarp(rows, panorama, setup.keystoned)
        }

        private fun times(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
            val r = i / 3
            val c = i % 3
            a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }

        private fun invert(m: DoubleArray): DoubleArray {
            val c00 = m[4] * m[8] - m[5] * m[7]
            val c01 = m[5] * m[6] - m[3] * m[8]
            val c02 = m[3] * m[7] - m[4] * m[6]
            val det = m[0] * c00 + m[1] * c01 + m[2] * c02
            return doubleArrayOf(
                c00 / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
                c01 / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
                c02 / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det,
            )
        }
    }
}
