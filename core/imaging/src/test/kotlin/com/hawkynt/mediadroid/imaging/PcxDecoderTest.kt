package com.hawkynt.mediadroid.imaging

import com.hawkynt.mediadroid.imaging.Oracle.assertSamePicture
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PCX against Pillow, which decoded every file here into the `*.expected.png` beside it
 * (`src/test/make-expected.py`). The `hopper_*` files are Pillow's own encodes of one picture in each mode it
 * writes; the others are real-world files from Pillow's test suite.
 */
class PcxDecoderTest {

    private fun check(name: String) =
        assertSamePicture(name, Oracle.png("pcx/$name.expected.png"), PcxDecoder.decode(Oracle.bytes("pcx/$name.pcx")))

    @Test
    fun blackAndWhite() = check("hopper_1")

    @Test
    fun greyscale() = check("hopper_l")

    @Test
    fun palettedWithTheTrailingPalette() = check("hopper_p")

    @Test
    fun rgbAsThreePlanes() = check("hopper_rgb")

    @Test
    fun sixteenColoursAsFourBitPlanes() = check("p_4_planes")

    @Test
    fun windowThatDoesNotStartAtZero() = check("pil184")

    @Test
    fun oddStrideWrittenByAMalformedEncoder() = check("odd_stride")

    @Test
    fun notAPcxIsNothing() {
        assertNull(PcxDecoder.decode(ByteArray(0)))
        assertNull(PcxDecoder.decode(ByteArray(128)))
    }
}
