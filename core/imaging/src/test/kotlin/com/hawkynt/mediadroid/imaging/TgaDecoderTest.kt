package com.hawkynt.mediadroid.imaging

import com.hawkynt.mediadroid.imaging.Oracle.assertSamePicture
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TGA against two oracles: GIMP wrote the `200x32_*` files together with the PNG each one was saved
 * from; Pillow decoded the remaining real-world files into the `*.expected.png` beside them.
 */
class TgaDecoderTest {

    private fun check(tga: String, expected: String) =
        assertSamePicture(tga, Oracle.png("tga/$expected"), TgaDecoder.decode(Oracle.bytes("tga/$tga")))

    @Test
    fun greyscaleRawAndRunLengthFromBothOrigins() {
        check("200x32_l_bl_raw.tga", "200x32_l.png")
        check("200x32_l_tl_rle.tga", "200x32_l.png")
    }

    @Test
    fun greyscaleWithAlpha() = check("200x32_la_tl_rle.tga", "200x32_la.png")

    @Test
    fun colourMapped() {
        check("200x32_p_bl_rle.tga", "200x32_p.png")
        check("p_16.tga", "p_16.expected.png")
    }

    @Test
    fun colourMapWhoseFirstEntryIsNotZero() =
        check("200x32_p_bl_raw_origin.tga", "200x32_p_bl_raw_origin.expected.png")

    @Test
    fun trueColour() {
        check("200x32_rgb_bl_raw.tga", "200x32_rgb.png")
        check("200x32_rgb_tl_rle.tga", "200x32_rgb.png")
    }

    @Test
    fun trueColourWithAlpha() {
        check("200x32_rgba_bl_raw.tga", "200x32_rgba.png")
        check("200x32_rgba_tl_rle.tga", "200x32_rgba.png")
    }

    @Test
    fun sixteenBitColour() = check("rgba16.tga", "rgba16.expected.png")

    @Test
    fun rightToLeftOrigin() = check("rgb32rle_top_right.tga", "rgb32rle_top_right.expected.png")

    @Test
    fun imageIdFieldIsSkipped() = check("tga_id_field.tga", "tga_id_field.expected.png")

    @Test
    fun notATgaIsNothing() {
        assertNull(TgaDecoder.decode(ByteArray(0)))
        assertNull(TgaDecoder.decode("<html>not a picture</html>".toByteArray()))
    }
}
