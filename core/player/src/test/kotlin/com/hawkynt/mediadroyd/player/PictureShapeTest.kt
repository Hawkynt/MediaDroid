package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PictureShapeTest {
    @Test
    fun `without an override a frame keeps the shape its file gives it`() {
        // PAL DVD: 720 × 576 with 16:15 pixels is 4:3, with 64:45 pixels 16:9.
        assertEquals(4f / 3f, PictureShape.aspect(720, 576, 16f / 15f, AspectOverride.AUTO), 1e-4f)
        assertEquals(16f / 9f, PictureShape.aspect(720, 576, 64f / 45f, AspectOverride.AUTO), 1e-4f)
        assertEquals(16f / 9f, PictureShape.aspect(1920, 1080, 1f, AspectOverride.AUTO), 1e-4f)
    }

    @Test
    fun `an override sets the whole frame's shape whatever the file says`() {
        // An anamorphic DVD whose flag says 4:3: forced to 16:9.
        assertEquals(16f / 9f, PictureShape.aspect(720, 576, 16f / 15f, AspectOverride.SIXTEEN_NINE), 1e-4f)
        // A 4:3 programme stretched into a 1080p frame: forced back to 4:3.
        assertEquals(4f / 3f, PictureShape.aspect(1920, 1080, 1f, AspectOverride.FOUR_THREE), 1e-4f)
        assertEquals(1.85f, PictureShape.aspect(720, 480, 8f / 9f, AspectOverride.FLAT), 1e-4f)
        assertEquals(2.35f, PictureShape.aspect(1920, 1080, 1f, AspectOverride.SCOPE), 1e-4f)
        // The pixel shape that makes it: 4:3 out of 1920 × 1080 is pixels three quarters as wide as high.
        assertEquals(0.75f, PictureShape.pixelAspect(1920, 1080, 1f, AspectOverride.FOUR_THREE), 1e-4f)
        assertEquals(64f / 45f, PictureShape.pixelAspect(720, 576, 64f / 45f, AspectOverride.AUTO), 1e-6f)
    }

    @Test
    fun `cropped bars keep the override's pixels`() {
        // A 2.35:1 film letterboxed in a 16:9 frame: three quarters of the height is picture.
        assertEquals(
            16f / 9f / 0.75f,
            PictureShape.aspect(1920, 1080, 1f, AspectOverride.SIXTEEN_NINE, shareY = 0.75f),
            1e-4f,
        )
        // Its 4:3 DVD flagged wrongly, overridden to 16:9, pillarbox bars cropped to the middle 3/4 of the width.
        assertEquals(
            4f / 3f,
            PictureShape.aspect(720, 576, 16f / 15f, AspectOverride.SIXTEEN_NINE, shareX = 0.75f),
            1e-4f,
        )
    }

    @Test
    fun `a screen seen as its pixels draws a picture as it is meant`() {
        assertEquals(1f, PictureShape.widening(16f / 9f, ScreenShape.AUTO), 0f)
        assertEquals(2.35f, PictureShape.drawn(2.35f, 16f / 9f, ScreenShape.AUTO), 0f)
        // A screen said to be the shape its pixels already are widens nothing either.
        assertEquals(1f, PictureShape.widening(16f / 9f, ScreenShape.SIXTEEN_NINE), 1e-6f)
    }

    @Test
    fun `behind an anamorphic lens a picture is drawn narrower, to be seen as meant`() {
        // A 16:9 projector behind a 1.33× lens: a scope film fills the panel's height, drawn 4/3 narrower.
        assertEquals(4f / 3f, PictureShape.widening(16f / 9f, ScreenShape.LENS_133), 1e-4f)
        assertEquals(2.35f * 3f / 4f, PictureShape.drawn(2.35f, 16f / 9f, ScreenShape.LENS_133), 1e-4f)
        // A 16:9 video there is pillarboxed on the panel, at 4:3, and seen 16:9 on the wall.
        assertEquals(4f / 3f, PictureShape.drawn(16f / 9f, 16f / 9f, ScreenShape.LENS_133), 1e-4f)
        // A 1.25× lens: 2.22:1 seen, 2.22 / 1.25 drawn.
        assertEquals(1.25f, PictureShape.widening(16f / 9f, ScreenShape.LENS_125), 1e-4f)
        // A 4:3 projector behind a 1.33× lens is a 16:9 screen: a 16:9 film fills its whole panel.
        assertEquals(4f / 3f, PictureShape.drawn(16f / 9f, 4f / 3f, ScreenShape.SIXTEEN_NINE), 1e-4f)
    }

    @Test
    fun `a screen's setup is read back by name, an unknown one as the default`() {
        assertEquals(ScreenSetup(ScreenShape.LENS_133), ScreenSetup.of("LENS_133"))
        assertEquals(ScreenSetup.Default, ScreenSetup.of("CINERAMA"))
    }

    @Test
    fun `nothing to measure is no shape at all`() {
        assertEquals(1f, PictureShape.aspect(0, 0, 1f, AspectOverride.SIXTEEN_NINE), 0f)
        assertEquals(1f, PictureShape.pixelAspect(0, 576, 1f, AspectOverride.FOUR_THREE), 0f)
        assertEquals(1f, PictureShape.widening(0f, ScreenShape.LENS_133), 0f)
    }
}
