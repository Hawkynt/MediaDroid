package com.hawkynt.mediadroid.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Titles FFmpeg wrote into the containers (`src/test/make-title-fixtures.py`) and reads back the same. */
class ContainerTitleTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun fixture(name: String): File = File(folder.root, name).apply {
        writeBytes(ContainerTitleTest::class.java.getResourceAsStream("/titles/$name")!!.readBytes())
    }

    @Test
    fun matroskaSegmentTitle() =
        assertEquals("Coco - Lebendiger als das Leben!", ContainerTitle.read(fixture("titled.mkv")))

    @Test
    fun mp4ItunesNameAtom() = assertEquals("Das große Abenteuer", ContainerTitle.read(fixture("titled.mp4")))

    @Test
    fun anEmptyOrMissingTitleIsNone() {
        assertNull(ContainerTitle.read(fixture("untitled.mkv")))
        assertNull(ContainerTitle.read(File(folder.root, "missing.mkv")))
        assertNull(ContainerTitle.read(File(folder.root, "junk.mp4").apply { writeText("not a movie") }))
    }

    @Test
    fun aMovieWithoutAnNfoTakesTheContainerTitleBeforeItsFileName() {
        File(folder.root, "Movies").mkdirs()
        fixture("titled.mkv").copyTo(File(folder.root, "Movies/Coco.2017.German.1080p.BluRay.x264-GRP.mkv"))
        val movie = LibraryScanner.scan(File(folder.root, "Movies")).movies.single()
        assertEquals("Coco - Lebendiger als das Leben!", movie.title)
        assertEquals(2017, movie.year)
    }
}
