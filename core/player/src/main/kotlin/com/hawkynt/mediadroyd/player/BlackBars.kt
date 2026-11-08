package com.hawkynt.mediadroyd.player

/**
 * Where the picture lies inside the black bars around it, found from its luma frame by frame: from each edge inwards,
 * a row (or column) whose mean luma stays at or below [limit] is bar, the first one above it is picture. [tolerance]
 * bright lines are passed over before one counts — a scratch or a line of noise in the bar. Over several frames the
 * picture only grows: the extent [picture] holds is the largest any frame has shown since the start, or since the last
 * fresh start every [resetAfter] frames (0: never).
 *
 * Written from a description of FFmpeg's cropdetect filter in its black mode (GPL-2.0-or-later, so its behaviour was
 * taken, not its code) and held to that filter's own output in BlackBarsTest: the same extents, the same rounded crop.
 */
class BlackBars(
    val width: Int,
    val height: Int,
    val limit: Int = LIMIT,
    val tolerance: Int = 0,
    val resetAfter: Int = 0,
) {
    /**
     * A picture's extent: its first and last column and row, inclusive. Before anything is found it is turned inside
     * out — [left] at the last column, [right] at the first, the same for the rows — and [found] is false.
     */
    data class Extent(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        /** Whether a bright line was found in both directions. */
        val found: Boolean get() = left <= right && top <= bottom
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
    }

    private var left = 0
    private var top = 0
    private var right = 0
    private var bottom = 0
    private var analysed = 0

    init {
        restart()
    }

    /** The picture's extent over the frames so far. */
    val picture: Extent get() = Extent(left, top, right, bottom)

    /** Forgets every frame so far. */
    fun restart() {
        left = width - 1
        top = height - 1
        right = 0
        bottom = 0
        analysed = 0
    }

    /** Takes one frame's luma, [width] × [height], into [picture]. */
    fun add(luma: Deinterlacer.Plane) {
        require(luma.width == width && luma.height == height) { "${luma.width}x${luma.height}" }
        if (resetAfter > 0 && analysed >= resetAfter) restart()
        analysed++
        val px = luma.pixels
        val rowBright = { y: Int ->
            var sum = 0
            val start = y * width
            for (x in 0 until width) sum += px[start + x]
            sum / width > limit
        }
        val columnBright = { x: Int ->
            var sum = 0
            for (y in 0 until height) sum += px[y * width + x]
            sum / height > limit
        }
        // Each edge is looked for only where it would widen the picture: the top above the top so far, the bottom
        // below both the bottom and the (new) top, and so on.
        edge(0 until top, rowBright)?.let { top = it }
        edge(height - 1 downTo maxOf(bottom, top) + 1, rowBright)?.let { bottom = it }
        edge(0 until left, columnBright)?.let { left = it }
        edge(width - 1 downTo maxOf(right, left) + 1, columnBright)?.let { right = it }
    }

    /**
     * Walks [lines] from the outside in and returns where the picture starts: the line after the last dark one before
     * the bright line past the [tolerance] — or null when the walk ends first.
     */
    private fun edge(lines: IntProgression, bright: (Int) -> Boolean): Int? {
        var start = lines.first
        var brightSeen = 0
        for (line in lines) {
            if (bright(line)) {
                if (++brightSeen > tolerance) return start
            } else {
                start = line + lines.step
            }
        }
        return null
    }

    /**
     * The [picture] as a crop a video coder can take: the corner moved to even columns and rows, the size cut down to a
     * multiple of [multiple] (16 for 1 or less, doubled when odd) with the cut shared between both sides — the crop
     * FFmpeg's cropdetect reports. With nothing found the size comes out negative, as there.
     */
    fun crop(multiple: Int = 16): PictureCrop {
        var step = if (multiple <= 1) 16 else multiple
        if (step % 2 != 0) step *= 2
        var x = (left + 1) and 1.inv()
        var y = (top + 1) and 1.inv()
        var w = right - x + 1
        var h = bottom - y + 1
        val cutW = w % step
        w -= cutW
        x += (cutW / 2 + 1) and 1.inv()
        val cutH = h % step
        h -= cutH
        y += (cutH / 2 + 1) and 1.inv()
        return PictureCrop(x, y, w, h)
    }

    companion object {
        /** The mean luma at or below which a line is bar: 24 of 255, cropdetect's default (24/255 of full scale). */
        const val LIMIT = 24
    }
}

/**
 * A part of a picture, in its pixels: [left] and [top] from the top-left corner, [width] × [height] large.
 */
data class PictureCrop(val left: Int, val top: Int, val width: Int, val height: Int) {

    /** Whether this is the whole of a [frameWidth] × [frameHeight] picture. */
    fun isWhole(frameWidth: Int, frameHeight: Int): Boolean =
        left == 0 && top == 0 && width == frameWidth && height == frameHeight

    /**
     * The crop as a texture matrix (column-major 4×4, as OpenGL's) over a [frameWidth] × [frameHeight] picture whose
     * texture coordinates run from the bottom left, as SurfaceTexture's: the whole picture's coordinates onto this
     * part, applied before the frame's own transform.
     */
    fun textureMatrix(frameWidth: Int, frameHeight: Int): FloatArray {
        val sx = width.toFloat() / frameWidth
        val sy = height.toFloat() / frameHeight
        val x = left.toFloat() / frameWidth
        val y = (frameHeight - top - height).toFloat() / frameHeight
        return floatArrayOf(
            sx, 0f, 0f, 0f,
            0f, sy, 0f, 0f,
            0f, 0f, 1f, 0f,
            x, y, 0f, 1f,
        )
    }

    companion object {
        /** The whole of a [width] × [height] picture. */
        fun whole(width: Int, height: Int) = PictureCrop(0, 0, width, height)
    }
}
