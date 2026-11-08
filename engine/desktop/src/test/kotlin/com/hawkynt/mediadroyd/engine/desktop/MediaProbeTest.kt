package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.library.FsFile
import com.hawkynt.mediadroyd.player.ToneMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.InputStream

class MediaProbeTest {

    private fun resource(name: String): File = File(requireNotNull(javaClass.getResource(name)).toURI())

    @Test
    fun `a file's length and streams are read without decoding it`() {
        val facts = requireNotNull(MediaProbe.read(resource("/subtitled.mkv").path))
        assertEquals(2021.0, facts.durationMs.toDouble(), 50.0)
        assertEquals(
            listOf(
                MediaProbe.Stream(MediaProbe.Kind.VIDEO, "mpeg4", "Simple Profile", 64, 48, 25f),
                MediaProbe.Stream(MediaProbe.Kind.AUDIO, "aac", "LC", channels = 2),
                MediaProbe.Stream(MediaProbe.Kind.SUBTITLE, "subrip", language = "deu"),
            ),
            facts.streams,
        )
    }

    @Test
    fun `an undetermined language is none, and an HDR picture says its transfer`() {
        val clip = requireNotNull(MediaProbe.read(resource("/clip.mp4").path))
        assertEquals(listOf(null, null), clip.streams.map { it.language })
        assertEquals(ToneMap.Transfer.PQ, MediaProbe.read(resource("/hdr10.mkv").path)?.streams?.first()?.hdr)
    }

    @Test
    fun `a file on a network source is read through it`() {
        val clip = resource("/clip.mp4")
        val remote = object : FsFile {
            override val name = clip.name
            override val path = "smb://nas/media/clip.mp4"
            override val isDirectory = false
            override val parentFile: FsFile? = null
            override fun length() = clip.length()
            override fun lastModified() = 0L
            override fun listFiles(): List<FsFile>? = null
            override fun child(name: String): FsFile = error("not a folder")
            override fun open(offset: Long): InputStream = clip.inputStream().apply { skip(offset) }
        }
        assertEquals(2000.0, MediaProbe.read(remote.path, remote)!!.durationMs.toDouble(), 50.0)
    }

    @Test
    fun `what is no media file has no facts`() {
        val text = File.createTempFile("not-a-video", ".mkv").apply { writeText("hello"); deleteOnExit() }
        assertNull(MediaProbe.read(text.path))
        assertNull(MediaProbe.read(File(text.parentFile, "gone.mkv").path))
    }
}
