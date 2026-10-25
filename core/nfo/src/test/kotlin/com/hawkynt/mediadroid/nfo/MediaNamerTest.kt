package com.hawkynt.mediadroid.nfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaNamerTest {

    @Test
    fun readsStandardEpisodeNames() {
        assertEquals(EpisodeRef(1, 2), episodeRefFromName("S01E02 - The Rains of Castamere.mkv"))
        assertEquals(EpisodeRef(1, 2), episodeRefFromName("s01e02.mkv"))
        assertEquals(EpisodeRef(10, 23), episodeRefFromName("Show.S10E23.1080p.mkv"))
        assertEquals(EpisodeRef(1, 2), episodeRefFromName("1x02 - Title.mp4"))
    }

    @Test
    fun namesWithoutEpisodeReferenceYieldNull() {
        assertNull(episodeRefFromName("Blade.Runner.2049.2017.2160p.mkv"))
        assertNull(episodeRefFromName("folder.jpg"))
    }
}
