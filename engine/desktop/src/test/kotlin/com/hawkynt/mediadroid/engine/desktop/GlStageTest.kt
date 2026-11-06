package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.player.Cdef
import com.hawkynt.mediadroid.player.Deblock
import com.hawkynt.mediadroid.player.Deinterlacer
import com.hawkynt.mediadroid.player.Dither
import com.hawkynt.mediadroid.player.ChromaUpscaling
import com.hawkynt.mediadroid.player.DotCrawl
import com.hawkynt.mediadroid.player.LogoMask
import com.hawkynt.mediadroid.player.MpvShaders
import com.hawkynt.mediadroid.player.OldGame
import com.hawkynt.mediadroid.player.Panorama
import com.hawkynt.mediadroid.player.TemporalSmoothing
import com.hawkynt.mediadroid.player.PeakDetect
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureCrop
import com.hawkynt.mediadroid.player.ScreenRect
import com.hawkynt.mediadroid.player.ScreenSetup
import com.hawkynt.mediadroid.player.ScreenWarp
import com.hawkynt.mediadroid.player.TemporalDenoise
import com.hawkynt.mediadroid.player.TestPattern
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
        val p = (0 until 3).map { combed(it * 40L, it * 7) }
        val fields = PictureAdjustment(deinterlace = PictureAdjustment.Deinterlace.BWDIF, fieldRate = true)
        fun at(phase: Float, a: PictureAdjustment) = stage.process(Moment(p[1], p[0], p[2], phase = phase), a, w, h)
        val first = at(0.2f, fields)
        val second = at(0.7f, fields)
        assertTrue(first.rgba.indices.any { abs(first.rgba[it] - second.rgba[it]) > 8 })
        val frames = fields.copy(fieldRate = false)
        assertTrue(at(0.2f, frames).rgba.contentEquals(at(0.7f, frames).rgba))
    }

    // Interlaced grey bars, the odd rows a moment later than the even ones, moved [shift] pixels.
    private fun combed(pts: Long, shift: Int, w: Int = 64, h: Int = 48) =
        MediaReader.Picture(pts, w, h, ByteArray(w * h * 4) { i ->
            val y = i / 4 / w
            val x = i / 4 % w
            if (i % 4 == 3) -1 else (if ((x + shift + (y % 2) * 5) % 16 < 8) 30 else 220).toByte()
        })

    private fun worstDifference(a: ByteArray, b: ByteArray) =
        a.indices.filter { it % 4 != 3 }.maxOf { abs((a[it].toInt() and 255) - (b[it].toInt() and 255)) }

    @Test
    fun `Greedy HM, Greedy 2-Frame and TomsMoComp draw what the core's reference draws`() {
        val w = 64
        val h = 48
        val p = (0 until 3).map { combed(it * 40L, it * 7) }
        fun luma(picture: MediaReader.Picture) =
            Deinterlacer.Plane(w, h, IntArray(w * h) { picture.rgba[it * 4].toInt() and 255 })
        val modes = mapOf(
            PictureAdjustment.Deinterlace.GREEDY_HM to Deinterlacer.Method.GREEDY_HM,
            PictureAdjustment.Deinterlace.GREEDY_2FRAME to Deinterlacer.Method.GREEDY_2FRAME,
            PictureAdjustment.Deinterlace.TOMSMOCOMP to Deinterlacer.Method.TOMSMOCOMP,
        )
        for ((mode, method) in modes) {
            val out = stage.process(Moment(p[1], p[0], p[2]), PictureAdjustment(deinterlace = mode), w, h)
            val reference = Deinterlacer.deinterlace(method, luma(p[0]), luma(p[1]), luma(p[2]), false, true)
            val expected = ByteArray(w * h * 4) { i -> if (i % 4 == 3) -1 else reference.pixels[i / 4].toByte() }
            val worst = worstDifference(out.rgba, expected)
            assertTrue("$mode off by $worst", worst <= 1)
            assertTrue("$mode rebuilt nothing", worstDifference(out.rgba, p[1].rgba) > 100)
        }
    }

    @Test
    fun `Old console stretches each field as the core's reference does, averaging it with the one before when still`() {
        // Large enough for DScaler's comb factor, which leaves 16 field lines and columns out at each edge.
        val w = 256
        val h = 192
        val console = PictureAdjustment(deinterlace = PictureAdjustment.Deinterlace.OLD_CONSOLE)
        fun luma(picture: MediaReader.Picture) =
            Deinterlacer.Plane(w, h, IntArray(w * h) { picture.rgba[it * 4].toInt() and 255 })
        // Moving half a bar a field: each field drawn alone; still with a little noise between the fields: averaged.
        val moving = (0 until 3).map { combed(it * 40L, it * 19, w, h) }
        val still = (0 until 3).map { k ->
            MediaReader.Picture(k * 40L, w, h, ByteArray(w * h * 4) { i ->
                val noise = (i / 4 / w) % 2 * (k + 1)
                if (i % 4 == 3) -1 else ((if ((i / 4 % w) % 16 < 8) 30 else 220) + noise).toByte()
            })
        }
        for ((p, averaged) in listOf(moving to false, still to true)) {
            for (second in listOf(false, true)) {
                val phase = if (second) 0.7f else 0.2f
                val out = stage.process(Moment(p[1], p[0], p[2], phase = phase), console.copy(fieldRate = true), w, h)
                val reference = OldGame.picture(luma(p[0]), luma(p[1]), second, true, averaged)
                val expected = ByteArray(w * h * 4) { i -> if (i % 4 == 3) -1 else reference.pixels[i / 4].toByte() }
                val worst = worstDifference(out.rgba, expected)
                assertTrue("averaged $averaged, second $second: off by $worst", worst <= 1)
            }
        }
    }

    @Test
    fun `MC Smooth draws what the core's reference draws, either field`() {
        val w = 96
        val h = 64
        // Grey waves moving 3 across and 2 down a field, with a little fixed noise; R, G and B alike.
        fun picture(k: Int) = MediaReader.Picture(k * 40L, w, h, ByteArray(w * h * 4) { i ->
            val x = i / 4 % w
            val y = i / 4 / w
            val t = 2 * k + y % 2
            val v = 120 + 50 * kotlin.math.sin((x + 3 * t) / 5.0) * kotlin.math.cos((y + 2 * t) / 7.0) +
                ((x * 31 + y * 17 + k * 7) % 9 - 4)
            if (i % 4 == 3) -1 else v.toInt().coerceIn(0, 255).toByte()
        })
        val p = (0 until 5).map(::picture)
        fun luma(picture: MediaReader.Picture) =
            Deinterlacer.Plane(w, h, IntArray(w * h) { picture.rgba[it * 4].toInt() and 255 })
        val smooth = PictureAdjustment(deinterlace = PictureAdjustment.Deinterlace.SMOOTH_MC, fieldRate = true)
        for (second in listOf(false, true)) {
            val out = stage.process(Moment(p[2], p[1], p[3], p[4], if (second) 0.7f else 0.2f), smooth, w, h)
            val reference = TemporalSmoothing.deinterlace(
                luma(p[1]), luma(p[1]), luma(p[2]), luma(p[3]), luma(p[4]), second, true,
            )
            val expected = ByteArray(w * h * 4) { i -> if (i % 4 == 3) -1 else reference.pixels[i / 4].toByte() }
            val worst = worstDifference(out.rgba, expected)
            assertTrue("second $second: off by $worst", worst <= 1)
        }
    }

    @Test
    fun `Adaptive weaves a picture that has stood still for 100 fields, and deinterlaces the first that moves`() {
        val w = 64
        val h = 48
        val adaptive = PictureAdjustment(deinterlace = PictureAdjustment.Deinterlace.ADAPTIVE)
        // One combed picture again and again with a little noise — still to DScaler's measure, which BWDIF does not
        // leave as it is — two fields to a picture: woven from the hundredth on.
        val bars = combed(0, 0)
        val still = (0..52).map { k ->
            MediaReader.Picture(k * 40L, w, h, ByteArray(w * h * 4) { i ->
                val noise = (i / 4 * 7 + k * 5) % 7 - 3
                if (i % 4 == 3) -1 else ((bars.rgba[i].toInt() and 255) + noise).toByte()
            })
        }
        val woven = (1..51).map { k ->
            val out = stage.process(Moment(still[k], still[k - 1], still[k + 1]), adaptive, w, h)
            worstDifference(out.rgba, still[k].rgba) <= 1
        }
        assertEquals(List(50) { false } + true, woven)
        val moved = combed(53 * 40L, 7)
        val out = stage.process(Moment(moved, still.last(), null), adaptive, w, h)
        assertTrue(worstDifference(out.rgba, moved.rgba) > 100)
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

    /** A fixture of `resources/mpv/` (make-expected.sh there): a binary PPM as a picture. */
    private fun mpvPicture(name: String): MediaReader.Picture {
        val data = requireNotNull(javaClass.getResourceAsStream("/mpv/$name")) { name }.use { it.readBytes() }
        val header = String(data, 0, 20, Charsets.US_ASCII).split(Regex("\\s+"))
        val w = header[1].toInt()
        val h = header[2].toInt()
        val rgb = data.copyOfRange(data.size - w * h * 3, data.size)
        return MediaReader.Picture(0, w, h, ByteArray(w * h * 4) { if (it % 4 == 3) -1 else rgb[it / 4 * 3 + it % 4] })
    }

    /** [shader] (`resources/mpv/`) run by the stage on [input] at twice its size, against libplacebo's [expected]. */
    private fun checkMpv(shader: String, input: String, expected: String, max: Int, mean: Double) {
        val text = requireNotNull(javaClass.getResourceAsStream("/mpv/$shader")).use { String(it.readBytes()) }
        MpvShaders.load(mapOf(shader to text))
        val picture = mpvPicture(input)
        val theirs = mpvPicture(expected)
        val out = stage.process(picture, PictureAdjustment(shader = shader), theirs.width, theirs.height)
        val d = out.rgba.indices.filter { it % 4 != 3 }
            .map { abs((out.rgba[it].toInt() and 255) - (theirs.rgba[it].toInt() and 255)) }
        val plain = stage.process(picture, PictureAdjustment.Neutral, theirs.width, theirs.height)
        val changed = out.rgba.indices.count { out.rgba[it] != plain.rgba[it] }
        assertTrue("$shader: no different from plain scaling", changed > 1000)
        assertTrue("$shader: off by ${d.max()} at worst", d.max() <= max)
        assertTrue("$shader: off by ${d.average()} on average", d.average() <= mean)
    }

    @Test
    fun `an mpv shader hooking MAIN runs as libplacebo runs it`() =
        checkMpv("Anime4K_Upscale_CNN_x2_S.glsl", "picture.ppm", "anime4k.ppm", 2, 0.1)

    @Test
    fun `an mpv shader hooking LUMA runs as libplacebo runs it, the colour following`() =
        checkMpv("FSRCNNX_x2_8-0-4-1.glsl", "grey.ppm", "fsrcnnx.ppm", 2, 0.1)

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
    fun `a station logo is filled in from around it as the reference fills it, the rest left alone`() {
        val logo = LogoMask(LogoMask.Corner.TOP_RIGHT, 0.25f, LogoMask.Mode.BLUR)
        val out = stage.process(picture, PictureAdjustment(logo = logo), picture.width, picture.height)
        val rect = logo.rectangle(picture.width, picture.height)
        var worst = 0
        for (c in 0..2) {
            val plane = ByteArray(picture.width * picture.height) { picture.rgba[it * 4 + c] }
            val expected = LogoMask.apply(plane, picture.width, picture.height, rect, LogoMask.Mode.BLUR)
            for (i in expected.indices) {
                worst = maxOf(worst, abs((out.rgba[i * 4 + c].toInt() and 255) - (expected[i].toInt() and 255)))
            }
        }
        assertTrue("off by $worst", worst <= 2)
    }

    @Test
    fun `deblocking and deringing agree with the references, on the grid of the part shown`() {
        val part = PictureCrop(3, 5, picture.width - 3, picture.height - 5)
        val look = PictureAdjustment(deblock = 0.5f, dering = 0.6f)
        val out = stage.process(picture, look, part.width, part.height, part)
        val rgb = ByteArray(part.width * part.height * 3) { i ->
            val x = i / 3 % part.width
            val y = i / 3 / part.width
            picture.rgba[((y + part.top) * picture.width + x + part.left) * 4 + i % 3]
        }
        val (originX, originY) = Deblock.gridOrigin(part.left, part.top)
        val deblock = requireNotNull(look.deblockParams())
        val deblocked = ByteArray(rgb.size)
        for (c in 0..2) {
            val plane = ByteArray(part.width * part.height) { rgb[it * 3 + c] }
            Deblock.apply(plane, part.width, part.height, deblock, originX, originY)
                .forEachIndexed { i, v -> deblocked[i * 3 + c] = v }
        }
        val dering = requireNotNull(look.deringParams())
        val expected = Cdef.apply(deblocked, part.width, part.height, dering, originX, originY)
        var worst = 0
        for (i in 0 until part.width * part.height) for (c in 0..2) {
            worst = maxOf(worst, abs((out.rgba[i * 4 + c].toInt() and 255) - (expected[i * 3 + c].toInt() and 255)))
        }
        assertTrue("off by $worst", worst <= 1)
        assertTrue(!expected.contentEquals(rgb))
    }

    @Test
    fun `temporal denoising agrees with the reference, the pictures either side read as decoded`() {
        // Too low for the motion search's two blocks: the pictures either side are read as they are.
        val w = 48
        val h = 24
        var state = 7
        fun grainy(k: Int) = MediaReader.Picture(k * 40L, w, h, ByteArray(w * h * 4) { i ->
            state = state * 1103515245 + 12345
            if (i % 4 == 3) -1 else (100 + (i / 4 % w) + ((state ushr 16) % 7) - 3).toByte()
        })
        val frames = (0..2).map(::grainy)
        val look = PictureAdjustment(temporalDenoise = 0.5f)
        val out = stage.process(Moment(frames[1], frames[0], frames[2]), look, w, h)
        // The noise level any picture before read.
        val params = requireNotNull(look.temporalDenoiseParams(stage.noiseLevel.sigma))
        fun channel(p: MediaReader.Picture, c: Int) = ByteArray(w * h) { p.rgba[it * 4 + c] }
        var worst = 0
        var changed = 0
        for (c in 0..2) {
            val (before, current, after) = frames.map { channel(it, c) }
            val expected = TemporalDenoise.apply(before, current, after, params)
            for (i in 0 until w * h) {
                worst = maxOf(worst, abs((out.rgba[i * 4 + c].toInt() and 255) - (expected[i].toInt() and 255)))
                if (expected[i] != frames[1].rgba[i * 4 + c]) changed++
            }
        }
        assertTrue("off by $worst", worst <= 1)
        assertTrue("only $changed changed", changed > w * h / 4)
    }

    @Test
    fun `dot crawl is combed out as the reference combs it`() {
        val w = 48
        val h = 32
        // A still picture with a residue flipping sign every frame, as a composite decoder leaves it.
        fun captured(k: Int) = MediaReader.Picture(k * 40L, w, h, ByteArray(w * h * 4) { i ->
            val x = i / 4 % w
            val y = i / 4 / w
            val sign = if ((x + y + k) % 2 == 0) 1 else -1
            if (i % 4 == 3) -1 else (120 + x + sign * 6).toByte()
        })
        val frames = (0..2).map(::captured)
        val look = PictureAdjustment(dotCrawl = 0.5f)
        val out = stage.process(Moment(frames[1], frames[0], frames[2]), look, w, h)
        fun rgb(p: MediaReader.Picture) = ByteArray(w * h * 3) { p.rgba[it / 3 * 4 + it % 3] }
        val expected = DotCrawl.apply(
            rgb(frames[0]), rgb(frames[1]), rgb(frames[2]), w, h, requireNotNull(DotCrawl.threshold(look.dotCrawl)),
        )
        var worst = 0
        for (i in 0 until w * h) for (c in 0..2) {
            worst = maxOf(worst, abs((out.rgba[i * 4 + c].toInt() and 255) - (expected[i * 3 + c].toInt() and 255)))
        }
        assertTrue("off by $worst", worst <= 1)
        assertTrue(!expected.contentEquals(rgb(frames[1])))
    }

    @Test
    fun `temporal denoising moves the pictures either side along the motion, as the reference does`() {
        val w = 256
        val h = 192
        val random = java.util.Random(9)
        // The waves panning four pixels a frame, with grain of three steps.
        fun grainy(k: Int): MediaReader.Picture {
            val clean = waves(w, h, 4 * k, 0, k * 40L)
            val rgba = clean.rgba.copyOf()
            for (i in rgba.indices) {
                if (i % 4 == 3) continue
                rgba[i] = ((rgba[i].toInt() and 255) + 3.0 * random.nextGaussian()).roundToInt().coerceIn(0, 255)
                    .toByte()
            }
            return MediaReader.Picture(clean.ptsMs, w, h, rgba)
        }
        val frames = (0..2).map(::grainy)
        val look = PictureAdjustment(temporalDenoise = 0.5f)
        val moment = Moment(frames[1], frames[0], frames[2])
        stage.process(moment, look, w, h)
        val deadline = System.nanoTime() + 5_000_000_000L
        fun vectors() = listOf(
            stage.denoiseMotion.vectors(frames[0], frames[1]),
            stage.denoiseMotion.vectors(frames[1], frames[2]),
        )
        while (vectors().any { it == null } && System.nanoTime() < deadline) Thread.sleep(20)
        val (before, after) = vectors().map { requireNotNull(it) { "the search never came in" } }
        val out = stage.process(moment, look, w, h)
        val params = requireNotNull(TemporalDenoise.params(0.5f, stage.noiseLevel.sigma))
        assertTrue("noise read ${stage.noiseLevel.sigma}", abs(requireNotNull(stage.noiseLevel.sigma) - 3.0) < 1.0)
        fun channel(p: MediaReader.Picture, c: Int) = ByteArray(w * h) { p.rgba[it * 4 + c] }
        var worst = 0
        var off = 0
        var changed = 0
        for (c in 0..2) {
            val moved =
                TemporalDenoise.aligned(channel(frames[0], c), w, h, before.motion(), TemporalDenoise.Side.BEFORE)
            val ahead =
                TemporalDenoise.aligned(channel(frames[2], c), w, h, after.motion(), TemporalDenoise.Side.AFTER)
            val expected = TemporalDenoise.apply(moved, channel(frames[1], c), ahead, params)
            for (i in 0 until w * h) {
                val d = abs((out.rgba[i * 4 + c].toInt() and 255) - (expected[i].toInt() and 255))
                worst = maxOf(worst, d)
                if (d > 0) off++
                if (expected[i] != frames[1].rgba[i * 4 + c]) changed++
            }
        }
        assertTrue("off by $worst at $off values", worst <= 1)
        assertTrue("only $changed changed", changed > w * h)
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

    @Test
    fun `a test pattern goes through the stage pixel for pixel, and the household's brightness shows on it`() {
        val w = 320
        val h = 180
        for (pattern in TestPattern.entries) {
            val still = MediaReader.Picture(0, w, h, pattern.render(w, h))
            val out = stage.process(still, TestPattern.pictureFor(PictureAdjustment.Neutral), w, h)
            val worst = still.rgba.indices.filter { it % 4 != 3 }
                .maxOf { abs((still.rgba[it].toInt() and 255) - (out.rgba[it].toInt() and 255)) }
            assertTrue("$pattern off by $worst", worst <= 1)
        }
        // The levels pattern's black, lifted by the household's brightness.
        val levels = MediaReader.Picture(0, w, h, TestPattern.LEVELS.render(w, h))
        val brighter = TestPattern.pictureFor(PictureAdjustment(brightness = 0.1f))
        val lifted = stage.process(levels, brighter, w, h)
        assertTrue((lifted.rgba[0].toInt() and 255) > 8)
    }

    @Test
    fun `every presentation reads back the same picture, the lagged one a picture late`() {
        val w = 320
        val h = 180
        val first = MediaReader.Picture(0, w, h, TestPattern.BARS_HD.render(w, h))
        val second = MediaReader.Picture(40, w, h, TestPattern.LEVELS.render(w, h))
        val expected = GlStage(Presentation.SYNC).use { sync ->
            listOf(first, second).map { sync.process(it, PictureAdjustment.Neutral, w, h).rgba }
        }
        GlStage(Presentation.PBO).use { pbo ->
            assertTrue(expected[0].contentEquals(pbo.process(first, PictureAdjustment.Neutral, w, h).rgba))
            assertTrue(expected[1].contentEquals(pbo.process(second, PictureAdjustment.Neutral, w, h).rgba))
        }
        GlStage(Presentation.PBO_LAGGED).use { lagged ->
            // The first picture is waited for; after it, each call hands out the one before.
            assertTrue(expected[0].contentEquals(lagged.process(first, PictureAdjustment.Neutral, w, h).rgba))
            val behind = lagged.process(second, PictureAdjustment.Neutral, w, h)
            assertEquals(first.ptsMs, behind.ptsMs)
            assertTrue(expected[0].contentEquals(behind.rgba))
            val newest = lagged.latest()!!
            assertEquals(second.ptsMs, newest.ptsMs)
            assertTrue(expected[1].contentEquals(newest.rgba))
        }
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
            stage = GlStage(Presentation.PBO)
            expect("no OpenGL 4.3 context", stage.ready)
        }

        @AfterClass @JvmStatic fun close() {
            if (::stage.isInitialized) stage.close()
        }
    }
}
