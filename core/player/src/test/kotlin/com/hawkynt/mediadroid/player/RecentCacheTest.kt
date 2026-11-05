package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentCacheTest {

    @Test
    fun keepsWhatWasPutUpToItsCapacity() {
        val cache = RecentCache<Int, String>(2)
        cache.put(1, "one")
        cache.put(2, "two")
        assertEquals("one", cache[1])
        assertEquals("two", cache[2])
    }

    @Test
    fun aFullCacheLetsGoOfTheLeastRecentlyUsed() {
        val cache = RecentCache<Int, String>(2)
        cache.put(1, "one")
        cache.put(2, "two")
        cache[1]
        cache.put(3, "three")
        assertNull(cache[2])
        assertEquals("one", cache[1])
        assertEquals(setOf(1, 3), cache.keys())
    }

    @Test
    fun putAgainReplacesWithoutGrowing() {
        val cache = RecentCache<Int, String>(2)
        cache.put(1, "one")
        cache.put(1, "uno")
        cache.put(2, "two")
        assertEquals("uno", cache[1])
        assertEquals(2, cache.keys().size)
    }

    @Test
    fun clearEmptiesIt() {
        val cache = RecentCache<Int, String>(2)
        cache.put(1, "one")
        cache.clear()
        assertNull(cache[1])
    }
}
