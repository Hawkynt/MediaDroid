package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matcher against Chromaprint's own (src/fingerprint_matcher.cpp, utils/gaussian_filter.h, utils/gradient.h)
 * compiled as an oracle: chromaprint/matcher_oracle.json, made by MediaUploadService for its Python port this one is
 * converted from — fpcalc 1.6.2's raw prints of generated melodies (a as FLAC, b the same as a 64 kbit/s MP3, c another
 * melody, b2 the first 40 items of c followed by b), the segments Chromaprint finds between them, and its filters'
 * output for a series.
 */
class PrintMatcherTest {
    private val oracle = javaClass.getResource("/chromaprint/matcher_oracle.json")!!.readText()

    // The fixture's number array named [key] (its first occurrence).
    private fun numbers(key: String): List<Double> {
        val body = Regex("\"$key\": \\[([^\\]]*)]").find(oracle)!!.groupValues[1]
        return body.split(',').map { it.trim().toDouble() }
    }

    private fun print(key: String) = numbers(key).map { it.toLong().toInt() }.toIntArray()

    private val a = print("a")
    private val b = print("b")
    private val c = print("c")
    private val b2 = c.copyOfRange(0, 40) + b

    @Test
    fun `the gaussian and the gradient are Chromaprint's`() {
        val smoothed = PrintMatcher.gaussian(numbers("series").toDoubleArray())
        numbers("gaussian").forEachIndexed { i, want -> assertEquals("gaussian $i", want, smoothed[i], 1e-4) }
        val slope = PrintMatcher.gradient(smoothed)
        numbers("gradient").forEachIndexed { i, want -> assertEquals("gradient $i", want, slope[i], 1e-4) }
    }

    @Test
    fun `at the best offset the segments are Chromaprint's`() {
        fun segments(x: IntArray, y: IntArray): List<List<Number>> {
            val best = PrintMatcher.offsets(x, y).firstOrNull() ?: return emptyList()
            return PrintMatcher.segmentsAt(x, y, best.second).map { listOf(it.first1, it.first2, it.length, it.score) }
        }
        fun check(found: List<List<Number>>, want: List<List<Number>>) {
            assertEquals(want.size, found.size)
            want.zip(found).forEach { (w, f) ->
                assertEquals(w.take(3), f.take(3))
                assertEquals(w[3].toDouble(), f[3].toDouble(), 1e-3)
            }
        }
        check(segments(a, b), listOf(listOf(0, 0, 463, 0.0329)))
        check(segments(a, c), emptyList())
        check(segments(a, b2), listOf(listOf(0, 40, 463, 0.0329)))
        check(segments(b2, a), listOf(listOf(40, 0, 463, 0.0329)))
    }

    @Test
    fun `what two prints share is taken where it lies in each, nothing between different melodies`() {
        val shared = PrintMatcher.shared(a, b2)
        assertEquals(1, shared.size)
        assertEquals(0, shared[0].first1)
        assertEquals(40, shared[0].first2)
        assertEquals(463, shared[0].length)
        assertTrue(PrintMatcher.shared(a, c).isEmpty())
    }
}
