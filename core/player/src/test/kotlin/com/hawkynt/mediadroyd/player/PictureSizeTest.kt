package com.hawkynt.mediadroyd.player

import com.hawkynt.mediadroyd.player.PictureAdjustment.Stereo
import org.junit.Assert.assertEquals
import org.junit.Test

class PictureSizeTest {
    @Test
    fun `the 3D eye is its half of the frame, pixel for pixel`() {
        assertEquals(PictureSize(960, 1080), PictureSize(1920, 1080).eye(Stereo.LEFT))
        assertEquals(PictureSize(960, 1080), PictureSize(1920, 1080).eye(Stereo.RIGHT))
        assertEquals(PictureSize(1920, 540), PictureSize(1920, 1080).eye(Stereo.TOP))
        assertEquals(PictureSize(1920, 540), PictureSize(1920, 1080).eye(Stereo.BOTTOM))
        assertEquals(PictureSize(1920, 1080), PictureSize(1920, 1080).eye(Stereo.OFF))
    }

    @Test
    fun `a picture larger than 1080p is brought down to fit, keeping its shape`() {
        assertEquals(PictureSize(1920, 1080), PictureSize(3840, 2160).reduced(Downscaler.BILINEAR))
        assertEquals(PictureSize(1920, 804), PictureSize(4096, 1716).reduced(Downscaler.LANCZOS))
        // Taller than wide for its size: the height decides.
        assertEquals(PictureSize(960, 1080), PictureSize(1920, 2160).reduced(Downscaler.AREA))
    }

    @Test
    fun `a picture within 1080p, or with the downscaler off, keeps its size`() {
        assertEquals(PictureSize(1920, 1080), PictureSize(1920, 1080).reduced(Downscaler.BILINEAR))
        assertEquals(PictureSize(720, 576), PictureSize(720, 576).reduced(Downscaler.NEAREST))
        assertEquals(PictureSize(3840, 2160), PictureSize(3840, 2160).reduced(Downscaler.OFF))
    }

    @Test
    fun `a 4K side-by-side eye is half the frame wide, then fitted`() {
        assertEquals(PictureSize(960, 1080), PictureSize(3840, 2160).eye(Stereo.LEFT).reduced(Downscaler.BILINEAR))
    }

    @Test
    fun `bilinear is the default and every method has a name`() {
        assertEquals(Downscaler.BILINEAR, Downscaler.DEFAULT)
        assertEquals(
            listOf("Off", "Nearest", "Bilinear", "Bicubic", "Lanczos", "Area"),
            Downscaler.entries.map { it.label },
        )
    }
}
