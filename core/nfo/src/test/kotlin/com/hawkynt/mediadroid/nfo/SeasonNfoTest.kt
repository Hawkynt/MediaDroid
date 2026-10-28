package com.hawkynt.mediadroid.nfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonNfoTest {

    @Test
    fun aSeasonNfoGivesItsTitleAndPlot() {
        val info = parseSeasonNfo(
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <season>
              <title>The Beginning</title>
              <plot>Jonas looks for Mikkel.</plot>
              <seasonnumber>1</seasonnumber>
            </season>
            """.trimIndent(),
        )
        assertEquals("The Beginning", info.title)
        assertEquals("Jonas looks for Mikkel.", info.plot)
        assertEquals(1, info.number)
    }

    @Test
    fun anOutlineStandsInForAMissingPlotAndNothingIsRequired() {
        assertEquals("Short.", parseSeasonNfo("<season><outline>Short.</outline></season>").plot)
        val empty = parseSeasonNfo("<season/>")
        assertNull(empty.title)
        assertNull(empty.plot)
    }
}
