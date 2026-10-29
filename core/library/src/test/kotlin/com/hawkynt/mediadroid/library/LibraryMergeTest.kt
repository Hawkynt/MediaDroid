package com.hawkynt.mediadroid.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryMergeTest {

    private fun heat(root: String, resolution: String) = Movie(
        title = "Heat",
        year = 1995,
        path = "$root/Heat",
        videoPath = "$root/Heat/Heat.mkv",
        quality = Quality(resolution = resolution),
    )

    private fun dark(root: String, season: Int) = Show(
        title = "Dark",
        path = "$root/Dark",
        seasons = listOf(Season(season, listOf(Episode(1, videoPath = "$root/Dark/s0${season}e01.mkv")))),
    )

    @Test
    fun aFilmOnTwoRootsIsOneCardWithBothFiles() {
        val phone = Library(listOf(heat("/storage/emulated/0/Movies", "1080p")), emptyList())
        val nas = Library(listOf(heat("smb://nas/media", "4K")), emptyList())

        val merged = Library.merge(listOf(phone, nas))

        val film = merged.movies.single()
        assertEquals("smb://nas/media/Heat/Heat.mkv", film.videoPath)
        assertEquals(
            listOf("smb://nas/media/Heat/Heat.mkv", "/storage/emulated/0/Movies/Heat/Heat.mkv"),
            film.versions.map { it.videoPath },
        )
    }

    @Test
    fun aMergedShowKeepsWhatItsNfoSaid() {
        val withNfo = dark("/a", 1).copy(
            rating = 8.7,
            studios = listOf("Netflix"),
            premiered = "2017-12-01",
            cast = listOf("Louis Hofmann"),
        )
        val merged = LibraryScanner.mergeShows(listOf(dark("/b", 2), withNfo))
        assertEquals(8.7, merged.rating)
        assertEquals(listOf("Netflix"), merged.studios)
        assertEquals("2017-12-01", merged.premiered)
        assertEquals(listOf("Louis Hofmann"), merged.cast)
    }

    @Test
    fun aShowSplitOverTwoRootsIsOneShowWithAllItsSeasons() {
        val a = Library(emptyList(), listOf(dark("/a", 1)))
        val b = Library(emptyList(), listOf(dark("/b", 2)))
        val merged = Library.merge(listOf(a, b))
        assertEquals(listOf(1, 2), merged.shows.single().seasons.map { it.number })
    }

    @Test
    fun copiesAlreadyFoldedOnOneRootStayTogetherWithTheOtherRootsCopies() {
        val one = Library(MovieVersions.merge(listOf(heat("/a", "720p"), heat("/a2", "1080p"))), emptyList())
        val two = Library(listOf(heat("/b", "4K")), emptyList())
        assertEquals(3, Library.merge(listOf(one, two)).movies.single().versions.size)
    }
}
