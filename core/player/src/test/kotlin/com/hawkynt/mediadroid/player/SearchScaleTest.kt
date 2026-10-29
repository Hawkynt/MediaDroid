package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchScaleTest {

    private val frame = 40_000_000L // 25 frames a second

    @Test
    fun `it starts at the finest scale whose search picture is small enough`() {
        assertEquals(2, SearchScale(720, 480).scale)
        assertEquals(4, SearchScale(1920, 1080).scale)
        assertEquals(8, SearchScale(3840, 2160).scale)
        assertEquals(1, SearchScale(320, 240).scale)
    }

    @Test
    fun `a search that takes over half the frame goes one step coarser at once`() {
        val pace = SearchScale(720, 480)
        pace.report(searchNanos = 25_000_000L, intervalNanos = frame, late = false)
        assertEquals(4, pace.scale)
    }

    @Test
    fun `vectors that came too late to be used go one step coarser`() {
        val pace = SearchScale(720, 480)
        pace.report(searchNanos = 1_000_000L, intervalNanos = frame, late = true)
        assertEquals(4, pace.scale)
    }

    @Test
    fun `steady headroom for four times the work goes one step finer, but only after several frames`() {
        val pace = SearchScale(720, 480)
        repeat(SearchScale.CALM_FRAMES - 1) { pace.report(2_000_000L, frame, late = false) }
        assertEquals(2, pace.scale)
        pace.report(2_000_000L, frame, late = false)
        assertEquals(1, pace.scale)
    }

    @Test
    fun `a search without that headroom stays where it is`() {
        val pace = SearchScale(720, 480)
        repeat(20) { pace.report(8_000_000L, frame, late = false) }
        assertEquals(2, pace.scale)
    }

    @Test
    fun `it never searches finer than the picture nor coarser than two blocks each way`() {
        val small = SearchScale(320, 240)
        repeat(20) { small.report(100_000L, frame, late = false) }
        assertEquals(1, small.scale)
        val coarse = SearchScale(720, 480)
        repeat(20) { coarse.report(60_000_000L, frame, late = true) }
        // 720 / 8 = 90 and 480 / 8 = 60 hold two 16-pixel blocks each way; 480 / 16 = 30 would not.
        assertEquals(8, coarse.scale)
    }

    @Test
    fun `a finer step that goes coarse again soon after waits twice as long before the next try`() {
        val pace = SearchScale(720, 480)
        repeat(SearchScale.CALM_FRAMES) { pace.report(2_000_000L, frame, late = false) }
        assertEquals(1, pace.scale)
        pace.report(2_000_000L, frame, late = true)
        assertEquals(2, pace.scale)
        repeat(SearchScale.CALM_FRAMES) { pace.report(2_000_000L, frame, late = false) }
        assertEquals("not yet: the last try bounced", 2, pace.scale)
        repeat(SearchScale.CALM_FRAMES) { pace.report(2_000_000L, frame, late = false) }
        assertEquals(1, pace.scale)
    }

    @Test
    fun `a long steady stretch lets the wait shrink back`() {
        val pace = SearchScale(720, 480)
        repeat(SearchScale.CALM_FRAMES) { pace.report(2_000_000L, frame, late = false) }
        pace.report(2_000_000L, frame, late = true)
        // Steady at 2 for a long while (no headroom, no lateness), then calm again.
        repeat(200) { pace.report(8_000_000L, frame, late = false) }
        repeat(SearchScale.CALM_FRAMES) { pace.report(2_000_000L, frame, late = false) }
        assertEquals(1, pace.scale)
    }
}
