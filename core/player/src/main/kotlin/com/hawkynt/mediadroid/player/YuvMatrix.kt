package com.hawkynt.mediadroid.player

/**
 * The matrix that turns a decoded frame's YUV into RGB, by the standard's red and blue luma weights [kr] and [kb]
 * (ITU-R BT.601, BT.709, BT.2020).
 */
enum class YuvMatrix(private val kr: Double, private val kb: Double) {
    BT601(0.299, 0.114),
    BT709(0.2126, 0.0722),
    BT2020(0.2627, 0.0593),
    ;

    /**
     * 8-bit limited-range YUV — luma 16..235, chroma 16..240 about 128, each over 255 — to RGB, as a column-major 3 × 3
     * matrix for a shader: Y's column, Cb's, Cr's; the shader takes 16/255 off luma and 128/255 off chroma first.
     */
    fun toRgb(): FloatArray {
        val kg = 1 - kr - kb
        val y = 255.0 / 219
        val c = 255.0 / 224
        return floatArrayOf(
            y.toFloat(), y.toFloat(), y.toFloat(),
            0f, (-c * 2 * (1 - kb) * kb / kg).toFloat(), (c * 2 * (1 - kb)).toFloat(),
            (c * 2 * (1 - kr)).toFloat(), (-c * 2 * (1 - kr) * kr / kg).toFloat(), 0f,
        )
    }

    companion object {
        /** The tallest standard-definition picture (576 lines, PAL). */
        const val SD_HEIGHT = 576

        /**
         * The matrix the stream [signalled], or — where it signals none, as many WMV, DV, RealVideo and MPEG-4 Part 2
         * files do — the one its [height] implies, as FFmpeg and mpv guess: BT.601 up to [SD_HEIGHT] lines, BT.709
         * above.
         */
        fun of(signalled: YuvMatrix?, height: Int): YuvMatrix = signalled ?: if (height <= SD_HEIGHT) BT601 else BT709
    }
}
