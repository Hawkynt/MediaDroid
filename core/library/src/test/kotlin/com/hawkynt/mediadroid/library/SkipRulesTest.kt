package com.hawkynt.mediadroid.library

import com.hawkynt.mediadroid.nfo.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Test

class SkipRulesTest {

    private val chapters = listOf(
        Chapter(0, 2000, "Chapter 1"),
        Chapter(2000, 5000, "Intro"),
        Chapter(5000, 120000, "Real start"),
        Chapter(120000, -1, "End credits"),
    )

    @Test
    fun introChapterEndIsTheSkipTarget() {
        assertEquals(5000L, SkipRules.introEndMs(chapters, null))
    }

    @Test
    fun nfoSpanIsUsedWhenNoChapterMatches() {
        assertEquals(130_000L, SkipRules.introEndMs(emptyList(), TimeRange(70, 130)))
        assertEquals(130_000L, SkipRules.introEndMs(listOf(Chapter(0, 2000, "Chapter 1")), TimeRange(70, 130)))
    }

    @Test
    fun chaptersWinOverNfoSpans() {
        assertEquals(5000L, SkipRules.introEndMs(chapters, TimeRange(70, 130)))
    }

    @Test
    fun creditsChapterStartIsTheJumpTarget() {
        assertEquals(120_000L, SkipRules.creditsStartMs(chapters, null))
    }

    @Test
    fun nfoCreditsSpanIsUsedWhenNoChapterMatches() {
        assertEquals(1180_000L, SkipRules.creditsStartMs(emptyList(), TimeRange(1180, 1260)))
    }

    @Test
    fun germanChapterTitlesMatchToo() {
        val german = listOf(Chapter(0, 4000, "Vorspann"), Chapter(4000, 90000, "Hauptfilm"), Chapter(90000, -1, "Abspann"))
        assertEquals(4000L, SkipRules.introEndMs(german, null))
        assertEquals(90_000L, SkipRules.creditsStartMs(german, null))
    }

    @Test
    fun nothingAttributedMeansNoSkip() {
        assertEquals(null, SkipRules.introEndMs(emptyList(), null))
        assertEquals(null, SkipRules.creditsStartMs(chapters.take(3), null))
    }

    @Test
    fun introSpanComesFromTheChapterOrTheNfo() {
        assertEquals(2000L..5000L, SkipRules.introSpanMs(chapters, null))
        assertEquals(70_000L..130_000L, SkipRules.introSpanMs(emptyList(), TimeRange(70, 130)))
        assertEquals(null, SkipRules.introSpanMs(emptyList(), null))
    }

    @Test
    fun playbackInsideTheIntroJumpsToItsEnd() {
        val intro = 70_000L..130_000L
        assertEquals(130_000L, SkipRules.skipTarget(70_500L, intro))
        assertEquals(60_000L, SkipRules.skipTarget(0L, 0L..60_000L))
        assertEquals(null, SkipRules.skipTarget(65_000L, intro))
        // The last second is left alone, or a seek that lands just short would loop.
        assertEquals(null, SkipRules.skipTarget(129_500L, intro))
        assertEquals(null, SkipRules.skipTarget(200_000L, intro))
        assertEquals(null, SkipRules.skipTarget(70_500L, null))
    }

    @Test
    fun aColdOpenPlaysUntilTheIntroBegins() {
        assertEquals(null, SkipRules.skipTarget(0L, 70_000L..130_000L))
    }
}
