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
    fun readsEpisodeRefThroughReleaseJunk() {
        assertEquals(EpisodeRef(1, 1), episodeRefFromName("Show.S01E01.720p.WEB-DL.DDP5.1.x264-GRP.mkv"))
        assertEquals(EpisodeRef(2, 7), episodeRefFromName("Some.Show.2x07.1080p.BluRay.x265-DEFLATE.mkv"))
    }

    @Test
    fun namesWithoutEpisodeReferenceYieldNull() {
        assertNull(episodeRefFromName("Blade.Runner.2049.2017.2160p.mkv"))
        assertNull(episodeRefFromName("folder.jpg"))
        assertNull(episodeRefFromName("Movie.2017.1080p.x264-GRP.mkv"))
        // Codec and channel-count junk must not masquerade as 1x264 / NxNN.
        assertNull(episodeRefFromName("Movie.5.1x264.mkv"))
        assertNull(episodeRefFromName("Movie.7.1x265.mkv"))
    }

    @Test
    fun bareEpisodeNumbersNeedTheSeasonFromElsewhere() {
        assertEquals(2, bareEpisodeFromName("E02 - Lies.mkv"))
        assertEquals(12, bareEpisodeFromName("e12.mkv"))
        assertEquals(5, bareEpisodeFromName("E5.mkv"))
        assertEquals(1, bareEpisodeFromName("EP001 - Romance Dawn.mkv"))
        assertEquals(7, bareEpisodeFromName("Ep7.mkv"))
        assertEquals(1071, bareEpisodeFromName("EP1071 - Luffy's Peak.mkv"))
        assertNull(bareEpisodeFromName("S01E02 - Lies.mkv"))
        assertNull(bareEpisodeFromName("Episode.mkv"))
        assertNull(bareEpisodeFromName("Epic (2013).mkv"))
    }
}
