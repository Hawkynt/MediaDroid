package com.hawkynt.mediadroid.library

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRowsTest {

    private val coco = Movie(title = "Coco", path = "/m/c", videoPath = "/m/c.mkv", addedMs = 300)
    private val heat = Movie(title = "Heat", path = "/m/h", videoPath = "/m/h.mkv", addedMs = 100, playcount = 1)
    private val alien = Movie(title = "Alien", path = "/m/a", videoPath = "/m/a.mkv")

    private fun show(title: String, vararg episodes: Pair<Long, Boolean>) = Show(
        title = title,
        path = "/s/$title",
        seasons = listOf(
            Season(
                1,
                episodes.mapIndexed { i, (added, watched) ->
                    Episode(i + 1, videoPath = "/s/$title/e${i + 1}.mkv", addedMs = added, watched = watched)
                },
            ),
        ),
    )

    private val marks = WatchMarks(mutableListOf(), mutableListOf())

    private fun titles(row: HomeRow, items: List<MediaItem>) = HomeRows.items(row, items, marks).map { it.title }

    @Test
    fun recentlyAddedIsNewestFirstAndLeavesOutWhatHasNoDate() {
        assertEquals(listOf("Coco", "Heat"), titles(HomeRow.RECENTLY_ADDED, listOf(alien, heat, coco)))
    }

    @Test
    fun notWatchedYetKeepsTheWallsOrderAndDropsAnythingStarted() {
        val started = show("Dark", 100L to true, 200L to false)
        val fresh = show("Lost", 100L to false)
        assertEquals(
            listOf("Alien", "Coco", "Lost"),
            titles(HomeRow.NOT_WATCHED, listOf(alien, heat, coco, started, fresh)),
        )
    }

    @Test
    fun newEpisodesAreShowsWhoseNewestEpisodeIsUnwatchedNewestFirst() {
        val dark = show("Dark", 100L to true, 900L to false)
        val lost = show("Lost", 100L to true, 500L to false)
        val done = show("Done", 100L to true, 950L to true)
        val unseen = show("Unseen", 800L to false)
        assertEquals(listOf("Dark", "Unseen", "Lost"), titles(HomeRow.NEW_EPISODES, listOf(lost, done, unseen, dark)))
    }

    @Test
    fun theAppsOwnMarksCountForTheRows() {
        val dark = show("Dark", 100L to false)
        marks.set(listOf("/s/Dark/e1.mkv"), watched = true)
        assertEquals(emptyList<String>(), titles(HomeRow.NOT_WATCHED, listOf(dark)))
        assertEquals(emptyList<String>(), titles(HomeRow.NEW_EPISODES, listOf(dark)))
    }

    @Test
    fun onlyShowsGetTheNewEpisodesRow() {
        assertEquals(listOf(HomeRow.RECENTLY_ADDED, HomeRow.NOT_WATCHED), HomeRows.rowsFor(LibrarySection.MOVIES))
        assertEquals(
            listOf(HomeRow.NEW_EPISODES, HomeRow.RECENTLY_ADDED, HomeRow.NOT_WATCHED),
            HomeRows.rowsFor(LibrarySection.SHOWS),
        )
    }
}
