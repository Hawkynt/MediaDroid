package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PictureControlTest {

    @Test
    fun `every control is neutral in the middle of its slider`() {
        PictureControl.entries.forEach { control ->
            assertEquals(control.name, control.neutral, control.valueAt(0f), 1e-6f)
            assertEquals(control.name, 0f, control.positionOf(control.neutral), 1e-6f)
        }
    }

    @Test
    fun `multipliers reach a quarter and four times at the ends`() {
        listOf(PictureControl.GAMMA, PictureControl.CONTRAST).forEach { control ->
            assertEquals(0.25f, control.valueAt(-1f), 1e-5f)
            assertEquals(4f, control.valueAt(1f), 1e-5f)
            assertEquals(2f, control.valueAt(0.5f), 1e-5f)
        }
    }

    @Test
    fun `colours go all the way to grey on the left and four times on the right`() {
        assertEquals(0f, PictureControl.SATURATION.valueAt(-1f), 1e-6f)
        assertEquals(0.5f, PictureControl.SATURATION.valueAt(-0.5f), 1e-6f)
        assertEquals(4f, PictureControl.SATURATION.valueAt(1f), 1e-5f)
    }

    @Test
    fun `brightness is linear from minus one to one`() {
        assertEquals(-1f, PictureControl.BRIGHTNESS.valueAt(-1f), 1e-6f)
        assertEquals(0.3f, PictureControl.BRIGHTNESS.valueAt(0.3f), 1e-6f)
    }

    @Test
    fun `positions round-trip and clamp out-of-range values`() {
        PictureControl.entries.forEach { control ->
            listOf(-1f, -0.4f, 0.25f, 1f).forEach { pos ->
                assertEquals(control.name, pos, control.positionOf(control.valueAt(pos)), 1e-4f)
            }
        }
        assertEquals(1f, PictureControl.CONTRAST.positionOf(100f), 1e-6f)
        assertEquals(-1f, PictureControl.GAMMA.positionOf(0.01f), 1e-6f)
    }

    @Test
    fun `each control reads and writes its own field`() {
        val p = PictureControl.GAMMA.set(PictureAdjustment.Neutral, 2f)
        assertEquals(2f, p.gamma, 0f)
        assertEquals(2f, PictureControl.GAMMA.get(p), 0f)
        assertEquals(PictureAdjustment.Neutral.copy(gamma = 2f), p)
    }
}
