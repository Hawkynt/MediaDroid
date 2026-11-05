package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTextTest {

    @Test
    fun `an ASS event's text is everything after its eight fields`() {
        assertEquals("Hello, world", SubtitleText.fromAssEvent("0,0,Default,,0,0,0,,Hello, world"))
    }

    @Test
    fun `override tags go, line breaks become new lines`() {
        assertEquals(
            "Not now.\nLater, maybe.",
            SubtitleText.fromAssEvent("3,0,Default,,0,0,0,,{\\i1}Not{\\i0} now.\\NLater, {\\b1}maybe{\\b0}."),
        )
        assertEquals("a b", SubtitleText.fromAssEvent("0,0,Default,,0,0,0,,a\\hb"))
    }

    @Test
    fun `a drawing shows nothing`() {
        assertEquals("", SubtitleText.fromAssEvent("0,0,Default,,0,0,0,,{\\p1}m 0 0 l 100 0 100 100{\\p0}"))
    }

    @Test
    fun `plain text without event fields passes as it is, trimmed`() {
        assertEquals("Just text", SubtitleText.fromAssEvent("  Just text "))
    }
}
