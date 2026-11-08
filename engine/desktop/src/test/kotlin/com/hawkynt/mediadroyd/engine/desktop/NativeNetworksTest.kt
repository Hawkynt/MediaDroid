package com.hawkynt.mediadroyd.engine.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The desktop library built from native/ loads and runs a network from its files: one 1 × 1 convolution, out = 2·in +
 * 0.5, written as ncnn's .param and .bin. It needs the library built for this system, so `./gradlew test` leaves it
 * out and `-PnativeLibrary` brings it in (_desktop.yml, on Linux and Windows).
 */
class NativeNetworksTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun tinyNetwork(dir: File) {
        File(dir, "tiny.param").writeText(
            """
            7767517
            2 2
            Input in0 0 1 in0 0=4 1=4 2=1
            Convolution conv 1 1 in0 out0 0=1 1=1 5=1 6=1
            """.trimIndent() + "\n",
        )
        // The weights behind a 0 tag (raw floats), then the bias, as ncnn's ModelBinFromDataReader reads them.
        val bin = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putFloat(2f).putFloat(0.5f)
        File(dir, "tiny.bin").writeBytes(bin.array())
    }

    @Test
    fun `the library loads and runs a network read from files`() {
        assertTrue(
            "the native library (${System.getProperty(NativeNetworks.FOLDER_PROPERTY)}) did not load",
            NativeNetworks.loaded,
        )
        val dir = folder.root
        tinyNetwork(dir)
        val input = FloatArray(16) { it.toFloat() }
        val output = NativeNetworks.run(dir.path, "tiny", input, 4, 4, 1)
        assertArrayEquals(FloatArray(16) { 2f * it + 0.5f }, output, 1e-6f)
    }

    @Test
    fun `a network that is not there gives nothing`() {
        assertTrue(NativeNetworks.loaded)
        assertNull(NativeNetworks.run(folder.root.path, "missing", FloatArray(16), 4, 4, 1))
    }
}
