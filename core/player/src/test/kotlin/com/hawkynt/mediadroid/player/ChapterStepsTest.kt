package com.hawkynt.mediadroid.player

import com.hawkynt.mediadroid.library.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterStepsTest {

    private val chapters = listOf(
        Chapter(0, 60_000, "Opening"),
        Chapter(60_000, 300_000, ""),
        Chapter(300_000, 600_000, "Finale"),
    )

    @Test
    fun `the current chapter is the last one begun`() {
        assertEquals(0, ChapterSteps.current(chapters, 0))
        assertEquals(0, ChapterSteps.current(chapters, 59_999))
        assertEquals(1, ChapterSteps.current(chapters, 60_000))
        assertEquals(2, ChapterSteps.current(chapters, 900_000))
    }

    @Test
    fun `before the first chapter begins, none is current`() {
        val late = listOf(Chapter(10_000, 20_000, "Late"))
        assertNull(ChapterSteps.current(late, 5_000))
        assertNull(ChapterSteps.current(emptyList(), 5_000))
    }

    @Test
    fun `next goes to the start of the following chapter, none after the last`() {
        assertEquals(60_000L, ChapterSteps.next(chapters, 10_000))
        assertEquals(300_000L, ChapterSteps.next(chapters, 60_000))
        assertNull(ChapterSteps.next(chapters, 400_000))
        assertNull(ChapterSteps.next(emptyList(), 0))
    }

    @Test
    fun `previous goes back to the chapter's own start once a few seconds into it`() {
        assertEquals(60_000L, ChapterSteps.previous(chapters, 60_000 + ChapterSteps.BACK_THRESHOLD_MS + 1))
        assertEquals(300_000L, ChapterSteps.previous(chapters, 450_000))
    }

    @Test
    fun `previous goes to the chapter before when just past a chapter's start`() {
        assertEquals(0L, ChapterSteps.previous(chapters, 61_000))
        assertEquals(60_000L, ChapterSteps.previous(chapters, 300_000 + ChapterSteps.BACK_THRESHOLD_MS))
        // In the first chapter there is nothing before it: its start.
        assertEquals(0L, ChapterSteps.previous(chapters, 2_000))
        assertNull(ChapterSteps.previous(emptyList(), 2_000))
    }

    @Test
    fun `chapters read out of order are stepped through in time order`() {
        val shuffled = listOf(chapters[2], chapters[0], chapters[1])
        assertEquals(300_000L, ChapterSteps.next(shuffled, 100_000))
        // The index is into the list as given: the middle chapter sits last in it.
        assertEquals(2, ChapterSteps.current(shuffled, 100_000))
    }

    @Test
    fun `a chapter without a title is named by its number`() {
        assertEquals("Opening", ChapterSteps.name(chapters, 0))
        assertEquals("Chapter 2", ChapterSteps.name(chapters, 1))
    }
}
