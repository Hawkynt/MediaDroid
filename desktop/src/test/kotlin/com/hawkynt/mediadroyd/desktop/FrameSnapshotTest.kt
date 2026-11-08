package com.hawkynt.mediadroyd.desktop

import com.hawkynt.mediadroyd.engine.desktop.MediaReader
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

class FrameSnapshotTest {

    @get:Rule
    val folder = TemporaryFolder()

    // 2 × 1: red, then half-transparent blue — a snapshot is opaque whatever the picture's alpha.
    private val picture = MediaReader.Picture(
        5_025_400L, 2, 1,
        byteArrayOf(-1, 0, 0, -1, 0, 0, -1, 0x40),
    )

    @Test
    fun `the picture is saved as an opaque PNG named after the title and its position`() {
        val file = FrameSnapshot.save(picture, "AC/DC: Live", folder.root)
        assertEquals(File(folder.root, "AC DC Live 01-23-45.png"), file)
        val read = ImageIO.read(file)
        assertEquals(2, read.width)
        assertEquals(1, read.height)
        assertEquals(0xFFFF0000.toInt(), read.getRGB(0, 0))
        assertEquals(0xFF0000FF.toInt(), read.getRGB(1, 0))
    }

    @Test
    fun `a second snapshot of the same second never overwrites the first`() {
        val first = FrameSnapshot.save(picture, "Up", folder.root)
        val second = FrameSnapshot.save(picture, "Up", folder.root)
        assertEquals("Up 01-23-45.png", first.name)
        assertEquals("Up 01-23-45 (2).png", second.name)
    }
}
