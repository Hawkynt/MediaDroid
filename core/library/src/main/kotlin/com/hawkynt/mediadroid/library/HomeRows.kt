package com.hawkynt.mediadroid.library

/** The rows a wall's home shows under Continue watching; each opens in full through "See all". */
enum class HomeRow(val label: String) {
    /** Shows only: those whose newest episode is not watched yet, the newest first. */
    NEW_EPISODES("New episodes"),

    /** By when each title arrived, the newest first. */
    RECENTLY_ADDED("Recently added"),

    /** Nothing of it watched yet, in the wall's own order. */
    NOT_WATCHED("Not watched yet"),
}

/** What goes into each [HomeRow]; pure, so the rows are tested without a device. */
object HomeRows {

    /** The rows a [section]'s home offers, top to bottom. */
    fun rowsFor(section: LibrarySection): List<HomeRow> = when (section) {
        LibrarySection.MOVIES -> listOf(HomeRow.RECENTLY_ADDED, HomeRow.NOT_WATCHED)
        LibrarySection.SHOWS -> listOf(HomeRow.NEW_EPISODES, HomeRow.RECENTLY_ADDED, HomeRow.NOT_WATCHED)
    }

    /** The titles of [row] out of [items] (already filtered and sorted for the wall), judged by [marks]. */
    fun items(row: HomeRow, items: List<MediaItem>, marks: WatchMarks): List<MediaItem> = when (row) {
        HomeRow.RECENTLY_ADDED -> LibraryQuery.sort(items.filter { addedOf(it) != null }, SortOrder.ADDED)
        HomeRow.NOT_WATCHED -> items.filter { !startedAny(it, marks) }
        HomeRow.NEW_EPISODES -> items.filterIsInstance<Show>()
            .filter { show -> newestEpisode(show)?.let { !marks.isWatched(it.videoPath, it.watched) } == true }
            .sortedByDescending { it.updatedMs ?: Long.MIN_VALUE }
    }

    private fun addedOf(item: MediaItem): Long? = when (item) {
        is Movie -> item.addedMs
        is Show -> item.addedMs
    }

    private fun startedAny(item: MediaItem, marks: WatchMarks): Boolean = when (item) {
        is Movie -> item.parts.any { marks.isWatched(it, item.playcount > 0) }
        is Show -> item.seasons.any { s -> s.episodes.any { marks.isWatched(it.videoPath, it.watched) } }
    }

    /** The episode added last; by season and number when no times are known. */
    private fun newestEpisode(show: Show): Episode? {
        val episodes = show.seasons.sortedBy { it.number }.flatMap { s -> s.episodes.sortedBy { it.number } }
        return episodes.maxByOrNull { it.addedMs ?: Long.MIN_VALUE }?.takeIf { it.addedMs != null }
            ?: episodes.lastOrNull()
    }
}
