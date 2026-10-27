package com.hawkynt.mediadroid.library

import com.hawkynt.mediadroid.nfo.TimeRange
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
    fun sceneNamedMovieFoldersAndFilesShowCleanTitles() {
        file(
            "Movies/The.Big.Short.2015.German.DL.1080p.BluRay.x264-GRP/" +
                "the.big.short.2015.german.dl.1080p.bluray.x264-grp.mkv",
        )
        file("Movies/Heat.1995.MULTi.2160p.UHD.BluRay.x265-GRP.mkv")

        val movies = LibraryScanner.scan(File(folder.root, "Movies")).movies
        assertEquals(listOf("Heat" to 1995, "The Big Short" to 2015), movies.map { it.title to it.year })
    }

    @Test
    fun onlyCleanEpisodeNamesGiveTitlesSoTheUiOtherwiseSaysEpisodeN() {
        file("Dark/Season 01/Dark.S01E01.Secrets.German.DL.1080p.WEB.x264-GRP.mkv")
        file("Dark/Season 01/S01E02 - Lies.mkv")
        file("Dark/Season 01/E03 - Past and Present.mkv")
        file("Dark/Season 01/1x04 - Double Lives.mkv")

        val episodes = LibraryScanner.scan(folder.root).shows.single().seasons.single().episodes
        assertEquals(listOf(1, 2, 3, 4), episodes.map { it.number })
        assertEquals(listOf(null, "Lies", "Past and Present", "Double Lives"), episodes.map { it.title })
    }

    @Test
    fun animeWithAbsoluteNumbersAndNoSeasonsIsOneSeason() {
        file("Anime/One Piece/EP001 - Romance Dawn.mkv")
        file("Anime/One Piece/EP002 - Enter the Great Swordsman.mkv")
        file("Anime/One Piece/EP1071 - Luffy's Peak.mkv")
        file("Anime/One Piece/folder.jpg")

        val library = LibraryScanner.scan(File(folder.root, "Anime"))
        val show = library.shows.single()
        assertEquals("One Piece", show.title)
        assertEquals(listOf(1), show.seasons.map { it.number })
        assertEquals(listOf(1, 2, 1071), show.seasons.single().episodes.map { it.number })
        assertEquals("Romance Dawn", show.seasons.single().episodes.first().title)
        assertEquals(File(folder.root, "Anime/One Piece/folder.jpg").absolutePath, show.posterPath)
        assertEquals(emptyList<Movie>(), library.movies)
    }

    @Test
    fun seasonArtComesFromTheSeasonFolderOrTheShowFolder() {
        file("Dark/Season 01/folder.jpg")
        file("Dark/Season 01/s01e01.mkv")
        file("Dark/Season 02/s02e01.mkv")
        file("Dark/season02-poster.jpg")
        file("Dark/Season 03/s03e01.mkv")

        val seasons = LibraryScanner.scan(folder.root).shows.single().seasons
        assertEquals(File(folder.root, "Dark/Season 01/folder.jpg").absolutePath, seasons[0].posterPath)
        assertEquals(File(folder.root, "Dark/season02-poster.jpg").absolutePath, seasons[1].posterPath)
        assertNull(seasons[2].posterPath)
    }

    @Test
    fun aJpgNamedAfterTheMovieFileIsItsPoster() {
        file("Movies/Coco (2017).mkv")
        file("Movies/Coco (2017).jpg")

        val movie = LibraryScanner.scan(File(folder.root, "Movies")).movies.single()
        assertEquals(File(folder.root, "Movies/Coco (2017).jpg").absolutePath, movie.posterPath)
    }

    @Test
    fun multiPartMovieInAFolderIsOneMovieWithTheFolderCover() {
        file("Movies/Heat (1995)/Heat.CD2.avi")
        file("Movies/Heat (1995)/Heat.CD1.avi")
        file("Movies/Heat (1995)/folder.jpg")

        val movie = LibraryScanner.scan(File(folder.root, "Movies")).movies.single()
        assertEquals("Heat", movie.title)
        assertEquals(1995, movie.year)
        assertEquals(
            listOf("Heat.CD1.avi", "Heat.CD2.avi").map { File(folder.root, "Movies/Heat (1995)/$it").absolutePath },
            movie.parts,
        )
        assertEquals(movie.parts.first(), movie.videoPath)
        assertEquals(File(folder.root, "Movies/Heat (1995)/folder.jpg").absolutePath, movie.posterPath)
    }

    @Test
    fun streamLanguagesFromNfosReachTheModels() {
        file("Movies/Heat (1995)/Heat (1995).mkv")
        file("Movies/Heat (1995)/movie.nfo", """
            <movie><title>Heat</title><fileinfo><streamdetails>
            <audio><language>ger</language></audio><audio><language>eng</language></audio>
            <subtitle><language>eng</language></subtitle></streamdetails></fileinfo></movie>
        """.trimIndent())

        val movie = LibraryScanner.scan(File(folder.root, "Movies")).movies.single()
        assertEquals(listOf("ger", "eng"), movie.audioLanguages)
        assertEquals(listOf("eng"), movie.subtitleLanguages)
    }

    @Test
    fun introAndCreditsComeFromTheEditDecisionListBesideTheVideo() {
        file("Shows/Dark/Season 01/S01E01.mkv")
        file("Shows/Dark/Season 01/S01E01.edl", "## Intro Skipper\n62.5 152 3\n1250 1320 3\n")
        file("Movies/Heat (1995)/Heat (1995) CD1.avi")
        file("Movies/Heat (1995)/Heat (1995) CD1.edl", "0:30 2:00 4\n")
        file("Movies/Heat (1995)/Heat (1995) CD2.avi")
        file("Movies/Heat (1995)/Heat (1995) CD2.edl", "1:41:00 1:45:00 3\n")

        val episode = LibraryScanner.scan(File(folder.root, "Shows")).shows.single().seasons.single().episodes.single()
        assertEquals(TimeRange(62_500, 152_000), episode.intro)
        assertEquals(TimeRange(1_250_000, 1_320_000), episode.credits)
        val movie = LibraryScanner.scan(File(folder.root, "Movies")).movies.single()
        assertEquals("the first part's EDL has the intro", TimeRange(30_000, 120_000), movie.intro)
        assertEquals("the last part's EDL has the credits", TimeRange(6_060_000, 6_300_000), movie.credits)
    }

    @Test
    fun postersSeasonArtAndThumbsInAnyPictureFormat() {
        file("Shows/Dark/tvshow.nfo", "<tvshow><title>Dark</title></tvshow>")
        file("Shows/Dark/season01-poster.tga")
        file("Shows/Dark/Season 01/S01E01.mkv")
        file("Shows/Dark/Season 01/S01E01-thumb.tif")
        file("Movies/Heat (1995)/Heat (1995).mkv")
        file("Movies/Heat (1995)/Heat (1995).pcx")

        val show = LibraryScanner.scan(File(folder.root, "Shows")).shows.single()
        assertTrue(show.seasons.single().posterPath!!.endsWith("season01-poster.tga"))
        assertTrue(show.seasons.single().episodes.single().thumbPath!!.endsWith("S01E01-thumb.tif"))
        val movie = LibraryScanner.scan(File(folder.root, "Movies")).movies.single()
        assertTrue(movie.posterPath!!.endsWith("Heat (1995).pcx"))
    }

    @Test
    fun everyContainerThePlayerReadsIsAVideo() {
        val extensions = listOf(
            "mp4", "m4v", "mov", "qt", "3gp", "3g2", "f4v", "mkv", "mk3d", "webm", "avi", "divx", "xvid",
            "mpg", "mpeg", "mpe", "mp2", "m2p", "vob", "ts", "m2ts", "mts", "m2t", "tp", "trp", "ogv", "ogm", "flv",
        )
        // Neutral names: "DivX", "XviD" or "TS" in a title are release tags the cleaner strips.
        val names = (
            "Alpaca Badger Camel Dingo Eagle Falcon Gecko Heron Ibis Jaguar Koala Lemur Moose Newt Otter Panda " +
                "Quail Raven Sloth Tapir Urchin Viper Walrus Yak Zebra Bison Crane Dove"
            ).split(" ")
        extensions.forEachIndexed { i, ext -> file("Movies/${names[i]}.$ext") }
        file("Movies/notes.txt")
        file("Movies/cover.jpg")

        val titles = LibraryScanner.scan(File(folder.root, "Movies")).movies.map { it.title }.toSet()
        assertEquals(names.take(extensions.size).toSet(), titles)
    }

    @Test
    fun anyLooseVideoThatIsNoEpisodeIsAMovie() {
        file("Movies/Heat.mp4")
        file("Movies/Cold Storage.mp4")
        file("Movies/Coyote vs. ACME.mp4")
        file("Movies/Heat-trailer.mp4")
        file("Movies/sample.mkv")

        val movies = LibraryScanner.scan(File(folder.root, "Movies")).movies
        assertEquals(listOf("Cold Storage", "Coyote vs. ACME", "Heat"), movies.map { it.title })
        assertEquals(listOf(null, null, null), movies.map { it.year })
    }

    @Test
    fun aFolderWithOneVideoIsAMovieNamedAfterTheFolder() {
        file("Movies/Ronin/ronin_final_cut.mkv")
        file("Movies/Ronin/folder.jpg")

        val movie = LibraryScanner.scan(File(folder.root, "Movies")).movies.single()
        assertEquals("Ronin", movie.title)
        assertEquals(File(folder.root, "Movies/Ronin/folder.jpg").absolutePath, movie.posterPath)
    }

    @Test
    fun looseMoviesAndShowsSharingAFolderKeepDistinctKeys() {
        // A card wall keys its cards by item; several loose files in one folder share that folder
        // as their path, so the path alone cannot be the key.
        file("Movies/Coco (2017).mkv")
        file("Movies/Up (2009).mkv")
        file("Movies/Firefly.S01E01.Serenity.mkv")
        file("Movies/Dark.S01E01.Secrets.mkv")

        val library = LibraryScanner.scan(File(folder.root, "Movies"))
        assertEquals(listOf("Coco", "Up"), library.movies.map { it.title })
        assertEquals(listOf("Dark", "Firefly"), library.shows.map { it.title })
        assertEquals(1, library.items.map { it.path }.distinct().size)
        assertEquals(library.items.size, library.items.map { it.key }.distinct().size)
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
    fun mergesLooseEpisodesIntoSeasonFoldersAndDerivesTitlesFromCleanNames() {
        file("TV Shows/The Expanse (2015)/tvshow.nfo", "<tvshow><title>The Expanse</title><year>2015</year></tvshow>")
        file("TV Shows/The Expanse (2015)/Season 01/s01e01.mkv", "a".repeat(100))
        file("TV Shows/The Expanse (2015)/S01E02 - Salvage.mkv", "a".repeat(100))
        file("TV Shows/The Expanse (2015)/2x01 - Safe.mkv", "a".repeat(100))

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
    fun sceneEpisodeNamesCarryNoTitle() {
        file("TV Shows/The Expanse (2015)/tvshow.nfo", "<tvshow><title>The Expanse</title><year>2015</year></tvshow>")
        file(
            "TV Shows/The Expanse (2015)/The.Expanse.S01E02.Salvage.1080p.ATMOS.WEB-DL.DDP5.1.x264-GRP.mkv",
            "a".repeat(100),
        )

        val library = LibraryScanner.scan(folder.root)
        val episode = library.shows[0].seasons[0].episodes[0]
        assertEquals(2, episode.number)
        assertNull(episode.title)
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
