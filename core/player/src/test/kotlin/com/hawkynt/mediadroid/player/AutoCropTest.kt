package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

class AutoCropTest {

    private val whole = PictureCrop.whole(1920, 1080)

    /**
     * A [W] × [H] sample: video black (16) everywhere, the picture — a bright texture at [level] — inside [left],
     * [top], [right], [bottom] (inclusive), and [extra] painted on top.
     */
    private fun sample(
        left: Int = 0,
        top: Int = 0,
        right: Int = W - 1,
        bottom: Int = H - 1,
        level: Int = 120,
        extra: (x: Int, y: Int) -> Int? = { _, _ -> null },
    ) = Deinterlacer.Plane(W, H, IntArray(W * H) { i ->
        val x = i % W
        val y = i / W
        extra(x, y) ?: if (x in left..right && y in top..bottom) level + (x * 7 + y * 13) % 40 else 16
    })

    // A 2.39:1 film in a 16:9 frame: 12.6 % of the height black above and below.
    private val letterbox = sample(top = 18, bottom = 125)

    private fun AutoCrop.feed(luma: Deinterlacer.Plane, times: Int): Boolean {
        var changed = false
        repeat(times) { changed = add(luma) or changed }
        return changed
    }

    @Test
    fun `a letterbox is cropped once a few samples agree, not before`() {
        val crop = AutoCrop()
        assertFalse(crop.feed(letterbox, AutoCrop.FIRST_SAMPLES - 1))
        assertEquals(whole, crop.crop(1920, 1080))
        assertTrue(crop.add(letterbox))
        // Rows 18..125 of 144 are the picture: cut where those rows' centres sample the frame, so no bar is left.
        assertEquals(PictureCrop(0, 140, 1920, 800), crop.crop(1920, 1080))
    }

    @Test
    fun `a pillarbox and a windowbox are cropped on their sides`() {
        val pillar = AutoCrop()
        pillar.feed(sample(left = 32, right = 223), AutoCrop.FIRST_SAMPLES)
        assertEquals(PictureCrop(246, 0, 1428, 1080), pillar.crop(1920, 1080))
        val window = AutoCrop()
        window.feed(sample(left = 32, top = 18, right = 223, bottom = 125), AutoCrop.FIRST_SAMPLES)
        assertEquals(PictureCrop(246, 140, 1428, 800), window.crop(1920, 1080))
    }

    @Test
    fun `the crop only ever moves inwards into the picture and lands on even pixels`() {
        val crop = AutoCrop()
        crop.feed(sample(left = 31, top = 17, right = 224, bottom = 126), AutoCrop.FIRST_SAMPLES)
        val c = crop.crop(1001, 563)
        assertEquals(0, c.left % 2)
        assertEquals(0, c.top % 2)
        assertEquals(0, c.width % 2)
        assertEquals(0, c.height % 2)
        // Sample column 31 looked at the frame's column 31.5 × 1001 / 256, row 17 at 17.5 × 563 / 144: the picture
        // may start only just before those, so the crop starts after them — and ends before the last ones' centres.
        assertTrue("$c", c.left >= 31.5 * 1001 / 256 && c.top >= 17.5 * 563 / 144)
        assertTrue("$c", c.left + c.width <= 224.5 * 1001 / 256 && c.top + c.height <= 126.5 * 563 / 144)
    }

    @Test
    fun `a dark scene is never cropped`() {
        val crop = AutoCrop()
        // A night: dim texture over the whole frame, its middle rows a little brighter — on average still dark.
        val night = sample(level = 0) { _, y -> if (y in 50..90) 30 else 8 }
        assertFalse(crop.feed(night, 500))
        assertEquals(whole, crop.crop(1920, 1080))
    }

    @Test
    fun `a dark scene in a cropped film leaves the crop as it is`() {
        val crop = AutoCrop()
        crop.feed(letterbox, AutoCrop.FIRST_SAMPLES)
        val cropped = crop.crop(1920, 1080)
        val night = sample(top = 40, bottom = 100, level = 4)
        assertFalse(crop.feed(night, 500))
        assertEquals(cropped, crop.crop(1920, 1080))
    }

    @Test
    fun `a dark sky above a lit street is not taken for a bar`() {
        val crop = AutoCrop()
        assertFalse(crop.feed(sample(top = 40), 500))
        assertEquals(whole, crop.crop(1920, 1080))
    }

    @Test
    fun `a logo on black is not taken for the picture`() {
        val crop = AutoCrop()
        assertFalse(crop.feed(sample(left = 100, top = 50, right = 156, bottom = 94, level = 200), 500))
        assertEquals(whole, crop.crop(1920, 1080))
    }

    @Test
    fun `black frames say nothing`() {
        val crop = AutoCrop()
        crop.feed(letterbox, AutoCrop.FIRST_SAMPLES)
        val cropped = crop.crop(1920, 1080)
        assertFalse(crop.feed(sample(level = 0) { _, _ -> 16 }, 500))
        assertEquals(cropped, crop.crop(1920, 1080))
    }

    @Test
    fun `subtitles in the bar widen the picture quickly and keep it wide`() {
        val crop = AutoCrop()
        crop.feed(letterbox, AutoCrop.FIRST_SAMPLES)
        val cropped = crop.crop(1920, 1080)
        val subtitle = sample(top = 18, bottom = 125) { x, y -> if (y in 132..137 && x in 60..195) 235 else null }
        assertFalse(crop.add(subtitle))
        assertTrue(crop.add(subtitle))
        val widened = crop.crop(1920, 1080)
        assertEquals(cropped.top, widened.top)
        assertTrue(widened.top + widened.height > 137 * 1080 / 144)
        // Lines come and go: between them the bar is black again, which must not flicker the crop.
        repeat(5) {
            assertFalse(crop.feed(letterbox, 20))
            assertFalse(crop.feed(subtitle, 3))
        }
        assertEquals(widened, crop.crop(1920, 1080))
        // Only a long stretch without them gives the bar back.
        assertFalse(crop.feed(letterbox, AutoCrop.RENARROW_SAMPLES - 1))
        assertTrue(crop.add(letterbox))
        assertEquals(cropped, crop.crop(1920, 1080))
    }

    @Test
    fun `a picture that grows taller is shown whole at once`() {
        val crop = AutoCrop()
        crop.feed(letterbox, AutoCrop.FIRST_SAMPLES)
        // An IMAX sequence opens up the whole frame.
        assertTrue(crop.feed(sample(), AutoCrop.WIDEN_SAMPLES))
        assertEquals(whole, crop.crop(1920, 1080))
    }

    @Test
    fun `odd samples here and there change nothing`() {
        val crop = AutoCrop()
        crop.feed(letterbox, AutoCrop.FIRST_SAMPLES)
        val cropped = crop.crop(1920, 1080)
        repeat(200) { k ->
            val changed = crop.add(if (k % 2 == 0) sample(top = 18, bottom = 125) else sample(top = 0, bottom = 143))
            assertFalse(changed)
        }
        assertEquals(cropped, crop.crop(1920, 1080))
    }

    @Test
    fun `a bar narrower by a line is not worth a change`() {
        val crop = AutoCrop()
        crop.feed(letterbox, AutoCrop.FIRST_SAMPLES)
        assertFalse(crop.feed(sample(top = 17, bottom = 126), 500))
        assertFalse(crop.feed(sample(top = 19, bottom = 124), 500))
    }

    @Test
    fun `a new picture size starts afresh`() {
        val crop = AutoCrop()
        crop.feed(letterbox, AutoCrop.FIRST_SAMPLES)
        val other = Deinterlacer.Plane(128, 96, IntArray(128 * 96) { 100 })
        crop.add(other)
        assertEquals(PictureCrop.whole(640, 480), crop.crop(640, 480))
    }

    @Test
    fun `a crop as a texture matrix maps the whole picture onto its part, rows counted from the bottom`() {
        val m = PictureCrop(240, 140, 1440, 800).textureMatrix(1920, 1080)
        // Column-major: (u, v) → (m0 u + m12, m5 v + m13).
        assertEquals(240f / 1920, m[12] + m[0] * 0f, 1e-6f)
        assertEquals(1680f / 1920, m[12] + m[0] * 1f, 1e-6f)
        // The picture's bottom edge, 140 rows up from the frame's bottom; its top 140 rows down from the frame's top.
        assertEquals(140f / 1080, m[13] + m[5] * 0f, 1e-6f)
        assertEquals(940f / 1080, m[13] + m[5] * 1f, 1e-6f)
        assertTrue(PictureCrop.whole(4, 3).isWhole(4, 3))
        assertFalse(PictureCrop(0, 2, 4, 1).isWhole(4, 3))
    }

    @Test
    fun `the sample is at most 512 by 288, never larger than the picture`() {
        assertEquals(PictureSize(512, 288), AutoCrop.sampleSize(1920, 1080))
        assertEquals(PictureSize(384, 288), AutoCrop.sampleSize(1440, 1080))
        assertEquals(PictureSize(512, 216), AutoCrop.sampleSize(3840, 1620))
        assertEquals(PictureSize(320, 240), AutoCrop.sampleSize(320, 240))
    }

    @Test
    fun `luma is sampled from 8 and 16-bit RGBA`() {
        val rgba = ByteArray(4 * 4 * 4) { i -> if (i % 4 == 1) 200.toByte() else 0 }
        val plane = AutoCrop.luma(rgba, 4, 4, 1, PictureSize(2, 2))
        assertEquals(List(4) { (200 * 7152 + 5000) / 10000 }, plane.pixels.toList())
        val deep = ByteArray(4 * 4 * 8) { i -> if (i % 8 == 3) 200.toByte() else 0 }
        assertEquals(plane.pixels.toList(), AutoCrop.luma(deep, 4, 4, 2, PictureSize(2, 2)).pixels.toList())
    }

    @Test
    fun `on cropdetect's clips the letterbox is cropped and the dark scene never`() {
        fun frames(clip: String): List<Deinterlacer.Plane> {
            val bytes = requireNotNull(AutoCropTest::class.java.getResourceAsStream("/cropdetect/$clip.gray.gz"))
                .use { GZIPInputStream(it).readBytes() }
            val size = 192 * 108
            return (0 until bytes.size / size).map { k ->
                Deinterlacer.Plane(192, 108, IntArray(size) { bytes[k * size + it].toInt() and 0xFF })
            }
        }
        val letterboxed = AutoCrop()
        frames("letterbox").forEach { letterboxed.add(it) }
        val c = letterboxed.crop(192, 108)
        assertTrue("$c", c.top in 14..18 && c.top + c.height in 90..94 && c.width == 192)
        val dark = AutoCrop()
        repeat(10) { frames("dark").forEach { assertFalse(dark.add(it)) } }
        assertEquals(PictureCrop.whole(192, 108), dark.crop(192, 108))
    }

    private companion object {
        const val W = 256
        const val H = 144
    }
}
