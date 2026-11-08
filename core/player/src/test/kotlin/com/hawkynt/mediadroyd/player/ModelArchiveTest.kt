package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelArchiveTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val content = mapOf("net.param" to "7767517\n".toByteArray(), "net.bin" to ByteArray(4096) { it.toByte() })

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("not a model".toByteArray())
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
            }
        }
    }.toByteArray()

    private val archive = zip(content.mapKeys { "port/models/${it.key}" })

    private val download = NeuralModels.Download(
        "net",
        content.map { (name, bytes) -> NeuralModels.File(name, bytes.size.toLong(), sha(bytes)) },
        "https://example.org/port.zip",
        archive = NeuralModels.Archive(archive.size.toLong(), "port/models/"),
    )

    @Test
    fun `a network in an upstream release archive is fetched as the archive, its files taken out and checked`() {
        val dir = File(folder.root, "net")
        val opened = ArrayList<String>()
        var last = 0f
        assertTrue(ModelFolder.fetch(dir, download, { last = it }) { url -> opened += url; archive.inputStream() })
        assertEquals(listOf("https://example.org/port.zip"), opened)
        assertEquals(1f, last)
        assertTrue(ModelFolder.ready(dir, download))
        assertArrayEquals(content["net.bin"], File(dir, "net.bin").readBytes())
        assertFalse(File(dir, "readme.txt").exists())
        // There already: the archive is not fetched again.
        opened.clear()
        assertTrue(ModelFolder.fetch(dir, download, {}) { url -> opened += url; archive.inputStream() })
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `an archive without a file, or with one that does not match, keeps nothing`() {
        val dir = File(folder.root, "net")
        val missing = zip(mapOf("port/models/net.param" to content.getValue("net.param")))
        assertFalse(ModelFolder.fetch(dir, download, {}) { missing.inputStream() })
        val wrong = zip(content.mapKeys { "port/models/${it.key}" }.mapValues { ByteArray(it.value.size) })
        assertFalse(ModelFolder.fetch(dir, download, {}) { wrong.inputStream() })
        assertFalse(File(dir, "net.bin").exists())
        assertFalse(File(dir, "net.param").exists())
    }

    @Test
    fun `Real-ESRGAN's general x4plus model comes out of the port's release, the desktop's second Real-ESRGAN`() {
        val x4plus = NeuralModels.REALESRGAN_X4PLUS
        assertEquals(listOf("realesrgan-x4plus.param", "realesrgan-x4plus.bin"), x4plus.files.map { it.name })
        assertEquals(
            "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/" +
                "realesrgan-ncnn-vulkan-20220424-ubuntu.zip",
            x4plus.source,
        )
        assertEquals(46_931_474L, x4plus.archive?.bytes)
        val models = NeuralEnlarging.models(PictureAdjustment.Upscaler.NEURAL)
        assertEquals(listOf("Anime video", "General"), models.map { it.label })
        val general = models.last()
        assertEquals("realesrgan-x4plus", general.file)
        assertEquals(4, general.scale)
        assertEquals("NEURAL:x4plus", NeuralEnlarging.Choice(general).key)
    }
}
