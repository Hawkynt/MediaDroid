package com.hawkynt.mediadroyd.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderDefaultsTest {
    private val phone = Source.local("primary", "Media folder", "/storage/emulated/0/Movies")
    private val nas = Source("nas", "NAS", SourceKind.SMB, host = "nas", path = "/media")
    private val sources = listOf(phone, nas)

    @Test
    fun `a video's place is its folder under the source that holds it`() {
        assertEquals(
            FolderPlace("nas", "VHS/1990"),
            FolderDefaults.placeOf(sources, "smb://nas/media/VHS/1990/tape.mkv"),
        )
        assertEquals(FolderPlace("primary", ""), FolderDefaults.placeOf(sources, "/storage/emulated/0/Movies/a.mkv"))
        assertEquals(
            FolderPlace("nas", "VHS"),
            FolderDefaults.placeOf(sources, "smb://nas/media/VHS/", isFolder = true),
        )
        // Held by no source: the whole folder path, Windows' backslashes as slashes.
        assertEquals(FolderPlace("", "/tmp/clips"), FolderDefaults.placeOf(sources, "/tmp/clips/a.mkv"))
        assertEquals(FolderPlace("", "C:/Videos/VHS"), FolderDefaults.placeOf(sources, "C:\\Videos\\VHS\\a.mkv"))
    }

    @Test
    fun `a folder holds itself and what lies inside it, folder by folder`() {
        val vhs = FolderPlace("nas", "VHS")
        assertTrue(vhs.holds(FolderPlace("nas", "VHS")))
        assertTrue(vhs.holds(FolderPlace("nas", "VHS/1990")))
        assertFalse(vhs.holds(FolderPlace("nas", "VHS-rips")))
        assertFalse(vhs.holds(FolderPlace("primary", "VHS")))
        // The whole share holds everything on it.
        assertTrue(FolderPlace("nas", "").holds(FolderPlace("nas", "Films/Old")))
    }

    @Test
    fun `the nearest folder above wins, then the share, else nothing`() {
        val defaults = mapOf(
            FolderPlace("nas", "") to "share",
            FolderPlace("nas", "VHS") to "vhs",
            FolderPlace("nas", "VHS/1990/broken") to "broken",
        )
        assertEquals(FolderPlace("nas", "VHS") to "vhs", FolderDefaults.nearest(defaults, FolderPlace("nas", "VHS/1990")))
        assertEquals("broken", FolderDefaults.nearest(defaults, FolderPlace("nas", "VHS/1990/broken"))?.second)
        assertEquals("share", FolderDefaults.nearest(defaults, FolderPlace("nas", "Films"))?.second)
        assertEquals("share", FolderDefaults.nearest(defaults, FolderPlace("nas", "VHS-rips"))?.second)
        assertNull(FolderDefaults.nearest(defaults, FolderPlace("primary", "VHS")))
    }

    @Test
    fun `a video's own choice beats its folder's, which beats the household's`() {
        assertEquals("own", FolderDefaults.resolve("own", "folder", "household"))
        assertEquals("folder", FolderDefaults.resolve(null, "folder", "household"))
        assertEquals("household", FolderDefaults.resolve(null, null, "household"))
    }

    @Test
    fun `a place is kept as a key and read back, and shown by the source's name`() {
        val place = FolderPlace("nas", "VHS/1990")
        assertEquals("nas|VHS/1990", place.key)
        assertEquals(place, FolderPlace.of(place.key))
        assertEquals(FolderPlace("", "C:/x|y"), FolderPlace.of("|C:/x|y"))
        assertNull(FolderPlace.of("nothing"))
        assertEquals("NAS › VHS › 1990", FolderDefaults.label(place, sources))
        assertEquals("NAS", FolderDefaults.label(FolderPlace("nas", ""), sources))
    }
}
