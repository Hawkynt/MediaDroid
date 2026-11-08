package com.hawkynt.mediadroyd.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopMediaArtTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun art(): Pair<DesktopMediaArt, File> {
        val data = folder.newFolder("data")
        val cache = File(data, "art")
        val places = DesktopLibrary(DesktopStore(data), FileSecrets(File(data, "secrets.properties")))
        return DesktopMediaArt(places, cache) to cache
    }

    @Test
    fun `a picture on this computer is shown as it is, nothing copied`() = runBlocking {
        val (art, cache) = art()
        val poster = File(folder.newFolder("Movies"), "folder.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(poster, art.picture(poster.path))
        assertFalse(cache.exists())
    }

    @Test
    fun `a picture on no source this app knows, or one that is gone, has none`() = runBlocking {
        val (art, _) = art()
        assertNull(art.picture("smb://nowhere/share/folder.jpg"))
        assertNull(art.picture(File(folder.root, "gone.jpg").path))
    }
}
