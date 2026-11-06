package com.hawkynt.mediadroid.player

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Dithering on the way to the screen: a picture held finer than the screen shows — a 10-bit source, HDR tone-mapped in
 * floats, a colour correction's or debanding's float results — rounded to the screen's 8 bits not to the nearest level,
 * which leaves a smooth gradient as visible steps, but up or down by a fixed noise threshold per pixel, so the levels
 * mix in the right shares and the eye sees the values between them.
 *
 * libplacebo's default, which mpv uses as `--dither=fruit`: ordered dithering against a 64 × 64 blue-noise matrix,
 * the same for every frame. Blue noise holds no pattern the eye picks out and, unlike error diffusion, needs nothing
 * from the neighbouring pixels — one texture read per pixel, so it costs a phone next to nothing — and it is
 * temporally stable: a still picture does not shimmer (libplacebo's temporal variant flickers on many LCDs, its own
 * documentation warns, and stays off by default).
 *
 * Converted from libplacebo 7.360 (LGPL-2.1-or-later, see `THIRD_PARTY_NOTICES.md`): `pl_shader_dither`
 * (`src/shaders/dithering.c`) and `pl_generate_blue_noise` (`src/dither.c`, © 2013 Wessel Dankers, from mpv), whose ties
 * are broken by the C library's `rand()` — glibc's, converted below, as libplacebo runs on Linux. This is the reference
 * the app's GL pass (glsl/video/dither.glsl) is written from, held to libplacebo's own output by `DitherTest`.
 */
object Dither {

    /** The matrix's side: libplacebo's default `lut_size` of 6 bits. */
    const val SIZE = 64

    /**
     * Whether the last pass dithers: where the picture reaching it holds more than a [screenBits] screen shows — a source
     * of [sourceBits] deeper than the screen, or [picture]'s colour correction or debanding, whose float results fall
     * between the screen's levels. A plain 8-bit picture on an 8-bit screen is left as it is, and a screen of 16 bits
     * or more is never dithered, as libplacebo's renderer leaves it.
     */
    fun applies(sourceBits: Int, screenBits: Int, picture: PictureAdjustment): Boolean =
        screenBits < 16 && (sourceBits > screenBits || !picture.isColorNeutral || picture.deband > 0f)

    /**
     * libplacebo's blue-noise matrix as ranks: [SIZE] rows of [SIZE], each 0 to [SIZE]² − 1 and every rank once — the
     * threshold of a pixel is its rank / [SIZE]². Made once, on first use (some tens of milliseconds).
     */
    val blueNoise: IntArray by lazy { BlueNoise(6).ranks() }

    /**
     * [blueNoise] as the GL pass reads it (glsl/video/dither.glsl): a [SIZE] × [SIZE] RGBA8 texture, rows as the
     * matrix's, each rank's high byte in red and low byte in green — exact on every chip, unlike a float texture.
     */
    fun noiseRgba(): ByteArray {
        val ranks = blueNoise
        val rgba = ByteArray(ranks.size * 4)
        for (i in ranks.indices) {
            rgba[i * 4] = (ranks[i] shr 8).toByte()
            rgba[i * 4 + 1] = ranks[i].toByte()
            rgba[i * 4 + 3] = -1
        }
        return rgba
    }

    /**
     * [rgb16] (16-bit RGB, rows from the top, [width] × [height]) dithered to 8 bits as libplacebo's `pl_shader_dither`
     * does it: each value scaled to 0..255, the pixel's threshold from [blueNoise] added, and rounded down. Which
     * threshold a pixel gets repeats every [SIZE] pixels across and down, counted from the top left.
     */
    fun apply(rgb16: ShortArray, width: Int, height: Int): ByteArray {
        require(rgb16.size == width * height * 3) { "not $width × $height RGB" }
        val noise = blueNoise
        val out = ByteArray(rgb16.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val bias = noise[(y % SIZE) * SIZE + x % SIZE] / (SIZE * SIZE).toFloat()
                for (c in 0..2) {
                    val i = (y * width + x) * 3 + c
                    // A 16-bit texel as the graphics chip reads it: the value over 65535.
                    val value = (rgb16[i].toInt() and 0xFFFF) / 65535f
                    out[i] = floor(255f * value + bias).toInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }

    /**
     * libplacebo's `pl_generate_blue_noise` (mpv's, after Wessel Dankers' "fruit" dither): the ranks of a void-and-
     * cluster style matrix, placed one at a time where a Gaussian-weighted sum of those placed so far, wrapping round
     * the edges, is lowest — so each new point lands as far from the others as it can. Ties are broken with `rand()`.
     */
    private class BlueNoise(private val sizeBits: Int) {
        private val size = 1 shl sizeBits
        private val area = size * size

        // The Gaussian's weights as unsigned 64-bit fixed point, laid out as the matrix with its centre at middle.
        private val gauss = LongArray(area)
        private val middle: Int
        private val placed = BooleanArray(area)
        private val sums = LongArray(area)
        private val ties = IntArray(area)
        private val ranks = IntArray(area)
        private val random = GlibcRandom()

        init {
            val radius = size / 2 - 1
            middle = at(radius, radius)
            val side = radius * 2 + 1
            val side2 = side * side
            // UINT64_MAX as a double: 2^64.
            val max = 18446744073709551615.0
            val sigma = -ln(1.5 / max * side2) / radius
            for (gy in 0..radius) {
                for (gx in 0..gy) {
                    val cx = gx - radius
                    val cy = gy - radius
                    val e = exp(-sqrt((cx * cx + cy * cy).toDouble()) * sigma)
                    val v = unsigned(e / side2 * max)
                    val far = side - 1
                    for ((a, b) in listOf(gx to gy, gy to gx, gx to far - gy, gy to far - gx, far - gx to gy,
                        far - gy to gx, far - gx to far - gy, far - gy to far - gx)) {
                        gauss[at(a, b)] = v
                    }
                }
            }
        }

        private fun at(x: Int, y: Int): Int = x or (y shl sizeBits)

        // A double in 0..2^64 to the unsigned 64-bit integer C's conversion gives, kept in a Long's bits.
        private fun unsigned(d: Double): Long =
            if (d < 9.223372036854775807E18) d.toLong() else (d - 9.223372036854775807E18).toLong() + Long.MIN_VALUE

        // The Gaussian centred on [c] added to every sum, wrapping round the matrix.
        private fun place(c: Int) {
            if (placed[c]) return
            placed[c] = true
            val offset = (middle + area - c) and (area - 1)
            for (i in 0 until area) sums[i] += gauss[(offset + i) and (area - 1)]
        }

        // The free position with the lowest sum (compared unsigned): the only one, the middle when all are free, or
        // one of the tied ones at random.
        private fun lowest(): Int {
            var min = -1L
            var count = 0
            for (c in 0 until area) {
                if (placed[c]) continue
                val total = sums[c]
                if (java.lang.Long.compareUnsigned(total, min) <= 0) {
                    if (total != min) {
                        min = total
                        count = 0
                    }
                    ties[count++] = c
                }
            }
            if (count == 1) return ties[0]
            if (count == area) return area / 2
            return ties[random.next() % count]
        }

        fun ranks(): IntArray {
            for (rank in 0 until area) {
                val c = lowest()
                place(c)
                ranks[c] = rank
            }
            // Row y, column x is the matrix's (x, y), which at() lays out as the same index.
            return ranks
        }
    }

    /**
     * glibc's `rand()` — `random_r` with its default state (`TYPE_3`: an additive feedback generator over 31 words,
     * the taps three apart, seeded as `srand(1)`, 310 numbers dropped) — as a process that never seeds it draws:
     * converted from glibc's `stdlib/random_r.c` (LGPL-2.1-or-later).
     */
    class GlibcRandom {
        private val state = IntArray(DEGREE)
        private var front = SEPARATION
        private var rear = 0

        init {
            state[0] = 1
            for (i in 1 until DEGREE) {
                // 16807 × the word before, modulo 2^31 − 1, by Schrage's method, as glibc computes it.
                val hi = state[i - 1] / 127773
                val lo = state[i - 1] % 127773
                var word = 16807 * lo - 2836 * hi
                if (word < 0) word += 2147483647
                state[i] = word
            }
            repeat(DEGREE * 10) { next() }
        }

        /** The next number, 0 to 2^31 − 1. */
        fun next(): Int {
            state[front] += state[rear]
            val result = state[front] ushr 1
            front++
            rear++
            if (front >= DEGREE) {
                front = 0
            } else if (rear >= DEGREE) {
                rear = 0
            }
            return result
        }

        private companion object {
            const val DEGREE = 31
            const val SEPARATION = 3
        }
    }
}
