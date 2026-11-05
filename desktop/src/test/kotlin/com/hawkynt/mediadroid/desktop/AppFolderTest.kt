package com.hawkynt.mediadroid.desktop

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AppFolderTest {

    @Test
    fun `on Linux it is under XDG_DATA_HOME, or ~-local-share without it`() {
        assertEquals(File("/x/MediaDroid"), AppFolder.of("Linux", mapOf("XDG_DATA_HOME" to "/x"), "/home/u", null))
        assertEquals(File("/home/u/.local/share/MediaDroid"), AppFolder.of("Linux", emptyMap(), "/home/u", null))
    }

    @Test
    fun `on Windows it is under APPDATA`() {
        assertEquals(
            File("C:\\Users\\u\\AppData\\Roaming", "MediaDroid"),
            AppFolder.of("Windows 11", mapOf("APPDATA" to "C:\\Users\\u\\AppData\\Roaming"), "C:\\Users\\u", null),
        )
    }

    @Test
    fun `the portable zip keeps its settings beside the program`() {
        assertEquals(
            File("/opt/md/data"),
            AppFolder.of("Windows 11", mapOf("APPDATA" to "C:\\x"), "C:\\u", File("/opt/md")),
        )
    }
}
