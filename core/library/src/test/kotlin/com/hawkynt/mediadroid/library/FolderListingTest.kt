package com.hawkynt.mediadroid.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderListingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(path: String, content: String = "x", modified: Long? = null): File =
        File(folder.root, path).apply {
            parentFile.mkdirs()
            writeText(content)
            modified?.let { setLastModified(it) }
        }

    @Test
    fun foldersComeFirstThenVideosByNameAsNumbersRead() {
        file("Holiday/clip.mp4")
        file("archive/old.mkv")
        file("Episode 10.mkv")
        file("Episode 2.mkv")
        file("notes.txt")
        file("cover.jpg")
        file(".hidden/secret.mkv")

        val listing = FolderListing.list(folder.root)

        assertEquals(listOf("archive", "Holiday"), listing.folders.map { it.name })
        assertEquals(listOf("Episode 2.mkv", "Episode 10.mkv"), listing.videos.map { it.name })
    }

    @Test
    fun namesStayRealUnlessAnNfoSaysOtherwise() {
        file("The.Big.Short.2015.German.DL.1080p.BluRay.x264-GRP.mkv")
        file("dark.s01e01.mkv")
        file("dark.s01e01.nfo", "<episodedetails><title>Secrets</title><playcount>1</playcount></episodedetails>")
        file("heat.mkv")
        file("heat.nfo", "<movie><title>Heat</title><plot>LA.</plot><mpaa>FSK 16</mpaa></movie>")

        val videos = FolderListing.list(folder.root).videos.associateBy { it.name }

        assertEquals(
            "The.Big.Short.2015.German.DL.1080p.BluRay.x264-GRP",
            videos.getValue("The.Big.Short.2015.German.DL.1080p.BluRay.x264-GRP.mkv").title,
        )
        assertEquals("Secrets", videos.getValue("dark.s01e01.mkv").title)
        assertTrue(videos.getValue("dark.s01e01.mkv").nfoWatched)
        val heat = videos.getValue("heat.mkv")
        assertEquals("Heat", heat.title)
        assertEquals("LA.", heat.plot)
        assertEquals(16, heat.minAge)
    }

    @Test
    fun theUsualArtShowsForFoldersAndVideos() {
        file("Holiday/folder.jpg")
        file("Holiday/a.mp4")
        file("Empty/readme.txt")
        file("a.mkv")
        file("a.jpg")
        file("b.mkv")
        file("b-poster.png")
        file("c.mkv")
        file("c-thumb.jpg")
        file("d.mkv")

        val listing = FolderListing.list(folder.root)

        assertEquals("folder.jpg", listing.folders.first { it.name == "Holiday" }.coverPath?.let { File(it).name })
        assertNull(listing.folders.first { it.name == "Empty" }.coverPath)
        val art = listing.videos.associate { it.name to it.picturePath?.let { p -> File(p).name } }
        assertEquals(
            mapOf("a.mkv" to "a.jpg", "b.mkv" to "b-poster.png", "c.mkv" to "c-thumb.jpg", "d.mkv" to null),
            art,
        )
    }

    @Test
    fun videosSortByDateOrSizeNewestAndBiggestFirst() {
        file("old.mkv", "xx", modified = 1_000_000_000_000L)
        file("new.mkv", "x", modified = 1_700_000_000_000L)
        file("big.mkv", "xxxxx", modified = 1_500_000_000_000L)

        assertEquals(
            listOf("new.mkv", "big.mkv", "old.mkv"),
            FolderListing.list(folder.root, FileSort.DATE).videos.map { it.name },
        )
        assertEquals(
            listOf("big.mkv", "old.mkv", "new.mkv"),
            FolderListing.list(folder.root, FileSort.SIZE).videos.map { it.name },
        )
    }
}
