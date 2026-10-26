package com.hawkynt.mediadroid.player

/**
 * Picture correction for dark or washed-out videos. Neutral at brightness 0 and gamma, contrast
 * and saturation 1; brightness runs -1..1, the others are multipliers around 1.
 */
data class PictureAdjustment(
    val brightness: Float = 0f,
    val gamma: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
) {
    val isNeutral: Boolean get() = this == Neutral

    /**
     * One 4×5 colour matrix (row-major, offsets in 0..255 units) for the whole adjustment:
     * saturation around grey with the Rec. 709 luma weights, then contrast around mid grey.
     * Brightness and an approximated gamma — a matrix cannot do a true power curve — both land in
     * the offset, so a dark video is lifted at the matrix level instead of washed under a scrim.
     * The saturation rows sum to 1, so the offset passes through untouched.
     */
    fun colorMatrix(): FloatArray {
        val c = contrast
        val s = saturation
        val o = (c - 1f) * -127.5f + (gamma - 1f) * -40f + brightness * 80f
        return floatArrayOf(
            (R + (1f - R) * s) * c, (G - G * s) * c, (B - B * s) * c, 0f, o,
            (R - R * s) * c, (G + (1f - G) * s) * c, (B - B * s) * c, 0f, o,
            (R - R * s) * c, (G - G * s) * c, (B + (1f - B) * s) * c, 0f, o,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    companion object {
        val Neutral = PictureAdjustment()

        // Rec. 709 luma weights, as rounded in the classic saturation matrix.
        private const val R = 0.213f
        private const val G = 0.715f
        private const val B = 0.072f
    }
}
