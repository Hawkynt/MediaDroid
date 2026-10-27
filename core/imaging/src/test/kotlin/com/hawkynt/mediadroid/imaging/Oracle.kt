package com.hawkynt.mediadroid.imaging

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail

/** Test resources and the JDK's own image codecs, used as the oracle the decoders are held to. */
object Oracle {

    fun bytes(resource: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/$resource")) { "missing $resource" }.use { it.readBytes() }

    fun png(resource: String): BufferedImage = ImageIO.read(bytes(resource).inputStream())

    /**
     * The picture as non-premultiplied ARGB, the way [RasterImage] holds it. Grey pictures are read
     * from their raw samples: ImageIO puts them in a linear grey space and `getRGB` would gamma-convert
     * them, while every file format here means the stored grey value as is.
     */
    fun argb(image: BufferedImage): IntArray {
        val model = image.colorModel
        if (model.colorSpace.type != java.awt.color.ColorSpace.TYPE_GRAY || model is java.awt.image.IndexColorModel) {
            return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        }
        val raster = image.raster
        val shift = model.getComponentSize(0) - 8
        return IntArray(image.width * image.height) { i ->
            val x = i % image.width
            val y = i / image.width
            val v = raster.getSample(x, y, 0) shr shift
            val a = if (raster.numBands > 1) raster.getSample(x, y, 1) shr shift else 0xFF
            (a shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    /**
     * [actual] shows the same picture as [expected]. Fully transparent pixels only have to agree on
     * being transparent: their colour is invisible and encoders store anything there.
     */
    fun assertSamePicture(name: String, expected: BufferedImage, actual: RasterImage?) {
        assertNotNull("$name was not decoded", actual)
        actual!!
        assertEquals("$name width", expected.width, actual.width)
        assertEquals("$name height", expected.height, actual.height)
        val want = argb(expected)
        for (i in want.indices) {
            val a = want[i]
            val b = actual.argb[i]
            val same = a == b || (a ushr 24 == 0 && b ushr 24 == 0)
            if (!same) {
                fail(
                    "$name pixel (${i % actual.width}, ${i / actual.width}): " +
                        "expected %08x but was %08x".format(a, b),
                )
            }
        }
    }
}
