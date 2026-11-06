package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.library.Episode
import com.hawkynt.mediadroid.library.Season
import com.hawkynt.mediadroid.library.Show
import org.junit.Assert.assertEquals
import org.junit.Test

class ShowQueueTest {

    private val show = Show(
        title = "Show",
        path = "/tv/Show",
        seasons = listOf(
            Season(2, listOf(Episode(2, videoPath = "/tv/Show/s2e2.mkv"), Episode(1, videoPath = "/tv/Show/s2e1.mkv"))),
            Season(1, listOf(Episode(1, videoPath = "/tv/Show/s1e1.mkv", watched = true))),
        ),
    )

    @Test
    fun `a show plays in binge order, seasons then episodes, the watched ones kept for a rewatch`() {
        val queue = showQueue(show)
        assertEquals(listOf("/tv/Show/s1e1.mkv", "/tv/Show/s2e1.mkv", "/tv/Show/s2e2.mkv"), queue.map { it.video })
        assertEquals(listOf("Show · S01E01", "Show · S02E01", "Show · S02E02"), queue.map { it.title })
    }
}
