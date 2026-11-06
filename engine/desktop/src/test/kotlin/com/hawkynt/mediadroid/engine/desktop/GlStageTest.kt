package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.player.Dither
import com.hawkynt.mediadroid.player.ChromaUpscaling
import com.hawkynt.mediadroid.player.Panorama
import com.hawkynt.mediadroid.player.ScreenRect
import com.hawkynt.mediadroid.player.ScreenSetup
import com.hawkynt.mediadroid.player.ScreenWarp
import com.hawkynt.mediadroid.player.PeakDetect
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureCrop
import com.hawkynt.mediadroid.player.ToneMap
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The desktop stage on this machine's graphics (skipped where there is no display to make a context on). */
class GlStageTest {

    private val picture: MediaReader.Picture by lazy {
        val clip = File(requireNotNull(javaClass.getResource("/clip.mp4")).toURI()).path
        MediaReader(clip).use { r ->
            generateSequence { r.read() }.filterIsInstance<MediaReader.Picture>().drop(10).first()
        }
    }

    @Test
    fun `a neutral picture at its own size comes back as it went in`() {
        val out = stage.process(picture, PictureAdjustment.Neutral, picture.width, picture.height)
        assertEquals(picture.width, out.width)
        val worst = picture.rgba.indices.filter { it % 4 != 3 }
            .maxOf { abs((picture.rgba[it].toInt() and 255) - (out.rgba[it].toInt() and 255)) }
        assertTrue("off by $worst", worst <= 1)
    }

    @Test
    fun `a crop takes that part of the picture alone, pixel for pixel`() {
        val part = PictureCrop(10, 6, 40, 20)
        val out = stage.process(picture, PictureAdjustment.Neutral, part.width, part.height, part)
        assertEquals(part.width * part.height * 4, out.rgba.size)
        var worst = 0
        for (y in 0 until part.height) for (x in 0 until part.width) for (c in 0..2) {
            val inside = ((part.top + y) * picture.width + part.left + x) * 4 + c
            val shown = (y * part.width + x) * 4 + c
            worst = maxOf(worst, abs((picture.rgba[inside].toInt() and 255) - (out.rgba[shown].toInt() and 255)))
        }
        assertTrue("off by $worst", worst <= 1)
    }

    @Test
    fun `Lanczos enlarges to the size asked for, and differs from plain scaling`() {
        val lanczos = stage.process(picture, PictureAdjustment(upscaler = PictureAdjustment.Upscaler.LANCZOS), 128, 96)
        val plain = stage.process(picture, PictureAdjustment.Neutral, 128, 96)
        assertEquals(128 * 96 * 4, lanczos.rgba.size)
        assertTrue(lanczos.rgba.indices.any { abs(lanczos.rgba[it] - plain.rgba[it]) > 8 })
    }

    @Test
    fun `Anime4K, ACNet and xBRZ enlarge to the size asked for, with a picture, unlike plain scaling`() {
        val plain = stage.process(picture, PictureAdjustment.Neutral, 128, 96)
        for (upscaler in listOf(
            PictureAdjustment.Upscaler.ANIME4K, PictureAdjustment.Upscaler.ACNET, PictureAdjustment.Upscaler.XBRZ,
        )) {
            val out = stage.process(picture, PictureAdjustment(upscaler = upscaler), 128, 96)
            assertEquals(128 * 96 * 4, out.rgba.size)
            val mean = out.rgba.indices.filter { it % 4 != 3 }.map { out.rgba[it].toInt() and 255 }.average()
            assertTrue("$upscaler: black picture", mean > 20)
            assertTrue("$upscaler: same as plain", out.rgba.indices.any { abs(out.rgba[it] - plain.rgba[it]) > 8 })
        }
    }

    @Test
    fun `an HDR picture comes out tone-mapped as the reference maps it, its peak measured`() {
        // PQ BT.2020, 64 × 32: each channel its own ramp over the code range, so the peak is the picture's own.
        val w = 64
        val h = 32
        val rgb16 = ShortArray(w * h * 3) { i ->
            val x = (i / 3) % w
            val y = (i / 3) / w
            (((x * 997 + y * 1531 * (i % 3 + 1)) % 50000) + 2000).toShort()
        }
        val rgba = ByteArray(w * h * 8)
        for (p in 0 until w * h) {
            for (c in 0..3) {
                val v = if (c == 3) 0xFFFF else rgb16[p * 3 + c].toInt() and 0xFFFF
                rgba[p * 8 + c * 2] = v.toByte()
                rgba[p * 8 + c * 2 + 1] = (v shr 8).toByte()
            }
        }
        val hdr = MediaReader.Picture(1, w, h, rgba, ToneMap.Transfer.PQ)
        val out = stage.process(hdr, PictureAdjustment.Neutral, w, h)
        val detect = PeakDetect().apply { update(PeakDetect.blocks(rgb16, w, h, ToneMap.Transfer.PQ)) }
        val pass = ToneMap.pass(ToneMap.Transfer.PQ, detect.peak)
        // Tone-mapped in floats, then dithered to 8 bits as the stage's last pass does it (see Dither).
        val mapped = ShortArray(w * h * 3)
        val pixel = FloatArray(3)
        for (p in 0 until w * h) {
            for (c in 0..2) pixel[c] = (rgb16[p * 3 + c].toInt() and 0xFFFF) / 65535f
            val m = ToneMap.map(pixel, pass)
            for (c in 0..2) mapped[p * 3 + c] = (m[c].coerceIn(0f, 1f) * 65535f).roundToInt().toShort()
        }
        val expected = Dither.apply(mapped, w, h)
        val off = (0 until w * h * 3).map { i ->
            abs((out.rgba[i / 3 * 4 + i % 3].toInt() and 255) - (expected[i].toInt() and 255))
        }
        // Dithered, not rounded: a good share of the values differ from the nearest level.
        val rounded = ToneMap.apply(rgb16, w, h, pass)
        val unlikeRounded = (0 until w * h * 3).count { out.rgba[it / 3 * 4 + it % 3] != rounded[it] }
        assertTrue("only $unlikeRounded values unlike rounding", unlikeRounded > w * h * 3 / 10)
        if (stage.renderer.startsWith("llvmpipe")) {
            // Mesa's software rasteriser (CI) computes pow() less exactly, and PQ raises to the 78.84th power: there
            // a few values land up to 3 off, and the dither's threshold can tip one of them a level further (6 of 6144
            // measured more than 1 off, the most 4, 325 by 1), never more, and almost all within 1.
            assertTrue("off by ${off.max()}", off.max() <= 4)
            assertTrue("${off.count { it > 1 }} off by more than 1", off.count { it > 1 } <= off.size / 100)
        } else {
            assertTrue("off by ${off.max()}", off.max() <= 1)
        }
    }

    @Test
    fun `a colour correction between two levels is dithered over both, in the right shares`() {
        // Level 100 everywhere, brightened by 0.4 of a level: rounded, all 100; dithered, about two in five go to 101.
        val flat = MediaReader.Picture(0, 64, 64, ByteArray(64 * 64 * 4) { i -> if (i % 4 == 3) -1 else 100 })
        val brighter = PictureAdjustment(brightness = 0.4f / PictureAdjustment.BRIGHTNESS_SPAN)
        val out = stage.process(flat, brighter, 64, 64)
        val values = out.rgba.indices.filter { it % 4 != 3 }.map { out.rgba[it].toInt() and 255 }
        assertTrue(values.all { it == 100 || it == 101 })
        val share = values.count { it == 101 }.toDouble() / values.size
        assertEquals(0.4, share, 0.05)
    }

    @Test
    fun `a blend between two levels keeps its half level in half floats for the dither`() {
        // Levels 100 and 101 blended half and half (100.5), then brightened by 0.4 of a level: 100.9, dithered nine
        // in ten to 101. Blended in 8 bits it would have rounded to a whole level first, and dithered from there.
        fun level(pts: Long, value: Int) =
            MediaReader.Picture(pts, 64, 64, ByteArray(64 * 64 * 4) { i -> if (i % 4 == 3) -1 else value.toByte() })
        val blended = PictureAdjustment(
            interpolation = PictureAdjustment.Interpolation.HIGH,
            brightness = 0.4f / PictureAdjustment.BRIGHTNESS_SPAN,
        )
        val out = stage.process(Moment(level(0, 100), next = level(40, 101), phase = 0.5f), blended, 64, 64)
        val values = out.rgba.indices.filter { it % 4 != 3 }.map { out.rgba[it].toInt() and 255 }
        assertTrue(values.all { it == 100 || it == 101 })
        val share = values.count { it == 101 }.toDouble() / values.size
        assertEquals(0.9, share, 0.05)
    }

    @Test
    fun `xBRZ leaves a flat picture exactly flat`() {
        val flat = MediaReader.Picture(0, 32, 24, ByteArray(32 * 24 * 4) { i -> if (i % 4 == 3) -1 else 0x5a })
        val out = stage.process(flat, PictureAdjustment(upscaler = PictureAdjustment.Upscaler.XBRZ), 64, 48)
        assertTrue(out.rgba.indices.filter { it % 4 != 3 }.all { out.rgba[it] == 0x5a.toByte() })
    }

    // A picture of smooth waves, moved [dx] × [dy] pixels: something for Motion's search to find.
    private fun waves(w: Int, h: Int, dx: Int, dy: Int, pts: Long): MediaReader.Picture {
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            for (c in 0 until 3) {
                val v = 128 + 100 * sin((x + dx) / 9.0 + c) * cos((y + dy) / 7.0 - c)
                rgba[(y * w + x) * 4 + c] = v.toInt().toByte()
            }
            rgba[(y * w + x) * 4 + 3] = -1
        }
        return MediaReader.Picture(pts, w, h, rgba)
    }

    private fun meanDifference(a: ByteArray, b: ByteArray, w: Int, h: Int, margin: Int): Double {
        var sum = 0L
        var n = 0
        for (y in margin until h - margin) for (x in margin until w - margin) for (c in 0 until 3) {
            val i = (y * w + x) * 4 + c
            sum += abs((a[i].toInt() and 255) - (b[i].toInt() and 255))
            n++
        }
        return sum.toDouble() / n
    }

    private fun flat(pts: Long, value: Int) =
        MediaReader.Picture(pts, 32, 24, ByteArray(32 * 24 * 4) { i -> if (i % 4 == 3) -1 else value.toByte() })

    @Test
    fun `blending High shows the two pictures mixed by the clock, Low only around the middle`() {
        val a = flat(0, 40)
        val b = flat(40, 200)
        val high = PictureAdjustment(interpolation = PictureAdjustment.Interpolation.HIGH)
        fun at(phase: Float, p: PictureAdjustment = high) =
            stage.process(Moment(a, next = b, phase = phase), p, 32, 24).rgba[0].toInt() and 255
        assertEquals(40, at(0f))
        assertTrue("${at(0.5f)}", abs(at(0.5f) - 120) <= 1)
        assertEquals(200, at(1f))
        val low = high.copy(interpolation = PictureAdjustment.Interpolation.LOW)
        assertEquals(40, at(0.3f, low))
        assertEquals(200, at(0.7f, low))
    }

    @Test
    fun `Motion builds the picture between along the motion, nearer the truth than a blend`() {
        val w = 256
        val h = 192
        // A move small against the waves: no scene cut, which Motion would not bridge.
        val first = waves(w, h, 0, 0, 0)
        val last = waves(w, h, 4, 2, 40)
        val truth = waves(w, h, 2, 1, 20)
        val motion = PictureAdjustment(interpolation = PictureAdjustment.Interpolation.MOTION)
        val moment = Moment(first, next = last, phase = 0.5f)
        // Asked once, the search starts; until its vectors are in, the earlier picture stays.
        val alone = stage.process(Moment(first), PictureAdjustment.Neutral, w, h).rgba
        var out = stage.process(moment, motion, w, h).rgba
        val deadline = System.nanoTime() + 2_000_000_000L
        while (out.contentEquals(alone) && System.nanoTime() < deadline) {
            Thread.sleep(20)
            out = stage.process(moment, motion, w, h).rgba
        }
        val blend = stage.process(moment, motion.copy(interpolation = PictureAdjustment.Interpolation.HIGH), w, h)
        val motionError = meanDifference(out, truth.rgba, w, h, 24)
        val blendError = meanDifference(blend.rgba, truth.rgba, w, h, 24)
        assertTrue("motion $motionError, blend $blendError", motionError < blendError / 2)
    }

    @Test
    fun `at field rate the second half of a picture's time shows its other field`() {
        val w = 64
        val h = 48
        // Interlaced: the odd rows a moment later than the even ones.
        fun combed(pts: Long, shift: Int) = MediaReader.Picture(pts, w, h, ByteArray(w * h * 4) { i ->
            val y = i / 4 / w
            val x = i / 4 % w
            if (i % 4 == 3) -1 else (if ((x + shift + (y % 2) * 5) % 16 < 8) 30 else 220).toByte()
        })
        val p = (0 until 3).map { combed(it * 40L, it * 7) }
        val fields = PictureAdjustment(deinterlace = PictureAdjustment.Deinterlace.BWDIF, fieldRate = true)
        fun at(phase: Float, a: PictureAdjustment) = stage.process(Moment(p[1], p[0], p[2], phase = phase), a, w, h)
        val first = at(0.2f, fields)
        val second = at(0.7f, fields)
        assertTrue(first.rgba.indices.any { abs(first.rgba[it] - second.rgba[it]) > 8 })
        val frames = fields.copy(fieldRate = false)
        assertTrue(at(0.2f, frames).rgba.contentEquals(at(0.7f, frames).rgba))
    }

    @Test
    fun `an upscaler at half strength lies halfway between plain scaling and itself`() {
        val fsr = PictureAdjustment(upscaler = PictureAdjustment.Upscaler.FSR)
        val full = stage.process(picture, fsr, 128, 96)
        val half = stage.process(picture, fsr.copy(upscalerStrength = 0.5f), 128, 96)
        val plain = stage.process(picture, PictureAdjustment.Neutral, 128, 96)
        val worst = full.rgba.indices.filter { it % 4 != 3 }.maxOf { i ->
            val expected = ((full.rgba[i].toInt() and 255) + (plain.rgba[i].toInt() and 255)) / 2.0
            abs((half.rgba[i].toInt() and 255) - expected)
        }
        assertTrue("off by $worst", worst <= 1.5)
    }

    @Test
    fun `chroma enlarged from the planes agrees with the reference, spline36 and KrigBilateral alike`() {
        val clip = File(requireNotNull(javaClass.getResource("/clip.mp4")).toURI()).path
        val decoded = MediaReader(clip).use { r ->
            r.keepPlanes = true
            generateSequence { r.read() }.filterIsInstance<MediaReader.Picture>().drop(10).first()
        }
        val planes = requireNotNull(decoded.planes) { "the clip's 4:2:0 planes" }
        val w = decoded.width
        val h = decoded.height
        val reference = ChromaUpscaling.Planes(w, h, planes.y, planes.u, planes.v)
        val m = if (planes.fullRange) planes.matrix.toRgbFull() else planes.matrix.toRgb()
        val foot = if (planes.fullRange) 0f else 16f / 255f
        val bilinear = stage.process(decoded, PictureAdjustment.Neutral, w, h)
        for (filter in listOf(PictureAdjustment.Chroma.SPLINE36, PictureAdjustment.Chroma.KRIG)) {
            val out = stage.process(decoded, PictureAdjustment(chroma = filter), w, h)
            val chroma = ChromaUpscaling.upscale(reference, filter)
            var worst = 0f
            for (i in 0 until w * h) {
                val y = (planes.y[i].toInt() and 255) / 255f - foot
                val cb = chroma.cb[i] - 128f / 255f
                val cr = chroma.cr[i] - 128f / 255f
                for (c in 0..2) {
                    val expected = ((m[c] * y + m[3 + c] * cb + m[6 + c] * cr) * 255f).coerceIn(0f, 255f)
                    worst = maxOf(worst, abs((out.rgba[i * 4 + c].toInt() and 255) - expected))
                }
            }
            // Within two steps: the 8-bit rounding, and the GPU solving KrigBilateral's near-singular corners.
            assertTrue("$filter: off by $worst", worst <= 2f)
            assertTrue("$filter: same as bilinear", out.rgba.indices.any { abs(out.rgba[it] - bilinear.rgba[it]) > 2 })
        }
    }

    @Test
    fun `pictures carry their planes only when asked`() {
        assertEquals(null, picture.planes)
    }

    @Test
    fun `one eye of a 3D picture fills the picture`() {
        // Side by side: the left eye dark, the right one light.
        val w = 64
        val h = 32
        val sideBySide = MediaReader.Picture(0, w, h, ByteArray(w * h * 4) { i ->
            if (i % 4 == 3) -1 else (if (i / 4 % w < w / 2) 50 else 210).toByte()
        })
        val left = stage.process(sideBySide, PictureAdjustment(stereo = PictureAdjustment.Stereo.LEFT), w, h)
        val right = stage.process(sideBySide, PictureAdjustment(stereo = PictureAdjustment.Stereo.RIGHT), w, h)
        assertTrue(left.rgba.indices.filter { it % 4 != 3 }.all { left.rgba[it] == 50.toByte() })
        assertTrue(right.rgba.indices.filter { it % 4 != 3 }.all { right.rgba[it] == 210.toByte() })
    }

    @Test
    fun `a dry run measures every picture and the work it took, more while mixing at every refresh`() {
        val lanczos = PictureAdjustment(upscaler = PictureAdjustment.Upscaler.LANCZOS)
        val run = requireNotNull(StageDryRun.run(lanczos, 1280, 1024, 60.0))
        assertEquals(30, run.frames)
        assertTrue(run.workMs > 0.0)
        val medium = lanczos.copy(interpolation = PictureAdjustment.Interpolation.MEDIUM)
        val mixing = requireNotNull(StageDryRun.run(medium, 1280, 1024, 60.0))
        assertTrue("${mixing.workMs} vs ${run.workMs}", mixing.workMs > run.workMs)
    }

    @Test
    fun `panoramic keeps the middle in shape and stretches the sides where Panorama puts them`() {
        // A 4:3 picture whose red channel is its column: shown at 16:9, each column of the screen tells which column
        // of the picture it read.
        val w = 64
        val h = 48
        val ramp = MediaReader.Picture(0, w, h, ByteArray(w * h * 4) { i ->
            when (i % 4) {
                0 -> ((i / 4 % w) * 4).toByte()
                3 -> -1
                else -> 0
            }
        })
        val outW = 256
        val outH = 144
        val ratio = 4f / 3f
        val warp = ScreenWarp.of(ScreenSetup.Default, panorama = ratio)
        val out = stage.process(ramp, PictureAdjustment.Neutral, outW, outH, warp = warp)
        var worst = 0.0
        for (x in 0 until outW) {
            val read = (out.rgba[(outH / 2 * outW + x) * 4].toInt() and 255) / 4.0
            // Where the picture's column centres are: bilinear between them, held at the edges.
            val column = (Panorama.source((x + 0.5) / outW, ratio.toDouble()) * w - 0.5).coerceIn(0.0, w - 1.0)
            worst = maxOf(worst, abs(read - column))
        }
        assertTrue("off by $worst columns", worst <= 0.5)
        // Unlike a plain stretch, which would read the middle of the screen's left quarter from the picture's.
        val plain = stage.process(ramp, PictureAdjustment.Neutral, outW, outH)
        val quarter = (outH / 2 * outW + outW / 4) * 4
        assertTrue(abs((out.rgba[quarter].toInt() and 255) - (plain.rgba[quarter].toInt() and 255)) > 8)
    }

    @Test
    fun `a projector's mirror, flip and keystone land each pixel where ScreenWarp puts it, black beside`() {
        // Red is the picture's column, green its row (both in quarter steps), blue full: black is no picture.
        val w = 64
        val h = 48
        val grid = MediaReader.Picture(0, w, h, ByteArray(w * h * 4) { i ->
            when (i % 4) {
                0 -> ((i / 4 % w) * 4).toByte()
                1 -> ((i / 4 / w) * 4).toByte()
                else -> -1
            }
        })
        val outW = 128
        val outH = 96
        val setup = ScreenSetup(mirror = true, flip = true, keystoneVertical = 0.3f, keystoneHorizontal = -0.1f)
        val warp = ScreenWarp.of(setup, ScreenRect.centred(96, 72, outW, outH))
        val out = stage.process(grid, PictureAdjustment.Neutral, outW, outH, warp = warp)
        var checked = 0
        for (y in 0 until outH step 3) for (x in 0 until outW step 3) {
            // The picture's rows run from the top; the stage's coordinates from the bottom.
            val at = (y * outW + x) * 4
            val blue = out.rgba[at + 2].toInt() and 255
            val source = warp.source((x + 0.5) / outW, 1.0 - (y + 0.5) / outH)
            if (source == null) {
                // Beside the picture, black — but for the one-pixel seam along its edge, which may blend.
                val near = listOf(-1.5 to 0.0, 1.5 to 0.0, 0.0 to -1.5, 0.0 to 1.5).any { (dx, dy) ->
                    warp.source((x + 0.5 + dx) / outW, 1.0 - (y + 0.5 + dy) / outH) != null
                }
                if (!near) assertEquals("black at $x,$y", 0, blue)
                continue
            }
            val column = (source.first * w - 0.5).coerceIn(0.0, w - 1.0)
            val row = ((1.0 - source.second) * h - 0.5).coerceIn(0.0, h - 1.0)
            val red = (out.rgba[at].toInt() and 255) / 4.0
            val green = (out.rgba[at + 1].toInt() and 255) / 4.0
            assertTrue("column at $x,$y: $red, not $column", abs(red - column) <= 0.75)
            assertTrue("row at $x,$y: $green, not $row", abs(green - row) <= 0.75)
            checked++
        }
        assertTrue(checked > 300)
    }

    companion object {
        private lateinit var stage: GlStage

        // MEDIADROID_GL=required (CI, on Mesa's software OpenGL) turns a missing context into a failure: there the
        // tests must run, not skip themselves and pass on nothing.
        private val required = System.getenv("MEDIADROID_GL") == "required"

        private fun expect(message: String, condition: Boolean) =
            if (required) assertTrue(message, condition) else assumeTrue(message, condition)

        @BeforeClass @JvmStatic fun open() {
            val windows = System.getProperty("os.name").startsWith("Windows")
            val display = System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null
            expect("no display", windows || display)
            stage = GlStage()
            expect("no OpenGL 4.3 context", stage.ready)
        }

        @AfterClass @JvmStatic fun close() {
            if (::stage.isInitialized) stage.close()
        }
    }
}
