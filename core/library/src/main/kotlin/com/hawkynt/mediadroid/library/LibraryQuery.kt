package com.hawkynt.mediadroid.library

/** How the poster wall is ordered. */
enum class SortOrder { TITLE, YEAR_NEW, YEAR_OLD }

/** What the Filters screen narrows the poster wall to; the default shows everything A–Z. */
data class BrowseFilter(
    val genres: Set<String> = emptySet(),
    val decade: Int? = null,
    val sort: SortOrder = SortOrder.TITLE,
)

/**
 * The filtering and grouping the browse screens run on. Everything here is pure so
 * it stays fast on a shelf tablet and testable without a device.
 */
object LibraryQuery {

    /** Case-insensitive substring match on the title. */
    fun search(items: List<MediaItem>, query: String): List<MediaItem> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return items
        return items.filter { it.title.lowercase().contains(needle) }
    }

    /** Items carrying every genre in [genres]; an empty set means no filter. */
    fun filterByGenres(items: List<MediaItem>, genres: Set<String>): List<MediaItem> {
        if (genres.isEmpty()) return items
        return items.filter { item -> genres.all { genre -> item.genres.any { it.equals(genre, ignoreCase = true) } } }
    }

    /**
     * Items a viewer of [maxAgeYears] may see: anything certified at or below the
     * age, and anything with no certification at all (unknown is not forbidden).
     * [maxAgeYears] of 0 means the max-age mode is off.
     */
    fun filterByAge(items: List<MediaItem>, maxAgeYears: Int): List<MediaItem> {
        if (maxAgeYears <= 0) return items
        return items.filter { item ->
            val age = item.minAge
            age == null || age <= maxAgeYears
        }
    }

    /** Items whose year falls in [from]..=[to]; null bounds are open. */
    fun filterByYear(items: List<MediaItem>, from: Int?, to: Int?): List<MediaItem> =
        items.filter { item ->
            val year = item.year
            year != null && (from == null || year >= from) && (to == null || year <= to)
        }

    /** Descending decade buckets, newest first; undated items land in [UNDATED]. */
    fun groupByYear(items: List<MediaItem>): Map<Int, List<MediaItem>> =
        items.groupBy { item -> item.year?.let { it / 10 * 10 } ?: UNDATED }
            .toSortedMap(compareByDescending { it })

    /** Every genre present, each with how many items carry it, most common first. */
    fun genreFacets(items: List<MediaItem>): List<Pair<String, Int>> =
        items.flatMap { it.genres }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })

    /** The poster wall: [items] cut down by the max-age mode and [filter], then sorted. */
    fun browse(items: List<MediaItem>, filter: BrowseFilter, maxAgeYears: Int = 0): List<MediaItem> {
        var result = filterByAge(items, maxAgeYears)
        result = filterByGenres(result, filter.genres)
        filter.decade?.let { result = filterByYear(result, it, it + 9) }
        return when (filter.sort) {
            SortOrder.TITLE -> result.sortedBy { it.title.lowercase() }
            SortOrder.YEAR_NEW -> result.sortedWith(compareBy<MediaItem> { it.year == null }.thenByDescending { it.year })
            SortOrder.YEAR_OLD -> result.sortedWith(compareBy<MediaItem> { it.year == null }.thenBy { it.year })
        }
    }

    /**
     * The title a hero banner shows: the first of [preferredKeys] (what is being watched) that has
     * a backdrop, else any item with a backdrop, else simply the first item.
     */
    fun featured(items: List<MediaItem>, preferredKeys: List<String> = emptyList()): MediaItem? {
        val byKey = items.associateBy { it.key }
        return preferredKeys.firstNotNullOfOrNull { key -> byKey[key]?.takeIf { it.fanartPath != null } }
            ?: items.firstOrNull { it.fanartPath != null }
            ?: items.firstOrNull()
    }

    const val UNDATED = -1
}
