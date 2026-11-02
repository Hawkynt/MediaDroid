package com.hawkynt.mediadroid.player

import com.hawkynt.mediadroid.player.PictureAdjustment.Stereo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoTest {
    /** Where [stereo]'s crop takes the frame's texture point ([u], [v]): origin bottom left, as SurfaceTexture's. */
    private fun crop(stereo: Stereo, u: Float, v: Float): FloatArray {
        val m = stereo.cropMatrix()
        // Column-major 4×4, as OpenGL's matrices are; the point is (u, v, 0, 1).
        return floatArrayOf(m[0] * u + m[4] * v + m[12], m[1] * u + m[5] * v + m[13])
    }

    @Test
    fun `each eye is its half of the frame, the whole picture spread over it`() {
        assertArrayEquals(floatArrayOf(0f, 0f), crop(Stereo.LEFT, 0f, 0f), 1e-6f)
        assertArrayEquals(floatArrayOf(0.5f, 1f), crop(Stereo.LEFT, 1f, 1f), 1e-6f)
        assertArrayEquals(floatArrayOf(0.5f, 0f), crop(Stereo.RIGHT, 0f, 0f), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 1f), crop(Stereo.RIGHT, 1f, 1f), 1e-6f)
        // Texture rows count from the bottom: the top half is the upper one.
        assertArrayEquals(floatArrayOf(0f, 0.5f), crop(Stereo.TOP, 0f, 0f), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 1f), crop(Stereo.TOP, 1f, 1f), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f), crop(Stereo.BOTTOM, 0f, 0f), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 0.5f), crop(Stereo.BOTTOM, 1f, 1f), 1e-6f)
    }

    @Test
    fun `off leaves the frame whole`() {
        assertArrayEquals(floatArrayOf(0.25f, 0.75f), crop(Stereo.OFF, 0.25f, 0.75f), 1e-6f)
    }

    @Test
    fun `one eye needs the filters' stage, and nothing else does by default`() {
        assertEquals(Stereo.OFF, PictureAdjustment.Neutral.stereo)
        assertTrue(PictureAdjustment(stereo = Stereo.RIGHT).hasFilters)
        assertFalse(PictureAdjustment.Neutral.hasFilters)
    }
}
