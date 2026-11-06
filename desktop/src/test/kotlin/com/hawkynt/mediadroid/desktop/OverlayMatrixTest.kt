package com.hawkynt.mediadroid.desktop

import androidx.compose.ui.geometry.Offset
import com.hawkynt.mediadroid.player.ScreenSetup
import com.hawkynt.mediadroid.player.ScreenWarp
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayMatrixTest {
    private fun apply(m: FloatArray, x: Float, y: Float): Offset {
        val w = m[6] * x + m[7] * y + m[8]
        return Offset((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    @Test
    fun `Compose's matrix moves the text subtitles where the warp's own does, perspective and all`() {
        for (setup in listOf(
            ScreenSetup(mirror = true),
            ScreenSetup(flip = true),
            ScreenSetup(keystoneVertical = 0.3f, keystoneHorizontal = -0.1f, mirror = true),
        )) {
            val m = ScreenWarp.of(setup).overlayMatrix(1280f, 720f)
            val compose = composeMatrix(m)
            for ((x, y) in listOf(0f to 0f, 640f to 360f, 100f to 650f, 1280f to 720f)) {
                val expected = apply(m, x, y)
                val actual = compose.map(Offset(x, y))
                assertEquals("$setup at $x,$y", expected.x, actual.x, 0.01f)
                assertEquals("$setup at $x,$y", expected.y, actual.y, 0.01f)
            }
        }
    }
}
