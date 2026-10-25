package com.hawkynt.mediadroid.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryScannerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(path: String, content: String = "x"): File =
        File(folder.root, path).apply {
            parentFile.mkdirs()
            writeText(content)
        }

    @Test
    fun recognizesShowWithSeasonFoldersAndSidecarNfos() {
        file("TV Shows/Supernatural (2005)/tvshow.nfo", """
            <tvshow><title>Supernatural</title><premiered>2005-09-13</premiered>
            <genre>Drama</genre><uniqueid type="imdb">tt0460681</uniqueid></tvshow>
        """.trimIndent())
        file("TV Shows/Supernatural (2005)/folder.jpg")
        file("TV Shows/Supernatural (2005)/Season 04/s04e09.mkv", "a".repeat(100))
        file("TV Shows/Supernatural (2005)/Season 04/s04e09.nfo", """
            <episodedetails><title>I Know What You Did Last Summer</title><season>4</season>
            <episode>9</episode><aired>2008-11-13</aired><playcount>1</playcount></episodedetails>
        """.trimIndent())
        file("TV Shows/Supernatural (2005)/Season 04/s04e09-thumb.jpg")
        file("TV Shows/Supernatural (2005)/Season 04/s04e10.mkv", "a".repeat(50))
        file("TV Shows/Supernatural (2005)/Season 04/s04e10.nfo", """
            <episodedetails><title>In the Beginning</title><season>4</season><episode>10</episode></episodedetails>
        """.trimIndent())

        val library = LibraryScanner.scan(folder.root)
        assertEquals(1, library.shows.size)
        val show = library.shows[0]
        assertEquals("Supernatural", show.title)
        assertEquals(2005, show.year)
        assertEquals(listOf("Drama"), show.genres)
        assertNotNull(show.posterPath)
        assertEquals(1, show.seasons.size)
        assertEquals(4, show.seasons[0].number)
        val episodes = show.seasons[0].episodes
        assertEquals(listOf(9, 10), episodes.map { it.number })
        assertEquals("I Know What You Did Last Summer", episodes[0].title)
        assertTrue(episodes[0].watched)
        assertNotNull(episodes[0].thumbPath)
        assertEquals(emptyList<Movie>(), library.movies)
    }

    @Test
    fun certificationsFromNfosBecomeMinimumAges() {
        file("Movies/Die Hard (1988)/movie.nfo", """
            <movie><title>Die Hard</title><year>1988</year><mpaa>FSK 16</mpaa></movie>
        """.trimIndent())
        file("Movies/Die Hard (1988)/die hard.mkv", "a".repeat(100))
        file("TV Shows/Breaking Bad (2008)/tvshow.nfo", """
            <tvshow><title>Breaking Bad</title><mpaa>TV-MA</mpaa></tvshow>
        """.trimIndent())
        file("TV Shows/Breaking Bad (2008)/Season 01/s01e01.mkv", "a".repeat(100))

        val library = LibraryScanner.scan(folder.root)
        assertEquals(16, library.movies[0].minAge)
        assertEquals(18, library.shows[0].minAge)
    }

    @Test
    fun recognizesMovieFolderWithNfoAndCover() {
        file("Movies/Coco (2017)/movie.nfo", """
            <movie><title>Coco - Lebendiger als das Leben!</title><year>2017</year>
            <genre>Familie</genre><runtime>109</runtime><id>tt2380307</id></movie>
        """.trimIndent())
        file("Movies/Coco (2017)/Coco (2017).mp4", "a".repeat(200))
        file("Movies/Coco (2017)/folder.jpg")

        val library = LibraryScanner.scan(folder.root)
        assertEquals(1, library.movies.size)
        val movie = library.movies[0]
        assertEquals("Coco - Lebendiger als das Leben!", movie.title)
        assertEquals(2017, movie.year)
        assertEquals(listOf("Familie"), movie.genres)
        assertEquals(109, movie.runtimeMinutes)
        assertNotNull(movie.posterPath)
    }

    @Test
    fun looseVideoWithSidecarNfoIsAMovie() {
        file("Incoming/Coco (2017).mp4", "a".repeat(100))
        file("Incoming/Coco (2017).nfo", "<movie><title>Coco</title><year>2017</year></movie>")
        file("Incoming/Coco (2017)-poster.jpg")
        file("Incoming/SomeGame.sfc")

        val library = LibraryScanner.scan(folder.root)
        assertEquals(1, library.movies.size)
        assertEquals("Coco", library.movies[0].title)
        assertNotNull(library.movies[0].posterPath)
    }

    @Test
    fun looseVideoNamedTitleYearNeedsNoNfo() {
        file("Incoming/Ocean's 8 (2018).mp4", "a".repeat(100))

        val library = LibraryScanner.scan(folder.root)
        assertEquals(1, library.movies.size)
        assertEquals("Ocean's 8", library.movies[0].title)
        assertEquals(2018, library.movies[0].year)
        assertNull(library.movies[0].plot)
    }

    @Test
    fun mixedIncomingFolderYieldsOneMoviePerVideo() {
        file("Incoming/Coco (2017).mp4", "a".repeat(100))
        file("Incoming/Coco (2017).nfo", "<movie><title>Coco</title><year>2017</year></movie>")
        file("Incoming/Ocean's 8 (2018).mp4", "a".repeat(100))

        val library = LibraryScanner.scan(folder.root)
        assertEquals(2, library.movies.size)
        assertEquals(listOf("Coco", "Ocean's 8"), library.movies.map { it.title })
    }

    @Test
    fun scansTheMaintainersRealLibraryWhenPresent() {
        val real = File(System.getProperty("user.home"), "Media")
        assumeTrue(real.isDirectory)

        val library = LibraryScanner.scan(real)
        val supernatural = library.shows.firstOrNull { it.title == "Supernatural" }
        assertNotNull("Supernatural should be found in the real library", supernatural)
        assertTrue(
            "Supernatural season 4 should have episodes",
            supernatural!!.seasons.any { it.number == 4 && it.episodes.isNotEmpty() },
        )
        assertTrue("Coco should be found among loose movies", library.movies.any { it.title.contains("Coco") })
        assertTrue("every movie has a playable video", library.movies.all { File(it.videoPath).isFile })
        assertTrue("every episode has a playable video",
            library.shows.flatMap { it.seasons }.flatMap { it.episodes }.all { File(it.videoPath).isFile })
    }

    @Test
    fun groupsLooseEpisodeNamedFilesIntoVirtualSeasons() {
        file("TV Shows/Firefly (2002)/s01e01.mkv", "a".repeat(100))
        file("TV Shows/Firefly (2002)/s01e02.mkv", "a".repeat(100))
        file("TV Shows/Firefly (2002)/2x03.mkv", "a".repeat(100))

        val library = LibraryScanner.scan(folder.root)
        assertEquals(1, library.shows.size)
        val show = library.shows[0]
        assertEquals("Firefly", show.title)
        assertEquals(2002, show.year)
        assertEquals(listOf(1, 2), show.seasons.map { it.number })
        assertEquals(listOf(1, 2), show.seasons[0].episodes.map { it.number })
        assertEquals(3, show.seasons[1].episodes[0].number)
        assertEquals(emptyList<Movie>(), library.movies)
    }

    @Test
    fun mergesLooseEpisodesIntoSeasonFoldersAndDerivesTitlesFromNames() {
        file("TV Shows/The Expanse (2015)/tvshow.nfo", "<tvshow><title>The Expanse</title><year>2015</year></tvshow>")
        file("TV Shows/The Expanse (2015)/Season 01/s01e01.mkv", "a".repeat(100))
        file("TV Shows/The Expanse (2015)/The.Expanse.S01E02.Salvage.mkv", "a".repeat(100))
        file("TV Shows/The Expanse (2015)/The.Expanse.S02E01.Safe.mkv", "a".repeat(100))

        val library = LibraryScanner.scan(folder.root)
        val seasons = library.shows[0].seasons
        assertEquals(listOf(1, 2), seasons.map { it.number })
        assertEquals(listOf(1, 2), seasons[0].episodes.map { it.number })
        assertEquals("Salvage", seasons[0].episodes[1].title)
        assertEquals("Safe", seasons[1].episodes[0].title)
    }

    @Test
    fun groupsJunkNamedLooseEpisodesByTheirSharedShowName() {
        file("Incoming/Firefly.S01E01.720p.WEB-DL.x264-GRP.mkv", "a".repeat(100))
        file("Incoming/Firefly.S01E02.720p.WEB-DL.mkv", "a".repeat(100))
        file("Incoming/Firefly.S02E01.1080p.BluRay.x265.mkv", "a".repeat(100))
        file("Incoming/Coco (2017).mp4", "a".repeat(100))
        file("Incoming/Coco (2017).nfo", "<movie><title>Coco</title><year>2017</year></movie>")

        val library = LibraryScanner.scan(folder.root)
        assertEquals(1, library.shows.size)
        val show = library.shows[0]
        assertEquals("Firefly", show.title)
        assertEquals(listOf(1, 2), show.seasons.map { it.number })
        assertEquals(listOf(1, 2), show.seasons[0].episodes.map { it.number })
        assertEquals(listOf("Coco"), library.movies.map { it.title })
    }

    @Test
    fun looseEpisodesJoinTheShowFolderLivingElsewhere() {
        file("TV Shows/Firefly (2005)/tvshow.nfo", """
            <tvshow><title>Firefly</title><premiered>2005-09-20</premiered></tvshow>
        """.trimIndent())
        file("TV Shows/Firefly (2005)/Season 01/s01e01.mkv", "a".repeat(100))
        file("Incoming/Firefly.S02E01.1080p.WEB-DL.mkv", "a".repeat(100))

        val library = LibraryScanner.scan(folder.root)
        assertEquals(1, library.shows.size)
        val show = library.shows[0]
        assertEquals("Firefly", show.title)
        assertEquals(2005, show.year)
        assertEquals(listOf(1, 2), show.seasons.map { it.number })
        assertEquals(emptyList<Movie>(), library.movies)
    }

    @Test
    fun episodeTitleStopsAtTheFirstReleaseToken() {
        file("TV Shows/The Expanse (2015)/tvshow.nfo", "<tvshow><title>The Expanse</title><year>2015</year></tvshow>")
        file("TV Shows/The Expanse (2015)/The.Expanse.S01E02.Salvage.1080p.ATMOS.WEB-DL.DDP5.1.x264-GRP.mkv", "a".repeat(100))

        val library = LibraryScanner.scan(folder.root)
        val episode = library.shows[0].seasons[0].episodes[0]
        assertEquals("Salvage", episode.title)
    }

    @Test
    fun showFoldersAreNotWalkedTwice() {
        file("TV Shows/Buru doragon (2007)/tvshow.nfo", "<tvshow><title>Blue Dragon</title><year>2007</year></tvshow>")
        file("TV Shows/Buru doragon (2007)/Season 01/s01e01.mkv", "a".repeat(100))
        file("TV Shows/Buru doragon (2007)/Season 01/s01e01.nfo", """
            <episodedetails><title>Schatten und Anfang</title><season>1</season><episode>1</episode></episodedetails>
        """.trimIndent())

        val library = LibraryScanner.scan(folder.root)
        assertEquals(1, library.shows.size)
        assertEquals("Blue Dragon", library.shows[0].title)
        assertEquals(emptyList<Movie>(), library.movies)
    }
}
