package com.hawkynt.mediadroid.desktop

import org.junit.Assert.assertEquals
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
        assertEquals(File("/home/u/Bilder/MediaDroid"), PicturesFolder.of("Linux", emptyMap(), "/home/u", dirs))
        val absolute = "XDG_PICTURES_DIR=\"/data/photos\"\n"
        assertEquals(File("/data/photos/MediaDroid"), PicturesFolder.of("Linux", emptyMap(), "/home/u", absolute))
    }

    @Test
    fun `without xdg-user-dirs, or with Pictures switched off, it is ~-Pictures`() {
        assertEquals(File("/home/u/Pictures/MediaDroid"), PicturesFolder.of("Linux", emptyMap(), "/home/u", null))
        // xdg-user-dirs: a folder set to $HOME itself is disabled.
        val off = "XDG_PICTURES_DIR=\"${'$'}HOME/\"\n"
        assertEquals(File("/home/u/Pictures/MediaDroid"), PicturesFolder.of("Linux", emptyMap(), "/home/u", off))
    }

    @Test
    fun `on Windows it is the profile's Pictures folder`() {
        assertEquals(
            File(File("C:\\Users\\u", "Pictures"), "MediaDroid"),
            PicturesFolder.of("Windows 11", mapOf("USERPROFILE" to "C:\\Users\\u"), "C:\\x", null),
        )
    }

    @Test
    fun `where xdg-user-dirs lives follows XDG_CONFIG_HOME`() {
        assertEquals(File("/c/user-dirs.dirs"), PicturesFolder.userDirsFile(mapOf("XDG_CONFIG_HOME" to "/c"), "/h"))
        assertEquals(File("/h/.config/user-dirs.dirs"), PicturesFolder.userDirsFile(emptyMap(), "/h"))
    }
}
