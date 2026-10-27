package com.hawkynt.mediadroid.imaging

import com.hawkynt.mediadroid.imaging.Oracle.assertSamePicture
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TIFF against the JDK's own TIFF codec: every picture is written by `javax.imageio` in each layout
 * and compression a scanner or editor produces, and decoding it must give back what was written.
 */
class TiffDecoderTest {

    private val compressions = listOf(null, "LZW", "PackBits", "Deflate", "ZLib")

    /** A picture with smooth gradients, hard edges and a deterministic speckle, so every codec path works. */
    private fun picture(type: Int, width: Int = 37, height: Int = 23): BufferedImage {
        val image = BufferedImage(width, height, type)
        var seed = 12345
        for (y in 0 until height) {
            for (x in 0 until width) {
                seed = seed * 1103515245 + 12345
                val noise = (seed ushr 16) and 0x1F
                val r = (x * 255 / (width - 1)) xor noise
                val g = (y * 255 / (height - 1))
                val b = if ((x / 4 + y / 4) % 2 == 0) 0xFF else 0x10
                val a = if (type == BufferedImage.TYPE_INT_ARGB) (x * 7 + y * 3) and 0xFF else 0xFF
                image.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        return image
    }

    private fun paletted(): BufferedImage {
        val reds = ByteArray(16) { (it * 17).toByte() }
        val greens = ByteArray(16) { (255 - it * 17).toByte() }
        val blues = ByteArray(16) { (it * 40 % 256).toByte() }
        val model = IndexColorModel(4, 16, reds, greens, blues)
        val image = BufferedImage(29, 13, BufferedImage.TYPE_BYTE_BINARY, model)
        for (y in 0 until 13) for (x in 0 until 29) image.raster.setSample(x, y, 0, (x + y * 3) % 16)
        return image
    }

    private fun tiff(
        image: BufferedImage,
        compression: String?,
        order: ByteOrder = ByteOrder.BIG_ENDIAN,
        tile: Int? = null,
    ): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("tiff").next()
        val out = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(out).use { stream ->
            stream.byteOrder = order
            writer.output = stream
            val param = writer.defaultWriteParam
            if (compression != null) {
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                param.compressionType = compression
            }
            if (tile != null) {
                param.tilingMode = ImageWriteParam.MODE_EXPLICIT
                param.setTiling(tile, tile, 0, 0)
            }
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    private fun roundTrip(label: String, image: BufferedImage) {
        for (compression in compressions) {
            for (order in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
                val name = "$label/${compression ?: "none"}/$order"
                assertSamePicture(name, image, TiffDecoder.decode(tiff(image, compression, order)))
            }
        }
    }

    @Test
    fun rgb() = roundTrip("rgb", picture(BufferedImage.TYPE_INT_RGB))

    @Test
    fun rgbWithAlpha() = roundTrip("argb", picture(BufferedImage.TYPE_INT_ARGB))

    @Test
    fun greyscale() = roundTrip("grey", picture(BufferedImage.TYPE_BYTE_GRAY))

    @Test
    fun sixteenBitGreyscale() = roundTrip("grey16", picture(BufferedImage.TYPE_USHORT_GRAY))

    @Test
    fun blackAndWhite() = roundTrip("bilevel", picture(BufferedImage.TYPE_BYTE_BINARY))

    @Test
    fun palette() = roundTrip("palette", paletted())

    @Test
    fun tiles() {
        val image = picture(BufferedImage.TYPE_INT_RGB, width = 50, height = 40)
        for (compression in compressions) {
            assertSamePicture("tiled/$compression", image, TiffDecoder.decode(tiff(image, compression, tile = 16)))
        }
    }

    @Test
    fun horizontalDifferencingPredictor() {
        // 3x1 RGB, uncompressed, Predictor 2: every sample after the first pixel is a delta to its left.
        val pixels = byteArrayOf(10, 20, 30, 5, 5, 5, -3, 0, 100)
        val tiff = littleEndianTiff(
            width = 3,
            height = 1,
            entries = listOf(
                Triple(258, 3, intArrayOf(8, 8, 8)),
                Triple(262, 3, intArrayOf(2)),
                Triple(277, 3, intArrayOf(3)),
                Triple(317, 3, intArrayOf(2)),
            ),
            data = pixels,
        )
        val decoded = TiffDecoder.decode(tiff)!!
        assertArrayEquals(
            intArrayOf(0xFF0A141E.toInt(), 0xFF0F1923.toInt(), 0xFF0C1987.toInt()),
            decoded.argb,
        )
    }

    @Test
    fun aLargePictureIsSampledDownToTheBudget() {
        val image = picture(BufferedImage.TYPE_INT_RGB, width = 64, height = 48)
        val decoded = TiffDecoder.decode(tiff(image, "LZW"), maxPixels = 16 * 12)!!
        assertEquals(16, decoded.width)
        assertEquals(12, decoded.height)
        assertTrue(decoded.sampled)
        assertEquals(image.getRGB(4, 8), decoded.argb[2 * 16 + 1])
    }

    @Test
    fun notATiffIsNothing() {
        assertNull(TiffDecoder.decode(ByteArray(0)))
        assertNull(TiffDecoder.decode("II*\u0000garbage".toByteArray()))
    }

    /** A one-strip little-endian TIFF with the given extra entries, built by hand for cases ImageIO cannot write. */
    private fun littleEndianTiff(
        width: Int,
        height: Int,
        entries: List<Triple<Int, Int, IntArray>>,
        data: ByteArray,
    ): ByteArray {
        val all = (
            entries + listOf(
                Triple(256, 3, intArrayOf(width)),
                Triple(257, 3, intArrayOf(height)),
                Triple(273, 4, intArrayOf(0)),
                Triple(279, 4, intArrayOf(data.size)),
            )
            ).sortedBy { it.first }
        val ifdSize = 2 + all.size * 12 + 4
        val extraStart = 8 + ifdSize
        val extra = ByteArrayOutputStream()
        val ifd = java.nio.ByteBuffer.allocate(ifdSize).order(ByteOrder.LITTLE_ENDIAN)
        ifd.putShort(all.size.toShort())
        var dataOffset = 0
        val patches = ArrayList<Int>()
        for ((tag, type, values) in all) {
            ifd.putShort(tag.toShort()).putShort(type.toShort()).putInt(values.size)
            val size = if (type == 3) 2 else 4
            if (values.size * size <= 4) {
                val inline = java.nio.ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                values.forEach { if (size == 2) inline.putShort(it.toShort()) else inline.putInt(it) }
                if (tag == 273) patches.add(ifd.position())
                ifd.put(inline.array())
            } else {
                ifd.putInt(extraStart + extra.size())
                values.forEach {
                    if (size == 2) {
                        extra.write(it and 0xFF)
                        extra.write(it shr 8)
                    } else {
                        repeat(4) { k -> extra.write((it shr (8 * k)) and 0xFF) }
                    }
                }
            }
        }
        ifd.putInt(0)
        dataOffset = extraStart + extra.size()
        patches.forEach { ifd.putInt(it, dataOffset) }
        val header = byteArrayOf(0x49, 0x49, 42, 0, 8, 0, 0, 0)
        return header + ifd.array() + extra.toByteArray() + data
    }
}
