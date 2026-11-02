package com.hawkynt.mediadroid.player

import kotlin.math.roundToInt

/**
 * How far ahead decoded pictures are kept, as the household sets it: a duration, a memory size or a number of
 * frames. Each turns into the others through the picture's size and the frame rate; the device's memory is the
 * ceiling ([frames]' ceilingBytes) and [MIN_FRAMES] the floor.
 */
data class DecodedBuffer(val unit: Unit, val value: Float) {

    /** What [value] counts. */
    enum class Unit(val label: String, val range: ClosedFloatingPointRange<Float>, private val step: Float) {
        SECONDS("Seconds", 0.5f..10f, 0.5f),
        MEGABYTES("MB", 50f..2000f, 50f),
        FRAMES("Frames", 4f..300f, 1f),
        ;

        /** [value] on this unit's steps: half seconds, 50 MB, whole frames. */
        fun rounded(value: Float): Float = ((value / step).roundToInt() * step).coerceIn(range)
    }

    /** The pictures to keep ahead for [size] at [frameRate] (≤ 0 when unknown), within [ceilingBytes]. */
    fun frames(size: PictureSize, frameRate: Float, ceilingBytes: Long): Int {
        val bytes = pictureBytes(size)
        val wanted = when (unit) {
            Unit.SECONDS -> (value * rate(frameRate)).roundToInt()
            Unit.MEGABYTES -> (value * MEBIBYTE / bytes).toInt()
            Unit.FRAMES -> value.toInt()
        }
        val ceiling = (ceilingBytes / bytes).toInt()
        return wanted.coerceAtMost(ceiling).coerceAtLeast(MIN_FRAMES)
    }

    /** The memory [frames] take, in MB. */
    fun megabytes(size: PictureSize, frameRate: Float, ceilingBytes: Long): Float =
        frames(size, frameRate, ceilingBytes).toLong() * pictureBytes(size) / MEBIBYTE.toFloat()

    /** The time [frames] cover, in seconds. */
    fun seconds(size: PictureSize, frameRate: Float, ceilingBytes: Long): Float =
        frames(size, frameRate, ceilingBytes) / rate(frameRate)

    /**
     * The pictures a decoder is set up to keep ahead before the video is known: this setting for a 1080p film at
     * 25 fps, but no more than [ceilingBytes] holds of 4K pictures, which a video may still decode to.
     */
    fun decoderFrames(ceilingBytes: Long): Int =
        minOf(frames(FULL_HD, BASIS_RATE, ceilingBytes), (ceilingBytes / pictureBytes(UHD)).toInt())
            .coerceAtLeast(MIN_FRAMES)

    companion object {
        /** The picture and rate a setting is shown and counted for. */
        val FULL_HD = PictureSize(1920, 1080)
        const val BASIS_RATE = 25f
        private val UHD = PictureSize(3840, 2160)

        /** Five seconds ahead, as far as memory allows. */
        val DEFAULT = DecodedBuffer(Unit.SECONDS, 5f)

        /** Pictures always kept ahead, whatever the setting: as many as Media3 keeps on its own. */
        const val MIN_FRAMES = 4

        private const val MEBIBYTE = 1024L * 1024L

        /** The rate a duration counts in when the video's own is not known. */
        private const val FALLBACK_RATE = 30f

        /** The most memory the buffer may take on a device with [totalMemoryBytes]: a quarter of it. */
        fun ceilingBytes(totalMemoryBytes: Long): Long = totalMemoryBytes / 4

        /** A decoded picture's bytes: 8-bit YUV 4:2:0, a byte of luma and half a byte of colour a pixel. */
        fun pictureBytes(size: PictureSize): Long = size.width.toLong() * size.height * 3 / 2

        private fun rate(frameRate: Float) = if (frameRate > 0f) frameRate else FALLBACK_RATE
    }
}
