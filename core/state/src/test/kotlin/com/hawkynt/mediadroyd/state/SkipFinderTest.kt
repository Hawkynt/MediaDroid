package com.hawkynt.mediadroyd.state

import com.hawkynt.mediadroyd.player.IntroCredits
import com.hawkynt.mediadroyd.player.NativeFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SkipFinderTest {
    private class Memory(var text: String? = null) : StateFile {
        override fun read(): String? = text
        override fun write(text: String) {
            this.text = text
        }
    }

    private fun items(seconds: Double) = (seconds / NativeFingerprint.ITEM_SECONDS).toInt()
    private fun noise(seconds: Double, seed: Int) = Random(seed).let { r -> IntArray(items(seconds)) { r.nextInt() } }

    private val theme = noise(50.0, 1)
    private val credits = noise(60.0, 2)

    // Episode n: a cold open of 10 * n seconds, the theme, its own story; its closing (from 2000 s) the credits music.
    private fun opening(n: Int) = noise(10.0 * n + 5, 100 + n) + theme + noise(400.0, 200 + n)
    private fun closing(n: Int) = noise(300.0, 300 + n) + credits + noise(20.0, 400 + n)

    private val asked = ArrayList<String>()
    private val episodes = (1..4).map { "/show/e$it.mkv" }
    private fun finder(store: StateStore) = SkipFinder(store) { location, closing ->
        asked += "$location ${if (closing) "closing" else "opening"}"
        val n = episodes.indexOf(location) + 1
        if (closing) IntroCredits.SoundPrint(closing(n), 2_000_000) else IntroCredits.SoundPrint(opening(n), 0)
    }

    @Test
    fun `the episode playing and the next are looked at, each intro and credits kept in its own time`() {
        val store = StateStore(Memory())
        finder(store).around(episodes, 1)
        // e2 with e3, then e3 with e4: e2's and e3's known before e3 starts.
        for (n in listOf(2, 3, 4)) assertTrue(store.hasLookedForSkips(episodes[n - 1]))
        assertFalse(store.hasLookedForSkips(episodes[0]))
        assertNotNull(store.foundSkipFor(episodes[2])?.intro)
        assertEquals(37_000.0, store.foundSkipFor(episodes[2])!!.introStartMs!!.toDouble(), 500.0)
        assertEquals(2_302_000.0, store.foundSkipFor(episodes[2])!!.creditsStartMs!!.toDouble(), 500.0)
    }

    @Test
    fun `what was looked at already is not looked at again, and the last episode pairs with the one before`() {
        val store = StateStore(Memory())
        finder(store).around(episodes, 3)
        assertTrue(store.hasLookedForSkips(episodes[3]))
        assertTrue(store.hasLookedForSkips(episodes[2]))
        asked.clear()
        finder(store).around(episodes, 3)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a video whose sound cannot be read is not marked as looked at, and a lone video is left alone`() {
        val store = StateStore(Memory())
        SkipFinder(store) { _, _ -> null }.around(episodes, 0)
        assertFalse(store.hasLookedForSkips(episodes[0]))
        finder(store).around(listOf(episodes[0]), 0)
        assertFalse(store.hasLookedForSkips(episodes[0]))
        assertNull(store.foundSkipFor(episodes[0]))
    }

    @Test
    fun `the intro of the episode playing comes first, from the openings alone, its credits after`() {
        val store = StateStore(Memory())
        val finder = finder(store)
        val first = finder.steps(episodes, 1).first()
        assertEquals(SkipFinder.Part.INTRO, first.part)
        finder.keep(first, requireNotNull(finder.find(first)))
        assertTrue(asked.none { it.endsWith("closing") })
        assertNotNull(store.foundSkipFor(episodes[1])?.intro)
        assertFalse(store.hasLookedForCredits(episodes[1]))
        assertEquals(SkipFinder.Part.CREDITS, finder.steps(episodes, 1).first().part)
    }

    @Test
    fun `each opening and closing is decoded once, however many pairs it is in`() {
        finder(StateStore(Memory())).around(episodes, 1)
        assertEquals(asked.size, asked.toSet().size)
    }
}
