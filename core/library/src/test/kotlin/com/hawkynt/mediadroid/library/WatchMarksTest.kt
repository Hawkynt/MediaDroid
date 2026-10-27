package com.hawkynt.mediadroid.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchMarksTest {

    private val watched = mutableListOf<String>()
    private val unwatched = mutableListOf<String>()
    private val marks = WatchMarks(watched, unwatched)

    @Test
    fun withoutAMarkTheNfoDecides() {
        assertFalse(marks.isWatched("/a.mkv"))
        assertTrue(marks.isWatched("/a.mkv", nfoWatched = true))
    }

    @Test
    fun aMarkInTheAppBeatsTheNfoBothWays() {
        marks.set(listOf("/a.mkv"), watched = true)
        assertTrue(marks.isWatched("/a.mkv"))

        marks.set(listOf("/b.mkv"), watched = false)
        assertFalse("unmarking beats a playcount in the NFO", marks.isWatched("/b.mkv", nfoWatched = true))

        marks.set(listOf("/b.mkv"), watched = true)
        assertTrue(marks.isWatched("/b.mkv", nfoWatched = true))
        assertEquals(listOf("/a.mkv", "/b.mkv"), watched)
        assertEquals(emptyList<String>(), unwatched)
    }

    @Test
    fun aSeasonIsWatchedWhenEveryEpisodeIs() {
        val season = Season(
            1,
            listOf(
                Episode(number = 1, videoPath = "/s1e1.mkv", watched = true),
                Episode(number = 2, videoPath = "/s1e2.mkv"),
            ),
        )
        assertFalse(marks.isWatched(season))
        marks.set(season.episodes.map { it.videoPath }.drop(1), watched = true)
        assertTrue(marks.isWatched(season))
        marks.set(listOf("/s1e1.mkv"), watched = false)
        assertFalse(marks.isWatched(season))
        assertFalse("an empty season is not watched", marks.isWatched(Season(2)))
    }

    @Test
    fun aMovieIsWatchedWhenEveryPartIs() {
        val parts = listOf("/m/cd1.avi", "/m/cd2.avi")
        val movie = Movie(title = "Heat", path = "/m", videoPath = parts[0], parts = parts)
        marks.set(listOf("/m/cd1.avi"), watched = true)
        assertFalse(marks.isWatched(movie))
        marks.set(movie.parts, watched = true)
        assertTrue(marks.isWatched(movie))
        assertTrue(marks.isWatched(movie.copy(videoPath = "/n.mkv", parts = listOf("/n.mkv"), playcount = 1)))
    }
}
