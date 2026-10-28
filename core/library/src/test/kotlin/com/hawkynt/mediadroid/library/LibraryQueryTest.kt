package com.hawkynt.mediadroid.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryQueryTest {

    private val items: List<MediaItem> = listOf(
        Movie(title = "Blade Runner 2049", year = 2017, genres = listOf("Action", "Science Fiction"), path = "/m/br2049", videoPath = "/m/br2049/br2049.mkv"),
        Movie(title = "Blade Runner", year = 1982, genres = listOf("Science Fiction"), path = "/m/br", videoPath = "/m/br/br.mkv"),
        Show(title = "Breaking Bad", year = 2008, genres = listOf("Crime", "Drama"), path = "/s/bb"),
        Movie(title = "Undated Short", genres = listOf("Drama"), path = "/m/short", videoPath = "/m/short/short.mkv"),
    )

    @Test
    fun searchIsCaseInsensitiveSubstring() {
        assertEquals(listOf("Blade Runner 2049", "Blade Runner"), LibraryQuery.search(items, "blade").map { it.title })
        assertEquals(items, LibraryQuery.search(items, "  "))
    }

    @Test
    fun genreFilterRequiresAllSelectedGenres() {
        assertEquals(
            listOf("Blade Runner 2049"),
            LibraryQuery.filterByGenres(items, setOf("Action", "Science Fiction")).map { it.title },
        )
        assertEquals(items, LibraryQuery.filterByGenres(items, emptySet()))
    }

    @Test
    fun ageFilterHidesOnlyItemsCertifiedAboveTheCap() {
        val rated = listOf(
            Movie(title = "Kids Film", minAge = 6, path = "/m/kids", videoPath = "/m/kids/k.mkv"),
            Movie(title = "Teen Film", minAge = 16, path = "/m/teen", videoPath = "/m/teen/t.mkv"),
            Movie(title = "Unrated Film", minAge = null, path = "/m/unrated", videoPath = "/m/unrated/u.mkv"),
        )
        assertEquals(
            listOf("Kids Film", "Unrated Film"),
            LibraryQuery.filterByAge(rated, 12).map { it.title },
        )
        assertEquals(rated, LibraryQuery.filterByAge(rated, 0))
    }

    @Test
    fun yearFilterKeepsOnlyDatedItemsInBounds() {
        assertEquals(
            listOf("Blade Runner 2049", "Breaking Bad"),
            LibraryQuery.filterByYear(items, 2000, 2020).map { it.title },
        )
    }

    @Test
    fun groupingBucketsByDecadeNewestFirst() {
        val groups = LibraryQuery.groupByYear(items)
        assertEquals(listOf(2010, 2000, 1980, LibraryQuery.UNDATED), groups.keys.toList())
        assertEquals(listOf("Blade Runner 2049"), groups[2010]?.map { it.title })
    }

    @Test
    fun facetsCountGenresMostCommonFirst() {
        val facets = LibraryQuery.genreFacets(items)
        // Ties break alphabetically, so the facet list is stable regardless of scan order.
        assertEquals(listOf("Drama" to 2, "Science Fiction" to 2, "Action" to 1, "Crime" to 1), facets)
    }

    @Test
    fun sectionsSplitTheLibraryIntoMoviesAndShows() {
        val library = Library(movies = items.filterIsInstance<Movie>(), shows = items.filterIsInstance<Show>())
        assertEquals(listOf("Blade Runner 2049", "Blade Runner", "Undated Short"), library.itemsIn(LibrarySection.MOVIES).map { it.title })
        assertEquals(listOf("Breaking Bad"), library.itemsIn(LibrarySection.SHOWS).map { it.title })
    }

    @Test
    fun browseAppliesAgeGenreDecadeAndSortTogether() {
        val filter = BrowseFilter(genres = setOf("Science Fiction"), sort = SortOrder.YEAR_OLD)
        assertEquals(listOf("Blade Runner", "Blade Runner 2049"), LibraryQuery.browse(items, filter).map { it.title })
        assertEquals(
            listOf("Blade Runner 2049"),
            LibraryQuery.browse(items, BrowseFilter(decade = 2010)).map { it.title },
        )
        assertEquals(
            listOf("Blade Runner", "Blade Runner 2049", "Breaking Bad", "Undated Short"),
            LibraryQuery.browse(items, BrowseFilter()).map { it.title },
        )
    }

    @Test
    fun newestFirstPutsUndatedItemsLast() {
        assertEquals(
            listOf("Blade Runner 2049", "Breaking Bad", "Blade Runner", "Undated Short"),
            LibraryQuery.browse(items, BrowseFilter(sort = SortOrder.YEAR_NEW)).map { it.title },
        )
    }

    @Test
    fun browseHonoursTheMaximumAge() {
        val rated = listOf(
            Movie(title = "Kids Film", minAge = 6, path = "/m/kids", videoPath = "/m/kids/k.mkv"),
            Movie(title = "Teen Film", minAge = 16, path = "/m/teen", videoPath = "/m/teen/t.mkv"),
        )
        assertEquals(listOf("Kids Film"), LibraryQuery.browse(rated, BrowseFilter(), maxAgeYears = 12).map { it.title })
    }

    @Test
    fun featuredPrefersWhatIsBeingWatchedThenAnythingWithABackdrop() {
        val withArt = listOf(
            Movie(title = "Alien", path = "/m/a", videoPath = "/m/a/a.mkv"),
            Movie(title = "Brazil", path = "/m/b", videoPath = "/m/b/b.mkv", fanartPath = "/m/b/fanart.jpg"),
            Movie(title = "Cars", path = "/m/c", videoPath = "/m/c/c.mkv", fanartPath = "/m/c/fanart.jpg"),
        )
        assertEquals("Brazil", LibraryQuery.featured(withArt)?.title)
        assertEquals("Cars", LibraryQuery.featured(withArt, preferredKeys = listOf("movie:/m/c/c.mkv"))?.title)
        // A title in progress without a backdrop still beats nothing, but not a backdrop.
        assertEquals("Brazil", LibraryQuery.featured(withArt, preferredKeys = listOf("movie:/m/a/a.mkv"))?.title)
        assertEquals("Alien", LibraryQuery.featured(withArt.take(1))?.title)
        assertEquals(null, LibraryQuery.featured(emptyList()))
    }

    @Test
    fun aVideoLeadsBackToTheMovieOrShowItBelongsTo() {
        val heat = Movie(
            title = "Heat",
            path = "/m/heat",
            videoPath = "/m/heat/cd1.avi",
            parts = listOf("/m/heat/cd1.avi", "/m/heat/cd2.avi"),
        )
        val dark = Show(
            title = "Dark",
            path = "/s/dark",
            seasons = listOf(Season(1, listOf(Episode(number = 2, videoPath = "/s/dark/s01e02.mkv")))),
        )
        val library = Library(movies = listOf(heat), shows = listOf(dark))
        assertEquals(heat, LibraryQuery.itemForVideo(library, "/m/heat/cd2.avi"))
        assertEquals(dark, LibraryQuery.itemForVideo(library, "/s/dark/s01e02.mkv"))
        assertEquals(null, LibraryQuery.itemForVideo(library, "/elsewhere.mkv"))
    }
}
