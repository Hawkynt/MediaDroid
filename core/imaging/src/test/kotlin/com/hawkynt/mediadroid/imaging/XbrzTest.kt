package com.hawkynt.mediadroid.imaging

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * xBRZ held to the reference. The pictures under `xbrz/` are 24 × 18 test patterns — shapes and lines, colour
 * noise with near-equal colours, a gradient crossed by a curve — and `<name>-<n>x.png` is what Zenju's xBRZ 1.9
 * (`xbrz::scale`, `ColorFormat::rgb`, default `ScalerCfg`), built from its own sources, made of each at n×.
 * That reference is GPL-3.0 and only ever ran as this oracle; none of its code is in [Xbrz].
 */
class XbrzTest {

    private fun rgb(resource: String): Triple<Int, Int, IntArray> {
        val image = Oracle.png(resource)
        val pixels = Oracle.argb(image).map { it and 0xFFFFFF }.toIntArray()
        return Triple(image.width, image.height, pixels)
    }

    private fun agrees(name: String, factor: Int) {
        val (w, h, source) = rgb("xbrz/$name.png")
        val (ew, eh, expected) = rgb("xbrz/$name-${factor}x.png")
        assertEquals(w * factor, ew)
        assertEquals(h * factor, eh)
        val scaled = Xbrz.scale(source, w, h, factor)
        val wrong = expected.indices.filter { scaled[it] != expected[it] }
        assertEquals(
            "$name at ${factor}x: ${wrong.size} pixels differ, first at " +
                wrong.firstOrNull()?.let { "(${it % ew}, ${it / ew})" },
            0, wrong.size,
        )
    }

    @Test fun `shapes agree with the reference at every factor`() = (2..6).forEach { agrees("shapes", it) }

    @Test fun `colour noise agrees with the reference at every factor`() = (2..6).forEach { agrees("noise", it) }

    @Test fun `a gradient agrees with the reference at every factor`() = (2..6).forEach { agrees("gradient", it) }

    @Test fun `a flat picture stays flat`() {
        val scaled = Xbrz.scale(IntArray(12) { 0x336699 }, 4, 3, 3)
        assertEquals(setOf(0x336699), scaled.toSet())
    }

    @Test fun `each pattern of each factor covers only its own corner's half of the block`() {
        for (factor in 2..6) for (pattern in Xbrz.Pattern.entries) {
            val alphas = Xbrz.alphas(factor, pattern)
            for (row in 0 until factor) for (col in 0 until factor) {
                if (alphas[row * factor + col] > 0.0) {
                    assert(row + col >= factor - 1) { "$factor× $pattern reaches ($row, $col)" }
                }
            }
        }
    }
}
