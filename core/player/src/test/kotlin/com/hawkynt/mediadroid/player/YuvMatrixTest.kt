package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Test

class YuvMatrixTest {

    @Test
    fun `a matrix the stream signals is taken as it is`() {
        assertEquals(YuvMatrix.BT709, YuvMatrix.of(YuvMatrix.BT709, 480))
        assertEquals(YuvMatrix.BT601, YuvMatrix.of(YuvMatrix.BT601, 1080))
        assertEquals(YuvMatrix.BT2020, YuvMatrix.of(YuvMatrix.BT2020, 2160))
    }

    @Test
    fun `without one, SD is BT601 and HD BT709, as FFmpeg and mpv guess`() {
        assertEquals(YuvMatrix.BT601, YuvMatrix.of(null, 480))
        assertEquals(YuvMatrix.BT601, YuvMatrix.of(null, 576))
        assertEquals(YuvMatrix.BT709, YuvMatrix.of(null, 720))
        assertEquals(YuvMatrix.BT709, YuvMatrix.of(null, 1080))
    }

    @Test
    fun `the matrices agree with the published 8-bit limited-range coefficients`() {
        // Column-major: Y's column, Cb's, Cr's; as ITU-R BT.601, BT.709 and BT.2020 give them for 8 bits.
        val published = mapOf(
            YuvMatrix.BT601 to floatArrayOf(
                1.164383f, 1.164383f, 1.164383f,
                0f, -0.391762f, 2.017232f,
                1.596027f, -0.812968f, 0f,
            ),
            YuvMatrix.BT709 to floatArrayOf(
                1.164383f, 1.164383f, 1.164383f,
                0f, -0.213249f, 2.112402f,
                1.792741f, -0.532909f, 0f,
            ),
            YuvMatrix.BT2020 to floatArrayOf(
                1.164383f, 1.164383f, 1.164383f,
                0f, -0.187326f, 2.141772f,
                1.678674f, -0.650424f, 0f,
            ),
        )
        for ((matrix, expected) in published) {
            val actual = matrix.toRgb()
            for (i in expected.indices) assertEquals("$matrix [$i]", expected[i], actual[i], 1e-5f)
        }
    }
}
