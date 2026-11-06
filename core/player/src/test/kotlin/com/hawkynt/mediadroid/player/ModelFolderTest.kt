package com.hawkynt.mediadroid.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ModelFolderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val content = mapOf("net.param" to "7767517\n".toByteArray(), "net.bin" to ByteArray(4096) { it.toByte() })

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val download = NeuralModels.Download(
        "net",
        content.map { (name, bytes) -> NeuralModels.File(name, bytes.size.toLong(), sha(bytes)) },
    )

    @Test
    fun `carried files are unpacked once and kept only when their digests match`() {
        val dir = File(folder.root, "net")
        assertFalse(ModelFolder.ready(dir, download))
        val opened = ArrayList<String>()
        assertTrue(ModelFolder.unpack(dir, download) { name -> opened += name; content[name]?.inputStream() })
        assertTrue(ModelFolder.ready(dir, download))
        assertArrayEquals(content["net.bin"], File(dir, "net.bin").readBytes())
        // There already and right: nothing is opened again.
        opened.clear()
        assertTrue(ModelFolder.unpack(dir, download) { name -> opened += name; content[name]?.inputStream() })
        assertTrue(opened.isEmpty())
        // A file damaged on disk is put back.
        File(dir, "net.bin").writeBytes(ByteArray(4096))
        assertTrue(ModelFolder.unpack(dir, download) { name -> content[name]?.inputStream() })
        assertArrayEquals(content["net.bin"], File(dir, "net.bin").readBytes())
    }

    @Test
    fun `a file that does not match is never kept, nor one that is missing`() {
        val dir = File(folder.root, "net")
        assertFalse(ModelFolder.unpack(dir, download) { name -> ByteArray(content.getValue(name).size).inputStream() })
        assertFalse(File(dir, "net.param").exists())
        assertFalse(ModelFolder.unpack(dir, download) { null })
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `a fetch says how far it got and takes every file from the repository's place for it`() {
        val dir = File(folder.root, "net")
        val urls = ArrayList<String>()
        val said = ArrayList<Float>()
        val ok = ModelFolder.fetch(dir, download, { said += it }) { url ->
            urls += url
            content.getValue(url.substringAfterLast('/')).inputStream()
        }
        assertTrue(ok)
        assertEquals(download.files.map { "${NeuralModels.REPOSITORY}/net/${it.name}" }, urls)
        assertEquals(1f, said.last())
        assertTrue(ModelFolder.ready(dir, download))
    }
}
