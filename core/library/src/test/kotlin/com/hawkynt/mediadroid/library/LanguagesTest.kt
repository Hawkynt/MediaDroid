package com.hawkynt.mediadroid.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguagesTest {

    @Test
    fun bibliographicTerminologyAndTwoLetterCodesShareAFlag() {
        listOf("ger", "deu", "de", "GER").forEach { assertEquals(it, "🇩🇪", Languages.flag(it)) }
        listOf("fre", "fra", "fr").forEach { assertEquals(it, "🇫🇷", Languages.flag(it)) }
        assertEquals("🇬🇧", Languages.flag("eng"))
        assertEquals("🇯🇵", Languages.flag("jpn"))
    }

    @Test
    fun regionTagsAreIgnoredForTheLanguage() {
        assertEquals("🇩🇪", Languages.flag("de-AT"))
        assertEquals("🇬🇧", Languages.flag("en_US"))
    }

    @Test
    fun unknownCodesHaveNoFlagButStillALabel() {
        assertNull(Languages.flag("und"))
        assertNull(Languages.flag("xyz"))
        assertEquals("XYZ", Languages.label("xyz"))
        assertEquals("DE", Languages.label("ger"))
    }
}
