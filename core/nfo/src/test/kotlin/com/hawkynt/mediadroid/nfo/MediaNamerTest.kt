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

    @Test
    fun aFolderOfThreeDigitNumbersIsSeasonAndEpisodeTheKodiWay() {
        val numbered = numberedEpisodes(
            listOf(
                "Bionic Six 101 - Das erste Abenteuer.avi",
                "Bionic Six 108 - Zurück in die Vergangenheit 1.avi",
                "Bionic Six 243 - Das war's Leute.avi",
            ),
        )!!
        assertEquals(
            listOf(EpisodeRef(1, 1), EpisodeRef(1, 8), EpisodeRef(2, 43)),
            numbered.values.map { it.ref },
        )
        assertEquals(
            listOf("Das erste Abenteuer", "Zurück in die Vergangenheit 1", "Das war's Leute"),
            numbered.values.map { it.title },
        )
    }

    @Test
    fun zeroPaddedNumbersAreAbsoluteEpisodesOfSeasonOne() {
        val numbered = numberedEpisodes(
            listOf("sr.01.die.star.sheriffs.avi", "sr.22.die.geiseln.avi", "sr.22.nur.zirkus.mit.dem.zirkus.avi"),
        )!!
        assertEquals(listOf(EpisodeRef(1, 1), EpisodeRef(1, 22), EpisodeRef(1, 22)), numbered.values.map { it.ref })
        // A scene-style name carries no clean title.
        assertEquals(listOf(null, null, null), numbered.values.map { it.title })
        assertEquals(
            listOf(EpisodeRef(3, 1), EpisodeRef(3, 12)),
            numberedEpisodes(listOf("001 - Pilot.mkv", "012 - Finale.mkv"), defaultSeason = 3)!!.values.map { it.ref },
        )
    }

    @Test
    fun numberedNamesExtendingTheSeriesNameAreSpecials() {
        val numbered = numberedEpisodes(
            listOf(
                "sr.01.die.star.sheriffs.avi",
                "sr.02.der.vierbeinige.freund.avi",
                "sr.le.1.taeuschungen.avi",
                "sr.le.2.kaeptn.holliday.avi",
            ),
        )!!
        assertEquals(
            listOf(EpisodeRef(1, 1), EpisodeRef(1, 2), EpisodeRef(0, 1), EpisodeRef(0, 2)),
            numbered.values.map { it.ref },
        )
        // Specials need a series to extend: more of them than episodes is no series.
        assertNull(numberedEpisodes(listOf("sr.01.a.avi", "sr.le.1.b.avi", "sr.le.2.c.avi")))
    }

    @Test
    fun numbersCountOnlyWhenTheWholeFolderAgrees() {
        // One video is no series.
        assertNull(numberedEpisodes(listOf("Bionic Six 101 - Das erste Abenteuer.avi")))
        // Different names in front: movies that happen to carry a number.
        assertNull(numberedEpisodes(listOf("Fahrenheit 451 (1966).avi", "Blade Runner 2049 (2017).mkv")))
        // Years are no season and episode.
        assertNull(numberedEpisodes(listOf("Show 1999 - A.avi", "Show 2001 - B.avi")))
        // Unpadded small numbers are a movie series, not episodes.
        assertNull(numberedEpisodes(listOf("Rocky 2.avi", "Rocky 3.avi")))
        // Every video must carry the number.
        assertNull(numberedEpisodes(listOf("sr.01.die.star.sheriffs.avi", "sr.bonus.avi")))
    }
}
