package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * NVScaler against NVIDIA's own NIS_Scaler.h run on the processor (`resources/nis/`: the picture, the
 * harness, and `make-expected.sh`, which made `expected.txt.gz`). Compared away from the top and left
 * edge, where NVIDIA's tile loader wraps an unsigned index and this reference clamps instead.
 */
class NisTest {

    private fun resource(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/nis/$name")) { name }.use { it.readBytes() }

    @Test
    fun `enlarging agrees with NVIDIA's scaler`() {
        val grey = resource("in.gray")
        val rgb = FloatArray(48 * 40 * 3) { (grey[it / 3].toInt() and 0xFF) / 255f }
        val out = Nis.scale(Nis.Picture(48, 40, rgb), 80, 66, sharpness = 0.5f)
        val expected = String(GZIPInputStream(resource("expected.txt.gz").inputStream()).readBytes())
            .lines().filter { it.isNotBlank() }.map { it.toFloat() }
        var worst = 0f
        for (y in 6 until 66) {
            for (x in 6 until 80) worst = maxOf(worst, abs(expected[y * 80 + x] - out[(y * 80 + x) * 3]))
        }
        // Float rounding only: the order of a few additions differs.
        assertEquals("worst difference", 0f, worst, 1e-6f)
    }

    @Test
    fun `a flat picture stays flat, but for NIS's four-decimal coefficients`() {
        val out = Nis.scale(Nis.Picture(16, 16, FloatArray(16 * 16 * 3) { 0.4f }), 24, 24, sharpness = 1f)
        out.forEach { assertEquals(0.4f, it, 1e-4f) }
    }
}
