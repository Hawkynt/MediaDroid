package com.hawkynt.mediadroid.player

import com.hawkynt.mediadroid.library.Episode
import com.hawkynt.mediadroid.library.Season
import org.junit.Assert.assertEquals
import org.junit.Test

class BingeQueueTest {

    private val seasons = listOf(
        Season(number = 1, episodes = listOf(Episode(1, watched = true), Episode(2, watched = true), Episode(3))),
        Season(number = 2, episodes = listOf(Episode(2), Episode(1))),
    )

    @Test
    fun nextIsTheFirstUnwatchedInOrder() {
        val next = BingeQueue.next(seasons)!!
        assertEquals(1, next.season)
        assertEquals(3, next.episode.number)
        assertEquals(1, next.position)
        assertEquals(3, next.total)
    }

    @Test
    fun queueRunsSeasonsThenEpisodesAndSkipsWatched() {
        val queued = BingeQueue.queue(seasons)
        assertEquals(listOf(1 to 3, 2 to 1, 2 to 2), queued.map { it.season to it.episode.number })
        assertEquals(listOf(1, 2, 3), queued.map { it.position })
    }

    @Test
    fun fullyWatchedShowHasNothingNext() {
        val watched = listOf(Season(1, listOf(Episode(1, watched = true))))
        assertEquals(null, BingeQueue.next(watched))
    }
}
