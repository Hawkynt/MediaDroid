package com.hawkynt.mediadroyd.player

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * Debanding: the steps an 8-bit picture leaves in a smooth gradient — a sky, a wall, a fade to black — smoothed
 * away, where film grain would only hide them. Each pixel is compared with the average of four points around it,
 * at a random angle and distance; where the two differ by less than a threshold the pixel is part of a gradient
 * and takes the average, where they differ by more it is detail and stays. Further iterations look twice, three,
 * four times as far with half, a third, a quarter of the threshold. A little grain, held back near black, covers
 * what remains.
 *
 * Converted from libplacebo's `pl_shader_deband` (`src/shaders/sampling.c`) and its random numbers (`sh_prng`, the
 * pcg3d hash of Jarzynski and Olano, JCGT 9(3), 2020) in `src/shaders.c`, 7.360 (LGPL-2.1-or-later, see
 * `THIRD_PARTY_NOTICES.md`) — mpv's `--deband`. This is the reference the app's GL pass (glsl/video/deband.glsl) is
 * written from, held to libplacebo's own output by `DebandTest`.
 */
object Deband {

    /**
     * libplacebo's `pl_deband_params`: [iterations] steps, the [threshold] in thousandths of full scale (each step
     * divides it by its number), the first step's [radius] in pixels (each step reaches that much further), and the
     * [grain] added afterwards in thousandths of full scale.
     */
    data class Params(val iterations: Int, val threshold: Float, val radius: Float, val grain: Float)

    /** libplacebo's `PL_DEBAND_DEFAULTS`, mpv's own: one iteration, threshold 3, radius 16, grain 4. */
    val Defaults = Params(1, 3f, 16f, 4f)

    /** The strongest the slider goes: four iterations — more barely change the picture, as libplacebo notes. */
    private const val MAX_ITERATIONS = 4

    /**
     * The settings for the slider's [strength], 0 to 1, or null at 0 (off): threshold and grain rise with it,
     * [Defaults] in the middle, and past the middle further iterations join, four at the top.
     */
    fun params(strength: Float): Params? {
        if (strength <= 0f) return null
        val s = strength.coerceAtMost(1f)
        val iterations = (1 + floor(6f * (s - 0.5f).coerceAtLeast(0f)).toInt()).coerceAtMost(MAX_ITERATIONS)
        return Params(iterations, Defaults.threshold * 2f * s, Defaults.radius, Defaults.grain * 2f * s)
    }

    /**
     * [rgb] (8-bit RGB, rows from the top, [width] × [height]) debanded as libplacebo does it on an RGBA texture read
     * nearest, the edges clamped. [frame] seeds the random numbers with the pixel's position, as libplacebo's frame
     * index does — an 8-bit counter, so it wraps at 256.
     */
    fun apply(rgb: ByteArray, width: Int, height: Int, params: Params, frame: Int): ByteArray {
        require(rgb.size == width * height * 3) { "not $width × $height RGB" }
        val out = ByteArray(rgb.size)
        val threshold = params.threshold / 1000f
        val grain = (params.grain / 1000.0).toFloat()
        val random = Random()
        val res = FloatArray(3)
        val avg = FloatArray(3)
        // A texel's byte times the float nearest 1/255, a hair off a true division, which decides how an average of
        // four landing on a half step rounds: libplacebo's output on Mesa rounds so (a true division: 3 % apart).
        fun texel(x: Int, y: Int, c: Int): Float {
            val index = (y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)) * 3 + c
            return (rgb[index].toInt() and 0xFF) * (1f / 255f)
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                random.seed(x, y, frame and 0xFF)
                for (c in 0..2) res[c] = texel(x, y, c)
                val cx = x + 0.5f
                val cy = y + 0.5f
                for (i in 1..params.iterations) {
                    random.next()
                    val distance = random.x * (i.toFloat() * params.radius)
                    val angle = random.y * TWO_PI
                    val dx = distance * cos(angle)
                    val dy = distance * sin(angle)
                    val right = floor(cx + dx).toInt()
                    val left = floor(cx - dx).toInt()
                    val down = floor(cy + dy).toInt()
                    val up = floor(cy - dy).toInt()
                    val bound = threshold / i.toFloat()
                    for (c in 0..2) {
                        // The four points a quarter turn apart, summed in libplacebo's order.
                        var sum = 0f
                        sum += texel(right, down, c)
                        sum += texel(left, down, c)
                        sum += texel(left, up, c)
                        sum += texel(right, up, c)
                        avg[c] = sum * 0.25f
                        if (abs(res[c] - avg[c]) <= bound) res[c] = avg[c]
                    }
                }
                if (params.grain > 0f) {
                    random.next()
                    // Black is the neutral value: the grain fades out towards it.
                    res[0] += min(abs(res[0]), grain) * (random.x - 0.5f)
                    res[1] += min(abs(res[1]), grain) * (random.y - 0.5f)
                    res[2] += min(abs(res[2]), grain) * (random.z - 0.5f)
                }
                // Written to 8 bits as Mesa does: to the nearest, a tie (an average of four landing on a half step)
                // to the even one.
                for (c in 0..2) out[(y * width + x) * 3 + c] = round(res[c].coerceIn(0f, 1f) * 255f).toInt().toByte()
            }
        }
        return out
    }

    /** 2π as libplacebo prints it into its shader (`%f`). */
    private const val TWO_PI = 6.283185f

    /**
     * libplacebo's `sh_prng`: pcg3d over the state (pixel x, pixel y, frame), each call stepping it and giving three
     * numbers in 0..1. Ints wrap as GLSL's `uint` does.
     */
    private class Random {
        private var a = 0
        private var b = 0
        private var c = 0
        var x = 0f
        var y = 0f
        var z = 0f

        fun seed(px: Int, py: Int, frame: Int) {
            a = px
            b = py
            c = frame
        }

        fun next() {
            a = 1664525 * a + 1013904223
            b = 1664525 * b + 1013904223
            c = 1664525 * c + 1013904223
            a += b * c
            b += c * a
            c += a * b
            a = a xor (a ushr 16)
            b = b xor (b ushr 16)
            c = c xor (c ushr 16)
            a += b * c
            b += c * a
            c += a * b
            x = unit(a)
            y = unit(b)
            z = unit(c)
        }

        // vec3(s) * 1.0 / float(0xFFFFFFFFu): the uint rounded to a float, times 2^-32 (the constant rounds to 2^32).
        private fun unit(v: Int): Float = (v.toUInt().toFloat()) * (1f / 4294967296f)
    }
}
