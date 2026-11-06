package com.hawkynt.mediadroid.player

/**
 * The upscalers made by networks on ncnn (waifu2x's CUnet, Real-CUGAN's SE model, RealSR's DF2K_JPEG model, as nihui's
 * ncnn ports run them): twice the picture's size, RealSR four times, the screen's scaling doing the rest. Like the
 * other neural choices, one is taken only where a self-test finds the device runs it right and fast enough.
 */
object NeuralEnlarging {
    /** How much larger [upscaler] makes a picture: RealSR and Real-ESRGAN four times, the others twice. */
    fun scale(upscaler: PictureAdjustment.Upscaler): Int =
        if (upscaler == PictureAdjustment.Upscaler.REALSR || upscaler == PictureAdjustment.Upscaler.NEURAL) 4 else 2

    /** What one 576p picture may take: a frame at 25 frames a second. */
    const val BUDGET_MS = 40.0

    /**
     * How far the output, shrunk back to its input's size, may be from that input, as a mean difference per value
     * (0..255): an upscaler adds detail, it does not change the picture.
     */
    const val MAX_ERROR = 10.0

    /** Whether an output [error] from its input (see [MAX_ERROR]) means the unit runs the network right. */
    fun rightEnough(error: Double): Boolean = error <= MAX_ERROR

    /** Whether enlarging a 576p picture in [ms] keeps up with playback. */
    fun fastEnough(ms: Double): Boolean = ms <= BUDGET_MS
}
