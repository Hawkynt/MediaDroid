package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.player.PictureAdjustment
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs

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
    fun `xBRZ leaves a flat picture exactly flat`() {
        val flat = MediaReader.Picture(0, 32, 24, ByteArray(32 * 24 * 4) { i -> if (i % 4 == 3) -1 else 0x5a })
        val out = stage.process(flat, PictureAdjustment(upscaler = PictureAdjustment.Upscaler.XBRZ), 64, 48)
        assertTrue(out.rgba.indices.filter { it % 4 != 3 }.all { out.rgba[it] == 0x5a.toByte() })
    }

    companion object {
        private lateinit var stage: GlStage

        @BeforeClass @JvmStatic fun open() {
            assumeTrue("no display", System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null)
            stage = GlStage()
            assumeTrue("no OpenGL 4.3 context", stage.ready)
        }

        @AfterClass @JvmStatic fun close() {
            if (::stage.isInitialized) stage.close()
        }
    }
}
