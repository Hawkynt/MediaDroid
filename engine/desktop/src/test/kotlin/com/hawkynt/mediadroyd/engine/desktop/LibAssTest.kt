package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.AssFrame
import com.hawkynt.mediadroyd.player.AssPicture
import com.hawkynt.mediadroyd.player.AssTrack
import com.hawkynt.mediadroyd.player.LibAss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
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

    @Test
    fun `Chinese, Japanese and Korean text falls back to the face given for it`() {
        val collection = File(checkNotNull(javaClass.getResource("/ass/NotoSansCJK-Subset.ttc")).toURI()).path
        // As the file meant it: the face named by its family, an attachment.
        fun asNamed(family: String, text: String) = AssTrack().use { track ->
            track.addFont("NotoSansCJK-Subset.ttc", resource("NotoSansCJK-Subset.ttc"))
            track.script(script(family, text))
            checkNotNull(track.render(1000, WIDTH, HEIGHT))
        }
        // A style naming a font nobody has, as Android sees it without one in the file: only the fallback draws it.
        fun fallenBack(index: Int?, text: String, family: String = "") = AssTrack().use { track ->
            if (index != null) assertEquals(family, track.fallbackFont(collection, index))
            track.script(script("Arial", text))
            checkNotNull(track.render(1000, WIDTH, HEIGHT))
        }
        val japanese = asNamed("Noto Sans JP", "日本語の字幕")
        assertTrue(!japanese.isEmpty)
        assertSamePicture(japanese, fallenBack(0, "日本語の字幕", "Noto Sans JP"))
        assertTrue(fallenBack(null, "日本語の字幕").let { it.isEmpty || !it.pixels.contentEquals(japanese.pixels) })
        assertSamePicture(asNamed("Noto Sans KR", "한국어 자막"), fallenBack(1, "한국어 자막", "Noto Sans KR"))
        AssTrack().use { assertEquals("", it.fallbackFont("$collection.missing", 0)) }
    }

    private fun script(family: String, text: String) = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 640
        PlayResY: 360

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, $STYLE_REST
        Style: Default,$family,48,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,20,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:00.00,0:00:02.00,Default,,0,0,0,,$text
    """.trimIndent().toByteArray()

    private fun assertSamePicture(expected: AssPicture, actual: AssPicture) {
        assertEquals(
            listOf(expected.x, expected.y, expected.width, expected.height),
            listOf(actual.x, actual.y, actual.width, actual.height),
        )
        assertTrue("the pictures differ", expected.pixels.contentEquals(actual.pixels))
    }

    @Test
    fun `a frame showing part of the video keeps signs in place and lines inside`() {
        // The whole video is 640 x 360; the frame shows rows 60 to 300 of it (black bars cropped, or Fill).
        val part = AssFrame.of(0, 0, WIDTH, 240, 0f, -60f, WIDTH.toFloat(), 300f)!!
        AssTrack().use { track ->
            track.addFont("NotoSans-Basic.ttf", resource("NotoSans-Basic.ttf"))
            val script = String(resource("styled.ass"))
            track.header(script.substringBefore("Dialogue:").toByteArray())
            track.event("0,0,Default,,0,0,0,,Hello there".toByteArray(), 0, 1000)
            track.event("1,0,Default,,0,0,0,,{\\an5\\pos(320,150)}Sign".toByteArray(), 2000, 1000)
            track.event("2,0,Default,,0,0,0,,{\\an5\\pos(320,20)}Above".toByteArray(), 4000, 1000)

            // A line without a place of its own: inside the frame, at its foot.
            val line = track.render(500, part, WIDTH, HEIGHT)!!
            assertTrue(!line.isEmpty && line.y + line.height <= part.height && line.y > part.height / 2)
            // A sign: exactly where it is on the whole video, moved up by the 60 rows cut away above.
            val whole = track.render(2500, WIDTH, HEIGHT, WIDTH, HEIGHT)!!
            val shown = track.render(2500, part, WIDTH, HEIGHT)!!
            assertEquals(listOf(whole.x, whole.y - 60, whole.width, whole.height),
                listOf(shown.x, shown.y, shown.width, shown.height))
            assertTrue(whole.pixels.contentEquals(shown.pixels))
            // A sign on the part cut away does not show.
            assertTrue(track.render(4500, part, WIDTH, HEIGHT)!!.isEmpty)
        }
    }

    @Test
    fun `a track shown second stands at the top, its signs where they are`() {
        AssTrack().use { track ->
            track.addFont("NotoSans-Basic.ttf", resource("NotoSans-Basic.ttf"))
            val script = String(resource("styled.ass"))
            track.header(script.substringBefore("Dialogue:").toByteArray())
            track.event("0,0,Default,,0,0,0,,Hello".toByteArray(), 0, 1000)
            track.event("1,0,Default,,0,0,0,,{\\an5\\pos(320,180)}Sign".toByteArray(), 2000, 1000)
            val below = track.render(500, WIDTH, HEIGHT)!!
            val sign = track.render(2500, WIDTH, HEIGHT)!!
            track.linePosition(100.0)
            val above = track.render(500, WIDTH, HEIGHT)!!
            assertTrue(below.y > HEIGHT / 2 && above.y + above.height < HEIGHT / 2)
            assertEquals(listOf(below.width, below.height), listOf(above.width, above.height))
            val signAbove = track.render(2500, WIDTH, HEIGHT)!!
            assertEquals(listOf(sign.x, sign.y), listOf(signAbove.x, signAbove.y))
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
        const val STYLE_REST = "Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, " +
            "Alignment, MarginL, MarginR, MarginV, Encoding"
    }
}
