package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssFrameTest {
    @Test
    fun `a video shown whole is laid out on itself`() {
        val frame = AssFrame.of(0, 0, 1920, 1080, 0f, 0f, 1920f, 1080f)!!
        assertEquals(AssFrame(0, 0, 1920, 1080), frame)
        assertFalse(frame.partial)
    }

    @Test
    fun `letterboxed in the view the frame is the video, not the bars around it`() {
        // A 16:9 video fitted into a 2400 x 1080 phone screen: 1920 wide, centred.
        assertEquals(AssFrame(240, 0, 1920, 1080), AssFrame.of(0, 0, 2400, 1080, 240f, 0f, 2160f, 1080f))
    }

    @Test
    fun `Fill keeps the frame to the screen and the video reaching past it`() {
        // The 16:9 video filling the phone's 2400 x 1080: 2400 x 1350, 135 above and below the screen.
        val frame = AssFrame.of(0, 0, 2400, 1080, 0f, -135f, 2400f, 1215f)!!
        assertEquals(AssFrame(0, 0, 2400, 1080, marginTop = -135, marginBottom = -135), frame)
        assertTrue(frame.partial)
    }

    @Test
    fun `a view that is itself larger than the screen is cut to what shows`() {
        // The view is the video (Fill) and runs from -135 to 1215; only 135 to 1215 of it shows.
        assertEquals(
            AssFrame(0, 135, 2400, 1080, marginTop = -135, marginBottom = -135),
            AssFrame.of(0, 135, 2400, 1215, 0f, 0f, 2400f, 1350f),
        )
        assertNull(AssFrame.of(0, 0, 100, 100, 200f, 200f, 300f, 300f))
    }

    @Test
    fun `cropped black bars put the whole video around the picture shown`() {
        // A 1920 x 1080 frame holding a 2.39:1 film between bars of 138 rows, cropped to 1920 x 804, shown 2400 wide.
        val frame = AssFrame.cropped(2400, 1005, PictureCrop(0, 138, 1920, 804), 1920, 1080)
        assertEquals(AssFrame(0, 0, 2400, 1005, marginTop = -172, marginBottom = -173), frame)
        assertEquals(AssFrame(0, 0, 640, 360), AssFrame.cropped(640, 360, null, 1920, 1080))
        assertEquals(AssFrame(0, 0, 640, 360), AssFrame.cropped(640, 360, PictureCrop(0, 0, 1920, 1080), 1920, 1080))
    }

    @Test
    fun `a picture drawn on part of the screen lays out on that part`() {
        // A projector's keystone: the stage draws the whole 1000 x 1000 screen, the picture centred at 800 x 450.
        assertEquals(
            AssFrame(100, 275, 800, 450),
            AssFrame.shown(1000, 1000, 0, 0, 1000, 1000, null, 1920, 1080, 100, 275, 800, 450),
        )
        // Its black bars cropped: the whole video reaches past the picture's place.
        val crop = PictureCrop(0, 138, 1920, 804)
        val frame = AssFrame.shown(1000, 1000, 0, 0, 1000, 1000, crop, 1920, 1080, 100, 275, 800, 450)!!
        assertEquals(AssFrame(100, 275, 800, 450, marginTop = -77, marginBottom = -77), frame)
    }

    @Test
    fun `a cropped picture in Fill is cut to the screen as well`() {
        // The cropped 1920 x 804 film filling a 1000 x 1000 view of which only columns 100 to 900 show.
        val frame = AssFrame.shown(1000, 1000, 100, 0, 900, 1000, PictureCrop(0, 138, 1920, 804), 1920, 1080)!!
        assertEquals(AssFrame(100, 0, 800, 1000, marginLeft = -100, marginRight = -100,
            marginTop = -172, marginBottom = -172), frame)
        assertEquals(
            AssFrame(100, 0, 800, 1000, marginLeft = -100, marginRight = -100),
            AssFrame.shown(1000, 1000, 100, 0, 900, 1000, null, 0, 0),
        )
    }
}
