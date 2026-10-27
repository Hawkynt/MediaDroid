package com.hawkynt.mediadroid.player

import kotlin.math.log2
import kotlin.math.pow

/**
 * One picture slider. Every slider runs from -1 to 1 with the neutral value in the middle, so the
 * fine adjustments near "unchanged" get the most travel. Multipliers are logarithmic — each half
 * covers the same ratio, ¼× to 4× — and saturation's left half is linear so it can reach grey.
 */
enum class PictureControl(val label: String, val neutral: Float) {
    BRIGHTNESS("Brightness", 0f),
    GAMMA("Gamma", 1f),
    CONTRAST("Contrast", 1f),
    SATURATION("Colors", 1f),
    ;

    /** The value at slider [position] (-1..1). */
    fun valueAt(position: Float): Float {
        val p = position.coerceIn(-1f, 1f)
        return when (this) {
            BRIGHTNESS -> p
            GAMMA, CONTRAST -> 2f.pow(p * OCTAVES)
            SATURATION -> if (p < 0f) 1f + p else 2f.pow(p * OCTAVES)
        }
    }

    /** The slider position (-1..1) showing [value]; values beyond the range sit at the ends. */
    fun positionOf(value: Float): Float = when (this) {
        BRIGHTNESS -> value
        GAMMA, CONTRAST -> log2(value.coerceAtLeast(Float.MIN_VALUE)) / OCTAVES
        SATURATION -> if (value < 1f) value - 1f else log2(value) / OCTAVES
    }.coerceIn(-1f, 1f)

    fun get(picture: PictureAdjustment): Float = when (this) {
        BRIGHTNESS -> picture.brightness
        GAMMA -> picture.gamma
        CONTRAST -> picture.contrast
        SATURATION -> picture.saturation
    }

    fun set(picture: PictureAdjustment, value: Float): PictureAdjustment = when (this) {
        BRIGHTNESS -> picture.copy(brightness = value)
        GAMMA -> picture.copy(gamma = value)
        CONTRAST -> picture.copy(contrast = value)
        SATURATION -> picture.copy(saturation = value)
    }

    private companion object {
        /** Each half of a multiplier slider spans two doublings: ¼× … 1× … 4×. */
        const val OCTAVES = 2f
    }
}
