package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.engine.Track
import com.hawkynt.mediadroid.library.FsFile
import com.hawkynt.mediadroid.player.ToneMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/** The test clip: 64 × 48, 25 fps, 2 s of FFmpeg's test pattern, a 440 Hz tone in 48 kHz stereo AAC. */
class MediaReaderTest {

    private val clip: String = File(requireNotNull(javaClass.getResource("/clip.mp4")).toURI()).path

    @Test
    fun `a file's length, picture size and tracks are known once it is open`() = MediaReader(clip).use { r ->
        assertEquals(2000.0, r.durationMs.toDouble(), 50.0)
        assertEquals(64, r.width)
        assertEquals(48, r.height)
        assertEquals(listOf(Track.Kind.VIDEO, Track.Kind.AUDIO), r.tracks.map { it.kind })
    }

    @Test
    fun `every picture comes out once, in order, 40 ms apart`() = MediaReader(clip).use { r ->
        val pictures = generateSequence { r.read() }.filterIsInstance<MediaReader.Picture>().toList()
        assertEquals(50, pictures.size)
        pictures.forEachIndexed { i, p -> assertEquals(i * 40L, p.ptsMs) }
        assertEquals(64 * 48 * 4, pictures.first().rgba.size)
    }

    @Test
    fun `the sound comes out as 48 kHz stereo, as long as the file`() = MediaReader(clip).use { r ->
        val sound = generateSequence { r.read() }.filterIsInstance<MediaReader.Sound>().toList()
        val frames = sound.sumOf { it.pcm.size / 4 }
        assertEquals(96_000.0, frames.toDouble(), 4_800.0)
        assertTrue(sound.zipWithNext().all { (a, b) -> b.ptsMs >= a.ptsMs })
    }

    @Test
    fun `after a seek the next picture is the one at that time, not the key frame before`() =
        MediaReader(clip).use { r ->
            r.seek(1000)
            val first = generateSequence { r.read() }.filterIsInstance<MediaReader.Picture>().first()
            assertEquals(1000L, first.ptsMs)
        }

    @Test
    fun `the frame rate comes from the file`() = MediaReader(clip).use { r ->
        assertEquals(25f, r.frameRate, 0.001f)
    }

    @Test
    fun `the shape of a picture's pixels comes from the file, square without one`() {
        MediaReader(clip).use { assertEquals(1f, it.pixelAspect, 0.001f) }
        val anamorphic = File(requireNotNull(javaClass.getResource("/anamorphic.mp4")).toURI()).path
        MediaReader(anamorphic).use { assertEquals(16f / 15f, it.pixelAspect, 0.001f) }
    }

    @Test
    fun `an HDR10 video's pictures keep their coded values at 16 bits, for the stage to tone-map`() {
        MediaReader(clip).use { assertNull(it.hdr) }
        // hdr10.mkv: FFmpeg's test pattern, 64 × 48, 10-bit BT.2020, tagged PQ (FFV1, so any FFmpeg decodes it).
        val hdr10 = File(requireNotNull(javaClass.getResource("/hdr10.mkv")).toURI()).path
        MediaReader(hdr10).use { r ->
            assertEquals(ToneMap.Transfer.PQ, r.hdr)
            val picture = generateSequence { r.read() }.filterIsInstance<MediaReader.Picture>().first()
            assertEquals(ToneMap.Transfer.PQ, picture.hdr)
            assertEquals(64 * 48 * 8, picture.rgba.size)
            // Limited range brought to full: the pattern's white comes out near the top of the 16 bits.
            val values = (0 until 64 * 48 * 4).map {
                (picture.rgba[2 * it].toInt() and 255) or ((picture.rgba[2 * it + 1].toInt() and 255) shl 8)
            }
            assertTrue(values.max() > 60000)
            assertEquals(64 * 48 * 4, picture.eightBit().rgba.size)
        }
    }

    private fun cues(path: String): List<MediaReader.Cue> = MediaReader(path).use { r ->
        r.selectText(r.tracks.first { it.kind == Track.Kind.TEXT }.id.toInt())
        generateSequence { r.read() }.filterIsInstance<MediaReader.Cue>().toList()
    }

    private val expected = listOf(
        MediaReader.Cue(200, 800, "First line"),
        MediaReader.Cue(1000, 1600, "Second line\nwith two rows"),
    )

    @Test
    fun `a subtitle file beside the video reads as timed lines of text`() {
        assertEquals(expected, cues(File(requireNotNull(javaClass.getResource("/clip.de.srt")).toURI()).path))
    }

    @Test
    fun `a file's own subtitle stream reads the same, with its language`() {
        val mkv = File(requireNotNull(javaClass.getResource("/subtitled.mkv")).toURI()).path
        assertEquals(expected, cues(mkv))
        MediaReader(mkv).use { r -> assertEquals("deu", r.tracks.first { it.kind == Track.Kind.TEXT }.language) }
    }

    /** A file the way a network source offers one: a length, and a stream from any offset; no path to open. */
    private class StreamOnly(private val file: File) : FsFile {
        var opened = 0
        override val name = file.name
        override val path = "smb://nas/media/${file.name}"
        override val isDirectory = false
        override val parentFile: FsFile? = null
        override fun length() = file.length()
        override fun lastModified() = 0L
        override fun listFiles(): List<FsFile>? = null
        override fun child(name: String): FsFile = error("not a folder")
        override fun open(offset: Long): InputStream {
            opened++
            return file.inputStream().apply { skip(offset) }
        }
    }

    @Test
    fun `a file from a network source plays like a local one, seeks included`() {
        val remote = StreamOnly(File(clip))
        MediaReader(remote.path, remote).use { r ->
            assertEquals(2000.0, r.durationMs.toDouble(), 50.0)
            assertEquals(listOf(Track.Kind.VIDEO, Track.Kind.AUDIO), r.tracks.map { it.kind })
            r.seek(1000)
            val pictures = generateSequence { r.read() }.filterIsInstance<MediaReader.Picture>().toList()
            assertEquals(1000L, pictures.first().ptsMs)
            assertEquals(25, pictures.size)
        }
        assertTrue(remote.opened >= 1)
    }
}
