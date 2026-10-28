package com.hawkynt.mediadroid.library

/** How the poster wall is ordered. Items without the value sorted by go last. */
enum class SortOrder {
    TITLE,
    TITLE_DESC,
    YEAR_NEW,
    YEAR_OLD,

    /** Recently added first. */
    ADDED,

    /** Shows only: the one with the newest episode first. */
    UPDATED,
    RUNTIME_SHORT,
    RUNTIME_LONG,

    /** Best rated first. */
    RATING,
}

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
        return sort(result, filter.sort)
    }

    /** [items] in [order], the title breaking ties. */
    fun sort(items: List<MediaItem>, order: SortOrder): List<MediaItem> {
        val byTitle = compareBy<MediaItem> { it.title.lowercase() }
        fun <T : Comparable<T>> by(descending: Boolean, value: (MediaItem) -> T?): Comparator<MediaItem> {
            val present = compareBy<MediaItem> { value(it) == null }
            val ordered = if (descending) present.thenByDescending(value) else present.thenBy(value)
            return ordered.then(byTitle)
        }
        return items.sortedWith(
            when (order) {
                SortOrder.TITLE -> byTitle
                SortOrder.TITLE_DESC -> byTitle.reversed()
                SortOrder.YEAR_NEW -> by(descending = true) { it.year }
                SortOrder.YEAR_OLD -> by(descending = false) { it.year }
                SortOrder.ADDED -> by(descending = true, ::addedOf)
                SortOrder.UPDATED -> by(descending = true) { (it as? Show)?.updatedMs ?: addedOf(it) }
                SortOrder.RUNTIME_SHORT -> by(descending = false) { (it as? Movie)?.runtimeMinutes }
                SortOrder.RUNTIME_LONG -> by(descending = true) { (it as? Movie)?.runtimeMinutes }
                SortOrder.RATING -> by(descending = true, ::ratingOf)
            },
        )
    }

    /**
     * Where each letter starts in a wall sorted by title: the letter (accents folded, `Ä` is `A`; digits
     * and symbols are `#`) and the index of its first title, in the wall's own order.
     */
    fun letterIndex(items: List<MediaItem>): List<Pair<Char, Int>> {
        val seen = LinkedHashMap<Char, Int>()
        items.forEachIndexed { i, item -> seen.putIfAbsent(letterOf(item.title), i) }
        return seen.toList()
    }

    private fun letterOf(title: String): Char {
        val first = java.text.Normalizer.normalize(title.trim().take(1), java.text.Normalizer.Form.NFD)
            .firstOrNull()?.uppercaseChar() ?: return '#'
        return if (first in 'A'..'Z') first else '#'
    }

    /**
     * The orders worth offering for [items]: runtime only where some item knows its runtime, recently
     * updated only for shows, rating only where an NFO carries one.
     */
    fun sortOrders(items: List<MediaItem>): List<SortOrder> = SortOrder.entries.filter { order ->
        when (order) {
            SortOrder.UPDATED -> items.any { it is Show }
            SortOrder.RUNTIME_SHORT, SortOrder.RUNTIME_LONG -> items.any { (it as? Movie)?.runtimeMinutes != null }
            SortOrder.RATING -> items.any { ratingOf(it) != null }
            else -> true
        }
    }

    private fun addedOf(item: MediaItem): Long? = when (item) {
        is Movie -> item.addedMs
        is Show -> item.addedMs
    }

    private fun ratingOf(item: MediaItem): Double? = when (item) {
        is Movie -> item.rating
        is Show -> item.rating
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

    /** The movie (any of its parts) or show (any of its episodes) the video at [videoPath] belongs to. */
    fun itemForVideo(library: Library, videoPath: String): MediaItem? =
        library.movies.firstOrNull { videoPath in it.parts }
            ?: library.shows.firstOrNull { show ->
                show.seasons.any { season -> season.episodes.any { it.videoPath == videoPath } }
            }
}
