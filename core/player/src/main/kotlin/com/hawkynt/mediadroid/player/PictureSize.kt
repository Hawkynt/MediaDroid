package com.hawkynt.mediadroid.player

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A picture's size in pixels as the filters' stage works on it, and the two steps that shrink it before the
 * heavier filters: the 3D eye ([eye]), cut out as it is, and the fit into 1080p ([reduced]), after
 * deinterlacing — scaling an interlaced picture would blend its fields.
 */
data class PictureSize(val width: Int, val height: Int) {

    /** The half of the frame [stereo] shows, pixel for pixel; the stretch back to the frame's shape is the screen's. */
    fun eye(stereo: PictureAdjustment.Stereo): PictureSize = PictureSize(
        maxOf(1, (width * stereo.width).toInt()),
        maxOf(1, (height * stereo.height).toInt()),
    )

    /** This size brought down to fit [MAX_WIDTH] × [MAX_HEIGHT], keeping its shape; unchanged when it fits or with [Downscaler.OFF]. */
    fun reduced(downscaler: Downscaler): PictureSize {
        val scale = min(MAX_WIDTH.toFloat() / width, MAX_HEIGHT.toFloat() / height)
        if (downscaler == Downscaler.OFF || scale >= 1f) return this
        return PictureSize(maxOf(1, (width * scale).roundToInt()), maxOf(1, (height * scale).roundToInt()))
    }

    companion object {
        /** The largest picture the filters work on: 1080p. */
        const val MAX_WIDTH = 1920
        const val MAX_HEIGHT = 1080
    }
}

/**
 * How a picture larger than 1080p is brought down (see [PictureSize.reduced]). [NEAREST] takes one pixel, [BILINEAR]
 * mixes the four nearest; [BICUBIC] (Keys' cubic, a = -0.5), [LANCZOS] (three lobes) and [AREA] (every pixel by how
 * much of it a new pixel covers) weigh all the pixels a new one spans, in two passes, across and then down.
 * [radius] is the kernel's reach in new pixels; 0 for the one-pass methods.
 */
enum class Downscaler(val label: String, val radius: Float) {
    OFF("Off", 0f),
    NEAREST("Nearest", 0f),
    BILINEAR("Bilinear", 0f),
    BICUBIC("Bicubic", 2f),
    LANCZOS("Lanczos", 3f),
    AREA("Area", 0.5f),
    ;

    /** Whether the method weighs a span of pixels, in two passes. */
    val separable: Boolean get() = radius > 0f

    companion object {
        val DEFAULT = BILINEAR
    }
}
