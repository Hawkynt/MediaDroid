package com.hawkynt.mediadroyd.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PicturesFolderTest {

    @Test
    fun `on Linux it is the Pictures folder xdg-user-dirs names, relative to home or absolute`() {
        val dirs = """
            # This file is written by xdg-user-dirs-update
            XDG_DESKTOP_DIR="${'$'}HOME/Desktop"
            XDG_PICTURES_DIR="${'$'}HOME/Bilder"
        """.trimIndent()
        assertEquals(File("/home/u/Bilder/MediaDroyd"), PicturesFolder.of("Linux", emptyMap(), "/home/u", dirs))
        val absolute = "XDG_PICTURES_DIR=\"/data/photos\"\n"
        assertEquals(File("/data/photos/MediaDroyd"), PicturesFolder.of("Linux", emptyMap(), "/home/u", absolute))
    }

    @Test
    fun `without xdg-user-dirs, or with Pictures switched off, it is ~-Pictures`() {
        assertEquals(File("/home/u/Pictures/MediaDroyd"), PicturesFolder.of("Linux", emptyMap(), "/home/u", null))
        // xdg-user-dirs: a folder set to $HOME itself is disabled.
        val off = "XDG_PICTURES_DIR=\"${'$'}HOME/\"\n"
        assertEquals(File("/home/u/Pictures/MediaDroyd"), PicturesFolder.of("Linux", emptyMap(), "/home/u", off))
    }

    @Test
    fun `on Windows it is the profile's Pictures folder`() {
        assertEquals(
            File(File("C:\\Users\\u", "Pictures"), "MediaDroyd"),
            PicturesFolder.of("Windows 11", mapOf("USERPROFILE" to "C:\\Users\\u"), "C:\\x", null),
        )
    }

    @Test
    fun `on Windows a Pictures folder moved elsewhere is followed, the profile's only without one`() {
        val env = mapOf("USERPROFILE" to "C:\\Users\\u")
        assertEquals(
            File("D:\\OneDrive\\Bilder", "MediaDroyd"),
            PicturesFolder.of("Windows 11", env, "C:\\x", null, "D:\\OneDrive\\Bilder"),
        )
        assertEquals(
            File(File("C:\\Users\\u", "Pictures"), "MediaDroyd"),
            PicturesFolder.of("Windows 11", env, "C:\\x", null, ""),
        )
        // Known folders are Windows' alone.
        assertEquals(File("/home/u/Pictures/MediaDroyd"), PicturesFolder.of("Linux", emptyMap(), "/home/u", null, "/x"))
    }

    @Test
    fun `FOLDERID_Pictures reaches Windows in its own byte order`() {
        assertEquals(
            "3081e2331e4e7646835a98395c3bc3bb",
            Smtc.guidBytes(PicturesFolder.FOLDERID_PICTURES).joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `on Windows the known Pictures folder is a folder that exists`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val known = requireNotNull(PicturesFolder.knownPictures()) { "SHGetKnownFolderPath gave nothing" }
        assertTrue(known, File(known).isDirectory)
    }

    @Test
    fun `where xdg-user-dirs lives follows XDG_CONFIG_HOME`() {
        assertEquals(File("/c/user-dirs.dirs"), PicturesFolder.userDirsFile(mapOf("XDG_CONFIG_HOME" to "/c"), "/h"))
        assertEquals(File("/h/.config/user-dirs.dirs"), PicturesFolder.userDirsFile(emptyMap(), "/h"))
    }
}
