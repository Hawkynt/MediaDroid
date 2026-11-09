package com.hawkynt.mediadroyd.player

import com.hawkynt.mediadroyd.nfo.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class IntroCreditsTest {
    private val step = NativeFingerprint.ITEM_SECONDS
    private fun items(seconds: Double) = (seconds / step).toInt()

    // A print of [seconds] of sound nobody else has.
    private fun noise(seconds: Double, seed: Int) = Random(seed).let { r -> IntArray(items(seconds)) { r.nextInt() } }

    // [print] as a re-encode leaves it: a bit flipped in one item of ten.
    private fun reencoded(print: IntArray, seed: Int) = Random(seed).let { r ->
        IntArray(print.size) { i -> if (r.nextInt(10) == 0) print[i] xor (1 shl r.nextInt(32)) else print[i] }
    }

    // Within three items (under 0.4 s): Chromaprint's smoothing blurs a segment's edges by a couple of items.
    private fun near(wantSeconds: Double, gotMs: Long) =
        assertEquals("at $gotMs ms", wantSeconds * 1000, gotMs.toDouble(), 3 * step * 1000)

    private val intro = noise(55.0, 1)

    @Test
    fun `the intro both openings share is found where it lies in each, after a cold open of its own length`() {
        val first = noise(90.0, 2) + intro + noise(400.0, 3)
        val second = noise(20.0, 4) + reencoded(intro, 5) + noise(500.0, 6)
        val found = requireNotNull(IntroCredits.intro(first, second))
        // Random items have no lookahead: each edge moved IntroCredits.LOOKAHEAD_MS later, then MARGIN_MS inside.
        near(92.0, found.first.startMs)
        near(145.0, found.first.endMs)
        near(22.0, found.second.startMs)
        near(75.0, found.second.endMs)
    }

    @Test
    fun `too short or too long a shared stretch is no intro, nor is nothing shared`() {
        val jingle = noise(6.0, 7)
        assertNull(IntroCredits.intro(noise(30.0, 8) + jingle + noise(300.0, 9), jingle + noise(300.0, 10)))
        val recap = noise(240.0, 11)
        assertNull(IntroCredits.intro(recap + noise(200.0, 12), recap + noise(200.0, 13)))
        assertNull(IntroCredits.intro(noise(300.0, 14), noise(300.0, 15)))
    }

    @Test
    fun `the credits start where the closing music both share begins, in each episode's own time`() {
        val credits = noise(70.0, 16)
        // The closings analysed: their last eight minutes, starting 1000 s and 1200 s into the episodes.
        val first = noise(400.0, 17) + credits + noise(10.0, 18)
        val second = noise(380.0, 19) + reencoded(credits, 20) + noise(30.0, 21)
        val found = requireNotNull(IntroCredits.credits(first, 1_000_000, second, 1_200_000))
        near(1402.0, found.first)
        near(1582.0, found.second)
        assertNull(IntroCredits.credits(noise(480.0, 22), 0, noise(480.0, 23), 0))
    }

    @Test
    fun `an episode's intro is the longest any of its neighbours shares with it`() {
        val own = noise(30.0, 24) + intro + noise(300.0, 25)
        val neighbours = listOf(noise(400.0, 26), noise(10.0, 27) + reencoded(intro, 28) + noise(300.0, 29))
        val found: TimeRange? = IntroCredits.introAmong(own, neighbours)
        near(32.0, found!!.startMs)
        assertEquals(true, abs(found.endMs - 85_000) < 1000)
    }
}
