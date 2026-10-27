package com.hawkynt.mediadroid.imaging

/**
 * A decoded picture: [argb] holds [width] × [height] non-premultiplied ARGB pixels, row by row from
 * the top. [sampled] says it is smaller than the file's picture because it was sampled down.
 */
class RasterImage(val width: Int, val height: Int, val argb: IntArray, val sampled: Boolean = false)

/**
 * Collects the pixels of a [width] × [height] picture into a [RasterImage] no larger than [maxPixels]:
 * when the picture is bigger, only every n-th pixel of every n-th row is kept (n a power of two), so
 * a scanned 6000 × 9000 poster never needs the memory of its full size.
 */
internal class RasterBuilder(width: Int, height: Int, maxPixels: Int) {

    private val step: Int
    private val outWidth: Int
    private val outHeight: Int
    private val pixels: IntArray

    init {
        var s = 1
        while (s < 1 shl 16 && ceil(width, s).toLong() * ceil(height, s) > maxPixels) s *= 2
        step = s
        outWidth = ceil(width, s)
        outHeight = ceil(height, s)
        pixels = IntArray(outWidth * outHeight)
    }

    /** Sets the pixel at ([x], [y]) of the full-size picture; pixels that sampling skips are dropped. */
    fun set(x: Int, y: Int, argb: Int) {
        if (x % step != 0 || y % step != 0) return
        val sx = x / step
        val sy = y / step
        if (sx < outWidth && sy < outHeight) pixels[sy * outWidth + sx] = argb
    }

    /** Whether row [y] of the full-size picture is kept at all, so a decoder can skip work on the others. */
    fun wantsRow(y: Int): Boolean = y % step == 0

    fun build(): RasterImage = RasterImage(outWidth, outHeight, pixels, sampled = step > 1)

    private companion object {
        fun ceil(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
    }
}

/** Packs four 0..255 channels into one ARGB int. */
internal fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

/** An opaque grey. */
internal fun grey(v: Int): Int = argb(0xFF, v, v, v)
