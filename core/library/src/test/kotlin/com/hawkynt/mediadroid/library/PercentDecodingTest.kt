package com.hawkynt.mediadroid.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PercentDecodingTest {

    @Test
    fun `escapes decode as UTF-8, a plus stays a plus`() {
        assertEquals("Die Hard (1988)", PercentDecoding.decode("Die%20Hard%20%281988%29"))
        assertEquals("Amélie", PercentDecoding.decode("Am%C3%A9lie"))
        assertEquals("a+b", PercentDecoding.decode("a+b"))
        assertEquals("plain", PercentDecoding.decode("plain"))
    }

    @Test
    fun `what is not an escape stays as written`() {
        assertEquals("100%", PercentDecoding.decode("100%"))
        assertEquals("%zz and %-1", PercentDecoding.decode("%zz and %-1"))
        assertEquals("tail %4", PercentDecoding.decode("tail %4"))
    }
}
