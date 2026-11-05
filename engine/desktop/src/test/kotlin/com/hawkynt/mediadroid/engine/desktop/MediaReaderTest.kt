package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.engine.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    fun `the shape of a picture's pixels comes from the file, square without one`() {
        MediaReader(clip).use { assertEquals(1f, it.pixelAspect, 0.001f) }
        val anamorphic = File(requireNotNull(javaClass.getResource("/anamorphic.mp4")).toURI()).path
        MediaReader(anamorphic).use { assertEquals(16f / 15f, it.pixelAspect, 0.001f) }
    }
}
