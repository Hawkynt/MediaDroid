package com.hawkynt.mediadroid.library

import com.hawkynt.mediadroid.library.Sidecars.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class SidecarsTest {

    private val folder = listOf(
        "Heat (1995).mkv",
        "Heat (1995).nfo",
        "Heat (1995).srt",
        "Heat (1995).de.srt",
        "Heat (1995).en.forced.ass",
        "Heat (1995).eng.sdh.vtt",
        "Heat (1995).de.ac3",
        "Heat (1995).en.commentary.mka",
        "Heat (1995)-poster.jpg",
        "Heat 2 (2026).de.srt",
        "Heat (1995).de.txt",
    )

    @Test
    fun `subtitle and audio files named after the video belong to it, subtitles first`() {
        val sidecars = Sidecars.of("Heat (1995).mkv", folder)
        assertEquals(
            listOf(
                "Heat (1995).de.srt",
                "Heat (1995).en.forced.ass",
                "Heat (1995).eng.sdh.vtt",
                "Heat (1995).srt",
                "Heat (1995).de.ac3",
                "Heat (1995).en.commentary.mka",
            ),
            sidecars.map { it.name },
        )
        assertEquals(
            listOf(Kind.SUBTITLES, Kind.SUBTITLES, Kind.SUBTITLES, Kind.SUBTITLES, Kind.AUDIO, Kind.AUDIO),
            sidecars.map { it.kind },
        )
    }

    @Test
    fun `the name tells the language and what kind of track it is`() {
        val byName = Sidecars.of("Heat (1995).mkv", folder).associateBy { it.name }
        assertEquals(
            Sidecars.Sidecar("Heat (1995).de.srt", Kind.SUBTITLES, "de", "application/x-subrip"),
            byName["Heat (1995).de.srt"],
        )
        val forced = byName.getValue("Heat (1995).en.forced.ass")
        assertEquals("en", forced.language)
        assertEquals(true, forced.forced)
        assertEquals("text/x-ssa", forced.mimeType)
        val sdh = byName.getValue("Heat (1995).eng.sdh.vtt")
        assertEquals("en", sdh.language)
        assertEquals(true, sdh.hearingImpaired)
        assertEquals("text/vtt", sdh.mimeType)
        assertEquals(null, byName.getValue("Heat (1995).srt").language)
        val commentary = byName.getValue("Heat (1995).en.commentary.mka")
        assertEquals("en", commentary.language)
        assertEquals("commentary", commentary.label)
        assertEquals(null, commentary.mimeType)
    }

    @Test
    fun `a label says it in words`() {
        val byName = Sidecars.of("Heat (1995).mkv", folder).associateBy { it.name }
        assertEquals("DE", byName.getValue("Heat (1995).de.srt").describe())
        assertEquals("EN · forced", byName.getValue("Heat (1995).en.forced.ass").describe())
        assertEquals("EN · for the hard of hearing", byName.getValue("Heat (1995).eng.sdh.vtt").describe())
        assertEquals("EN · commentary", byName.getValue("Heat (1995).en.commentary.mka").describe())
        assertEquals("Heat (1995).srt", byName.getValue("Heat (1995).srt").describe())
    }

    @Test
    fun `nothing beside it, nothing found`() {
        assertEquals(emptyList<Sidecars.Sidecar>(), Sidecars.of("Coco.mkv", folder))
    }
}
