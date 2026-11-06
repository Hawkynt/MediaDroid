package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.player.AssTrack
import com.hawkynt.mediadroid.player.LibAss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * libass as the native library carries it, held to libass's own test program: styled.ass — styles, outlines and
 * shadows, a rotated sign at its \pos, karaoke part-way through its syllables, a fade, a blur and a vector drawing —
 * drawn over a transparent frame at three moments must give the pictures that program drew from a separately built
 * libass (resources/ass, made by oracle.sh). The two lay libass's pieces over each other with their own rounding, so a
 * channel may be off by a little, never more.
 * It needs the library built for this system: `-PnativeLibrary` (_desktop.yml, on Linux, Windows and Linux arm64).
 */
class LibAssTest {
    // A native call that never returns fails the test with where it hangs, not the whole run.
    @get:Rule
    val timeout: Timeout = Timeout.builder().withTimeout(60, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/ass/$name")) { name }.readBytes()

    @Before
    fun library() {
        assertTrue("the native library did not load", NativeNetworks.loaded)
        assertTrue("the native library carries no libass", LibAss.available)
    }

    @Test
    fun `the library carries libass 0_17_5`() = assertEquals(0x01705000, LibAss.version())

    @Test
    fun `a styled script is drawn as libass's own test program draws it`() {
        AssTrack().use { track ->
            track.addFont("NotoSans-Basic.ttf", resource("NotoSans-Basic.ttf"))
            track.script(resource("styled.ass"))
            for (ms in listOf(500L, 1500L, 2500L)) {
                val picture = checkNotNull(track.render(ms, WIDTH, HEIGHT)) { "nothing drawn at $ms ms" }
                val expected = ImageIO.read(javaClass.getResource("/ass/styled-${ms}ms.png"))
                var worst = 0
                for (y in 0 until HEIGHT) {
                    for (x in 0 until WIDTH) {
                        val inside = x - picture.x in 0 until picture.width && y - picture.y in 0 until picture.height
                        val got = if (inside) picture.pixels[(y - picture.y) * picture.width + x - picture.x] else 0
                        worst = maxOf(worst, difference(expected.getRGB(x, y), got))
                    }
                }
                assertTrue("at $ms ms a channel is off by $worst", worst <= TOLERANCE)
            }
        }
    }

    @Test
    fun `a picture is only drawn again when it changes`() {
        AssTrack().use { track ->
            track.addFont("NotoSans-Basic.ttf", resource("NotoSans-Basic.ttf"))
            track.script(resource("styled.ass"))
            checkNotNull(track.render(500, WIDTH, HEIGHT))
            assertNull(track.render(500, WIDTH, HEIGHT))
            assertTrue(track.render(3500, WIDTH, HEIGHT)!!.isEmpty)
        }
    }

    @Test
    fun `events fed one by one show for their time`() {
        AssTrack().use { track ->
            track.addFont("NotoSans-Basic.ttf", resource("NotoSans-Basic.ttf"))
            val script = String(resource("styled.ass"))
            track.header(script.substringBefore("Dialogue:").toByteArray())
            track.event("0,0,Default,,0,0,0,,Hello".toByteArray(), 1000, 500)
            assertTrue(track.render(900, WIDTH, HEIGHT)!!.isEmpty)
            val shown = track.render(1200, WIDTH, HEIGHT)!!
            assertTrue(!shown.isEmpty && shown.y > HEIGHT / 2)
            // The same event again (its ReadOrder) is not added twice; once flushed, nothing is left.
            track.event("0,0,Default,,0,0,0,,Hello".toByteArray(), 1000, 500)
            assertNull(track.render(1200, WIDTH, HEIGHT))
            track.flush()
            assertTrue(track.render(1300, WIDTH, HEIGHT)!!.isEmpty)
        }
    }

    /** The largest difference of two ARGB pixels' alpha or premultiplied colours. */
    private fun difference(a: Int, b: Int): Int {
        val alphaA = a ushr 24
        val alphaB = b ushr 24
        var worst = kotlin.math.abs(alphaA - alphaB)
        for (shift in listOf(16, 8, 0)) {
            val colourA = ((a shr shift) and 0xFF) * alphaA / 255
            val colourB = ((b shr shift) and 0xFF) * alphaB / 255
            worst = maxOf(worst, kotlin.math.abs(colourA - colourB))
        }
        return worst
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 360
        const val TOLERANCE = 2
    }
}
