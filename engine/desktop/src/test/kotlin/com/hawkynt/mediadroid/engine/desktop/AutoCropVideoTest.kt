package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.player.AutoCrop
import com.hawkynt.mediadroid.player.PictureCrop
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Black bars found in a real coded video, as the desktop player samples it: `letterboxed.mp4` is FFmpeg's test pattern,
 * 192 × 80, padded to 192 × 108 with video black (rows 14 to 93 the picture), coded as MPEG-4 Part 2 at a middling
 * quality, so the bars carry the coder's noise.
 */
class AutoCropVideoTest {

    @Test
    fun `the decoded pictures' bars are cropped, and nothing of the picture`() {
        val clip = File(requireNotNull(javaClass.getResource("/letterboxed.mp4")).toURI()).path
        val crop = AutoCrop()
        MediaReader(clip).use { r ->
            generateSequence { r.read() }.filterIsInstance<MediaReader.Picture>().forEach { p ->
                crop.add(AutoCrop.luma(p.rgba, p.width, p.height, 1, AutoCrop.sampleSize(p.width, p.height)))
            }
        }
        assertEquals(PictureCrop(0, 14, 192, 80), crop.crop(192, 108))
    }
}
