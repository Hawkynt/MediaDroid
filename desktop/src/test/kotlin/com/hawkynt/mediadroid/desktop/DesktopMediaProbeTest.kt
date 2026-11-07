package com.hawkynt.mediadroid.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/** subtitled.mkv: 64 × 48 at 25 fps (MPEG-4 Part 2), AAC stereo and German SubRip, 2 s. */
class DesktopMediaProbeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val data by lazy { folder.newFolder("data") }
    private val places by lazy { DesktopLibrary(DesktopStore(data), FileSecrets(File(data, "secrets.properties"))) }
    private val video by lazy {
        File(folder.newFolder("Movies"), "Clip.mkv").apply {
            writeBytes(requireNotNull(DesktopMediaProbeTest::class.java.getResource("/subtitled.mkv")).readBytes())
        }
    }

    private fun probe() = DesktopMediaProbe(places, data)

    @Test
    fun `a video's length and tracks are read once and kept beyond the app's run`() = runBlocking {
        val first = probe()
        assertEquals(2021.0, first.durationMs(video.path)!!.toDouble(), 50.0)
        assertEquals(
            listOf("Video: MPEG-4 · Simple Profile · 64 × 48 · 25 fps", "Audio: AAC · 2.0", "Subtitles: SubRip · DE"),
            first.tracks(video.path),
        )
        assertTrue(File(data, DesktopMediaProbe.CACHE).isFile)
        // Kept: a new run answers from the cache, even where the file could no longer be read as a video.
        val modified = video.lastModified()
        video.writeBytes(ByteArray(video.length().toInt()))
        video.setLastModified(modified)
        assertEquals(64, probe().streamDetails(video.path)?.videoWidth)
    }

    @Test
    fun `a changed file is read again`() = runBlocking {
        val probe = probe()
        assertNotNull(probe.durationMs(video.path))
        video.writeText("no longer a video")
        assertNull(probe.durationMs(video.path))
    }

    @Test
    fun `a still is taken a tenth of the way in, kept as a JPEG at the picture's size`() = runBlocking {
        val probe = probe()
        val still = requireNotNull(probe.still(video.path))
        val picture = requireNotNull(ImageIO.read(still))
        assertEquals(64 to 48, picture.width to picture.height)
        assertEquals(File(data, "thumbs"), still.parentFile)
        val taken = still.lastModified()
        assertEquals(still, probe.still(video.path))
        assertEquals(taken, still.lastModified())
    }

    @Test
    fun `what is no video has no facts and no still`() = runBlocking {
        val probe = probe()
        val text = File(folder.root, "notes.mkv").apply { writeText("hello") }
        assertNull(probe.tracks(text.path))
        assertNull(probe.still(text.path))
        assertNull(probe.durationMs(File(folder.root, "gone.mkv").path))
    }
}
