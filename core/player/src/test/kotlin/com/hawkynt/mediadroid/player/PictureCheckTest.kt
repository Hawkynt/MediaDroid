package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureCheckTest {
    @Test
    fun `every choice of deinterlacing, upscaling and smooth motion says what it does`() {
        val descriptions = PictureAdjustment.Deinterlace.entries.map { it.description } +
            PictureAdjustment.Upscaler.entries.map { it.description } +
            PictureAdjustment.Interpolation.entries.map { it.description } +
            PictureAdjustment.Chroma.entries.map { it.description }
        descriptions.forEach { assertTrue(it, it.length >= 20) }
        assertEquals(descriptions.size, descriptions.toSet().size)
    }

    @Test
    fun `what a check is about is the costly part of a picture, not its colours`() {
        val lanczos = PictureAdjustment(upscaler = PictureAdjustment.Upscaler.LANCZOS)
        assertEquals(PictureCheck.key(lanczos), PictureCheck.key(lanczos.copy(brightness = 0.3f, gamma = 1.2f)))
        assertNotEquals(
            PictureCheck.key(lanczos),
            PictureCheck.key(lanczos.copy(upscaler = PictureAdjustment.Upscaler.FSR)),
        )
        assertNotEquals(
            PictureCheck.key(lanczos),
            PictureCheck.key(lanczos.copy(interpolation = PictureAdjustment.Interpolation.MOTION)),
        )
        // A network's model and its eight-way run cost differently: each is a check of its own.
        val waifu2x = PictureAdjustment(upscaler = PictureAdjustment.Upscaler.WAIFU2X)
        assertNotEquals(PictureCheck.key(waifu2x), PictureCheck.key(waifu2x.copy(upscalerModel = "noise3")))
        assertNotEquals(PictureCheck.key(waifu2x), PictureCheck.key(waifu2x.copy(upscalerTta = true)))
        assertTrue(PictureCheck.needed(waifu2x, waifu2x.copy(upscalerTta = true)))
        assertEquals(PictureCheck.key(lanczos), PictureCheck.key(lanczos.copy(upscalerModel = "noise3")))
    }

    @Test
    fun `turning a row back to its plainest choice needs no check, anything else does`() {
        val busy = PictureAdjustment(
            upscaler = PictureAdjustment.Upscaler.FSR,
            interpolation = PictureAdjustment.Interpolation.MOTION,
            deinterlace = PictureAdjustment.Deinterlace.BWDIF,
        )
        assertFalse(PictureCheck.needed(busy, busy.copy(upscaler = PictureAdjustment.Upscaler.STANDARD)))
        assertFalse(PictureCheck.needed(busy, busy.copy(interpolation = PictureAdjustment.Interpolation.OFF)))
        assertFalse(PictureCheck.needed(busy, busy.copy(deinterlace = PictureAdjustment.Deinterlace.OFF)))
        assertTrue(PictureCheck.needed(busy, busy.copy(upscaler = PictureAdjustment.Upscaler.LANCZOS)))
        assertTrue(PictureCheck.needed(busy, busy.copy(fieldRate = true)))
        assertFalse(PictureCheck.needed(busy, busy.copy(sharpness = 0.5f)))
        val krig = busy.copy(chroma = PictureAdjustment.Chroma.KRIG)
        assertTrue(PictureCheck.needed(busy, krig))
        assertTrue(PictureCheck.needed(krig, krig.copy(chroma = PictureAdjustment.Chroma.SPLINE36)))
        assertFalse(PictureCheck.needed(krig, busy))
    }

    @Test
    fun `a dry run keeps up with hardly anything late and the stage's work well within a frame`() {
        // 576p at 25 frames a second: a frame every 40 ms; 80 % of it is 32 ms.
        val frameMs = 40.0
        assertTrue(PictureCheck.keepsUp(PictureCheck.DryRun(frames = 40, late = 0, workMs = 20.0), frameMs))
        // Motion's search for a pair may come late while it settles on its scale: that pair is blended, no stutter —
        // up to one frame in ten; more is a search that cannot keep up.
        assertTrue(PictureCheck.keepsUp(PictureCheck.DryRun(frames = 30, late = 3, workMs = 20.0), frameMs))
        assertFalse(PictureCheck.keepsUp(PictureCheck.DryRun(frames = 30, late = 4, workMs = 20.0), frameMs))
        assertFalse(PictureCheck.keepsUp(PictureCheck.DryRun(frames = 40, late = 0, workMs = 33.0), frameMs))
        assertFalse(PictureCheck.keepsUp(PictureCheck.DryRun(frames = 0, late = 0, workMs = 1.0), frameMs))
    }
}
