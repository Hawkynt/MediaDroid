package com.hawkynt.mediadroid.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class DetailsLineTest {

    @Test
    fun yearAndGenresAreJoinedWithoutADanglingDot() {
        assertEquals("2010  ·  Drama · Crime", yearAndGenres(2010, listOf("Drama", "Crime")))
        assertEquals("2010", yearAndGenres(2010, emptyList()))
        assertEquals("Drama", yearAndGenres(null, listOf("Drama")))
        assertEquals("", yearAndGenres(null, emptyList()))
    }
}
