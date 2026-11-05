package com.hawkynt.mediadroid.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowPlaceTest {

    @Test
    fun `a place reads back as it was written`() {
        val place = WindowPlace(100f, 50f, 1400f, 900f, maximized = true)
        assertEquals("100,50,1400,900,max", place.toString())
        assertEquals(place, WindowPlace.parse(place.toString()))
        assertEquals(WindowPlace(-8f, 0f, 1280f, 800f, false), WindowPlace.parse("-8,0,1280,800"))
    }

    @Test
    fun `nothing, nonsense or a sliver of a window is no place to come back to`() {
        assertNull(WindowPlace.parse(null))
        assertNull(WindowPlace.parse("wide"))
        assertNull(WindowPlace.parse("0,0,1280"))
        assertNull(WindowPlace.parse("0,0,100,800"))
    }
}
