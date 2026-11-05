package com.hawkynt.mediadroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TracksTest {

    private fun text(id: String, language: String?) = Track(id, Track.Kind.TEXT, language, null)

    private val tracks = listOf(
        Track("a1", Track.Kind.AUDIO, "deu", null),
        text("t1", "eng"),
        text("t2", "deu"),
        text("t3", "deu"),
    )

    @Test
    fun `the household's subtitle language picks the first text track in it`() {
        assertEquals("t2", Tracks.preferredText(tracks, "deu")?.id)
    }

    @Test
    fun `no preference, or none in that language, picks nothing`() {
        assertNull(Tracks.preferredText(tracks, ""))
        assertNull(Tracks.preferredText(tracks, "fra"))
    }

    @Test
    fun `an audio track never counts as a subtitle`() {
        assertNull(Tracks.preferredText(listOf(Track("a1", Track.Kind.AUDIO, "fra", null)), "fra"))
    }

    @Test
    fun `tracks are grouped by kind in their order`() {
        assertEquals(listOf("t1", "t2", "t3"), Tracks.of(tracks, Track.Kind.TEXT).map { it.id })
    }

    @Test
    fun `a track names the file it comes from, the video itself by default`() {
        assertEquals(0, Track("a1", Track.Kind.AUDIO, "deu", null).source)
        assertEquals(2, Track("a2", Track.Kind.AUDIO, "eng", "Director's commentary", source = 2).source)
    }
}
