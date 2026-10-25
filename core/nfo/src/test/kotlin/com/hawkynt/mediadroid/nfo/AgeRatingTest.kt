package com.hawkynt.mediadroid.nfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgeRatingTest {

    @Test
    fun `fsk certifications map to their age`() {
        assertEquals(16, minAgeOf("FSK 16"))
        assertEquals(16, minAgeOf("FSK ab 16"))
        assertEquals(12, minAgeOf("FSK 12 freigegeben ab 12 Jahren"))
        assertEquals(0, minAgeOf("FSK 0"))
    }

    @Test
    fun `bare numbers are taken as the age`() {
        assertEquals(6, minAgeOf("6"))
        assertEquals(18, minAgeOf("18"))
    }

    @Test
    fun `mpaa movie ratings map to sensible ages`() {
        assertEquals(0, minAgeOf("G"))
        assertEquals(6, minAgeOf("PG"))
        assertEquals(13, minAgeOf("PG-13"))
        assertEquals(13, minAgeOf("Rated PG-13"))
        assertEquals(18, minAgeOf("R"))
        assertEquals(18, minAgeOf("NC-17"))
    }

    @Test
    fun `tv ratings map to sensible ages`() {
        assertEquals(0, minAgeOf("TV-Y"))
        assertEquals(7, minAgeOf("TV-Y7"))
        assertEquals(7, minAgeOf("TV-Y7-FV"))
        assertEquals(0, minAgeOf("TV-G"))
        assertEquals(7, minAgeOf("TV-PG"))
        assertEquals(14, minAgeOf("TV-14"))
        assertEquals(18, minAgeOf("TV-MA"))
    }

    @Test
    fun `unknown or missing certifications carry no age`() {
        assertNull(minAgeOf(null))
        assertNull(minAgeOf(""))
        assertNull(minAgeOf("not a rating"))
    }
}
