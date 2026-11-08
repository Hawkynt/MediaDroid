package com.hawkynt.mediadroyd.stage

import com.hawkynt.mediadroyd.player.PictureAdjustment
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class StageCheckTest {

    @Test
    fun `the test pictures and their planes are the ones both dry runs fed before they were shared`() {
        // Captured from the desktop's StageDryRun (testFrames, planesOf) before this code moved here; Android's
        // PictureChecker drew the same blotches and planes.
        val md = MessageDigest.getInstance("SHA-256")
        for (k in 0 until StageCheck.PICTURES) {
            val rgba = StageCheck.picture(k)
            md.update(rgba)
            md.update(StageCheck.planes(rgba))
        }
        assertEquals(
            "e0ffaefd585ec78e7b91e9874ff811d269bb0bb3e1156364d75b5a60575d8e4e",
            md.digest().joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `the window asks for a picture once, both fields at field rate, every refresh while mixing`() {
        assertEquals(listOf(0.0), StageCheck.phases(PictureAdjustment.Neutral, 40.0, 16.0))
        val fields = PictureAdjustment(deinterlace = PictureAdjustment.Deinterlace.YADIF, fieldRate = true)
        assertEquals(listOf(0.0, 0.5), StageCheck.phases(fields, 40.0, 16.0))
        val mixing = PictureAdjustment(interpolation = PictureAdjustment.Interpolation.HIGH)
        assertEquals(listOf(0.0, 0.4, 0.8), StageCheck.phases(mixing, 40.0, 16.0))
    }
}
