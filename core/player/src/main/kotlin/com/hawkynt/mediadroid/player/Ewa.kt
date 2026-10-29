package com.hawkynt.mediadroid.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Elliptical weighted averaging with a sharpened Jinc ("EWA Lanczos-sharp"): every source pixel within a
 * circle of about three pixels weighs by its distance alone, so edges at any angle are enlarged alike —
 * none of the staircase a separable filter leaves on diagonals. The definition is libplacebo's
 * `ewa_lanczossharp` (LGPL-2.1-or-later, see `THIRD_PARTY_NOTICES.md`): a Jinc kernel windowed by a
 * Jinc stretched over the radius, the third zero of Jinc, with Nicolas Robidoux's blur.
 */
object Ewa {

    /** Jinc's third zero, libplacebo's JINC_ZERO3. */
    private const val JINC_ZERO3 = 3.2383154841662362076499

    /** Robidoux's blur for the sharpened Jinc. */
    private const val BLUR = 0.98125058372237073562493

    /** Jinc's first zero: the window's own radius. */
    private const val JINC_ZERO1 = 1.2196698912665045

    /** How far the weights reach, in source pixels; libplacebo rounds it to a float. */
    val RADIUS: Double = (JINC_ZERO3 * BLUR).toFloat().toDouble()

    /** The weight of a source pixel [distance] source pixels away. */
    fun weight(distance: Double): Double {
        val x = abs(distance)
        if (x > RADIUS) return 0.0
        return jinc(x / BLUR) * jinc(x / RADIUS * JINC_ZERO1)
    }

    /** [size] weights from the centre (index 0) to [RADIUS] (the last), for the graphics pass. */
    fun table(size: Int): FloatArray = FloatArray(size) { weight(RADIUS * it / (size - 1)).toFloat() }

    private fun jinc(x: Double): Double {
        if (x < 1e-8) return 1.0
        val t = x * PI
        return 2.0 * besselJ1(t) / t
    }

    /**
     * The Bessel function J1 by Abramowitz & Stegun's polynomial approximations 9.4.4 (|x| ≤ 3, error
     * under 1.3e-8 on J1(x)/x) and 9.4.6 (x ≥ 3, under 4e-8 and 9e-8 on its two parts).
     */
    private fun besselJ1(x: Double): Double {
        val ax = abs(x)
        if (ax <= 3.0) {
            val y = (x / 3.0) * (x / 3.0)
            return x * (0.5 + y * (-0.56249985 + y * (0.21093573 + y * (-0.03954289 + y * (0.00443319 +
                y * (-0.00031761 + y * 0.00001109))))))
        }
        val z = 3.0 / ax
        val f = 0.79788456 + z * (0.00000156 + z * (0.01659667 + z * (0.00017105 + z * (-0.00249511 +
            z * (0.00113653 + z * -0.00020033)))))
        val theta = ax - 2.35619449 + z * (0.12499612 + z * (0.00005650 + z * (-0.00637879 + z * (0.00074348 +
            z * (0.00079824 + z * -0.00029166)))))
        val j = f * cos(theta) / sqrt(ax)
        return if (x < 0) -j else j
    }
}
