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
}
