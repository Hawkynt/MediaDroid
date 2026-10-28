package com.hawkynt.mediadroid.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FsFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aLocalFileLooksLikeJavaIoFile() {
        File(folder.root, "Heat (1995)").mkdirs()
        File(folder.root, "Heat (1995)/Heat (1995).mkv").writeText("video")
        File(folder.root, "Heat (1995)/movie.nfo").writeText("<movie><title>Heat</title></movie>")

        val root = LocalFile(folder.root)
        val dir = root.listFiles()!!.single()
        assertTrue(dir.isDirectory)
        assertEquals(listOf("Heat (1995).mkv", "movie.nfo"), dir.listFiles()!!.map { it.name }.sorted())
        val video = dir.child("Heat (1995).mkv")
        assertEquals("Heat (1995)", video.nameWithoutExtension)
        assertEquals("mkv", video.extension)
        assertEquals(5, video.length())
        assertEquals(File(folder.root, "Heat (1995)/Heat (1995).mkv").absolutePath, video.path)
        assertEquals(dir, video.parentFile)
        assertEquals("<movie><title>Heat</title></movie>", dir.child("movie.nfo").readTextOrNull())
        assertNull(dir.child("missing.nfo").readTextOrNull())
    }
}
