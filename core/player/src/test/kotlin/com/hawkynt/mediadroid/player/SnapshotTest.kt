package com.hawkynt.mediadroid.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotTest {
    @Test
    fun `a snapshot is named after the title and the position as hours, minutes and seconds`() {
        assertEquals("Title 01-23-45.png", Snapshot.fileName("Title", 5_025_000L))
        assertEquals("Up 00-00-00.png", Snapshot.fileName("Up", 0L))
        // Milliseconds are dropped, not rounded: the picture is from that second.
        assertEquals("Up 00-00-59.png", Snapshot.fileName("Up", 59_999L))
        // Past a hundred hours the hours simply grow.
        assertEquals("Up 100-00-00.png", Snapshot.fileName("Up", 360_000_000L))
        // A position before the start (a seek in flight) counts as the start.
        assertEquals("Up 00-00-00.png", Snapshot.fileName("Up", -5L))
    }

    @Test
    fun `the digits are Latin whatever the system's language`() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"))
            assertEquals("Up 01-02-03.png", Snapshot.fileName("Up", 3_723_000L))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test
    fun `characters no file system takes become spaces`() {
        assertEquals("AC DC Live 00-00-01.png", Snapshot.fileName("AC/DC: Live", 1_000L))
        assertEquals("What If 00-00-01.png", Snapshot.fileName("What If?", 1_000L))
        assertEquals("a b c d e f g 00-00-01.png", Snapshot.fileName("a\\b*c\"d<e>f|g", 1_000L))
        assertEquals("Tab Line 00-00-01.png", Snapshot.fileName("Tab\tLine\n", 1_000L))
    }

    @Test
    fun `spaces collapse and dots and spaces leave the ends`() {
        assertEquals("Mr. Robot", Snapshot.sanitise("  Mr.   Robot  "))
        assertEquals("Ends", Snapshot.sanitise("Ends..."))
        assertEquals("hidden", Snapshot.sanitise(".hidden"))
    }

    @Test
    fun `a title with nothing left is a snapshot`() {
        assertEquals("Snapshot", Snapshot.sanitise(""))
        assertEquals("Snapshot", Snapshot.sanitise("???"))
        assertEquals("Snapshot 00-00-02.png", Snapshot.fileName("  ", 2_000L))
    }

    @Test
    fun `the names Windows keeps for devices are not taken`() {
        assertEquals("CON_", Snapshot.sanitise("CON"))
        assertEquals("nul_", Snapshot.sanitise("nul"))
        assertEquals("COM1_", Snapshot.sanitise("COM1"))
        assertEquals("LPT9_", Snapshot.sanitise("lpt9").uppercase())
        assertEquals("Console", Snapshot.sanitise("Console"))
    }

    @Test
    fun `a long title is cut so the whole name fits a file system`() {
        val name = Snapshot.fileName("x".repeat(500), 1_000L)
        assertEquals(Snapshot.MAX_TITLE + " 00-00-01.png".length, name.length)
        // Cut at a character, never inside a surrogate pair.
        val emoji = "🎬".repeat(200)
        val cut = Snapshot.sanitise(emoji)
        assertTrue(cut.length <= Snapshot.MAX_TITLE)
        assertTrue(!Character.isHighSurrogate(cut.last()))
    }

    @Test
    fun `a name already taken gets the next free number, never overwriting`() {
        val taken = setOf("Up 00-00-01.png", "Up 00-00-01 (2).png")
        assertEquals("Up 00-00-01 (3).png", Snapshot.freeName("Up 00-00-01.png") { it in taken })
        assertEquals("Up 00-00-02.png", Snapshot.freeName("Up 00-00-02.png") { it in taken })
    }

    @Test
    fun `rows read back from the bottom come out from the top, opaque`() {
        // 2 × 2: bottom row red then green, top row blue then white, alpha anything.
        val bottomUp = byteArrayOf(
            -1, 0, 0, 0, 0, -1, 0, 10,
            0, 0, -1, 77, -1, -1, -1, -1,
        )
        val upright = Snapshot.upright(bottomUp, 2, 2)
        assertArrayEquals(
            byteArrayOf(
                0, 0, -1, -1, -1, -1, -1, -1,
                -1, 0, 0, -1, 0, -1, 0, -1,
            ),
            upright,
        )
    }
}
