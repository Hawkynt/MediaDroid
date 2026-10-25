package com.hawkynt.mediadroid.nfo

import org.junit.Assert.assertEquals
import org.junit.Test

class ShowNfoTest {

    private val breakingBad = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <tvshow>
          <title>Breaking Bad</title>
          <plot>A high school chemistry teacher diagnosed with inoperable lung cancer turns to manufacturing and selling methamphetamine.</plot>
          <premiered>2008-01-20</premiered>
          <status>Ended</status>
          <mpaa>TV-MA</mpaa>
          <studio>Sony Pictures Television</studio>
          <genre>Crime</genre>
          <genre>Drama</genre>
          <imdbid>tt0903747</imdbid>
        </tvshow>
    """.trimIndent()

    @Test
    fun readsShowFields() {
        val show = parseShowNfo(breakingBad)
        assertEquals("Breaking Bad", show.title)
        assertEquals(2008, show.firstAiredYear)
        assertEquals("Ended", show.status)
        assertEquals("TV-MA", show.mpaa)
        assertEquals(listOf("Crime", "Drama"), show.genres)
        assertEquals("tt0903747", show.imdbId)
    }
}
