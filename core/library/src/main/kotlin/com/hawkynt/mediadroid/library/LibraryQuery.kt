package com.hawkynt.mediadroid.library

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

    const val UNDATED = -1
}
