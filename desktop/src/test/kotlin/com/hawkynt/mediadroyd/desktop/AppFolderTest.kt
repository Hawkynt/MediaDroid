package com.hawkynt.mediadroyd.desktop

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AppFolderTest {

    @Test
    fun `on Linux it is under XDG_DATA_HOME, or ~-local-share without it`() {
        assertEquals(File("/x/MediaDroyd"), AppFolder.of("Linux", mapOf("XDG_DATA_HOME" to "/x"), "/home/u", null))
        assertEquals(File("/home/u/.local/share/MediaDroyd"), AppFolder.of("Linux", emptyMap(), "/home/u", null))
    }

    @Test
    fun `on Windows it is under APPDATA`() {
        assertEquals(
            File("C:\\Users\\u\\AppData\\Roaming", "MediaDroyd"),
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

    @Test
    fun `the program's folder is two up from its resources, however the launcher wrote that path`() {
        val root = File(System.getProperty("user.dir")).absoluteFile
        // Absolute on every system (a drive letter on Windows): the program folder somewhere real.
        val unpacked = File(root, "md")
        assertEquals(unpacked, AppFolder.programOf(File(unpacked, "lib${File.separator}resources").path))
        assertEquals(root, AppFolder.programOf("lib${File.separator}resources"))
        assertEquals(null, AppFolder.programOf(null))
    }

    @Test
    fun `the folder from before the rename moves to the new name, an existing new one is left alone`() {
        val root = kotlin.io.path.createTempDirectory("app-folder").toFile()
        try {
            val old = File(root, "MediaDroid").apply { mkdirs() }
            File(old, "state.json").writeText("{}")
            val folder = File(root, "MediaDroyd")
            AppFolder.movedFromOld(folder)
            assertEquals("{}", File(folder, "state.json").readText())
            assertEquals(false, old.exists())
            File(root, "MediaDroid").mkdirs()
            AppFolder.movedFromOld(folder)
            assertEquals(true, File(root, "MediaDroid").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
