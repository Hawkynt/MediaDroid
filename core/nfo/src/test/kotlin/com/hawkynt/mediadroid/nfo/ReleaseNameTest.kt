package com.hawkynt.mediadroid.nfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseNameTest {

    private fun movie(raw: String) = ReleaseName.parse(raw)

    @Test
    fun sceneMovieNamesLoseEverythingAfterTheYear() {
        assertEquals(CleanName("The Big Short", 2015), movie("The.Big.Short.2015.German.DL.1080p.BluRay.x264-GRP"))
        assertEquals(
            CleanName("Mortal Kombat 2", 2026),
            movie("Mortal.Kombat.2.2026.MULTi.2160p.WEB-DL.DDP5.1.Atmos.DV.HEVC-FLUX"),
        )
    }

    @Test
    fun kodiStyleNamesKeepTheirTitle() {
        assertEquals(CleanName("Coco", 2017), movie("Coco (2017)"))
        assertEquals(CleanName("Blade Runner 2049", 2017), movie("Blade Runner 2049 (2017)"))
        assertEquals(CleanName("Mr. Robot", null), movie("Mr. Robot"))
    }

    @Test
    fun aNumberAtTheStartIsTitleNotYear() {
        assertEquals(CleanName("1917", 2019), movie("1917.2019.1080p.BluRay"))
        assertEquals(CleanName("2001 A Space Odyssey", 1968), movie("2001.A.Space.Odyssey.1968.REMASTERED.1080p"))
        assertEquals(CleanName("1917", null), movie("1917"))
    }

    @Test
    fun bracketTagsAndGroupSuffixesGo() {
        assertEquals(CleanName("Movie Name", 2010), movie("[GRP] Movie Name (2010) [1080p]"))
        assertEquals(CleanName("Heat", null), movie("Heat.1080p.BluRay.x264-SPARKS"))
        assertEquals(CleanName("Heat", null), movie("Heat_German_DL_720p"))
    }

    @Test
    fun onlyTheCleanFormsCarryAnEpisodeTitle() {
        assertEquals("Pilot", ReleaseName.episodeTitle("S01E01 - Pilot"))
        assertEquals("The Train Job", ReleaseName.episodeTitle("s01e02 - The Train Job"))
        assertEquals("Out of Gas", ReleaseName.episodeTitle("1x08 - Out of Gas"))
        assertEquals("Lies", ReleaseName.episodeTitle("E02 - Lies"))
        assertEquals("Romance Dawn", ReleaseName.episodeTitle("EP001 - Romance Dawn"))
        assertEquals("Wake Up", ReleaseName.episodeTitle("E1 - Wake Up"))
        assertEquals("Mr. Robot's Day", ReleaseName.episodeTitle("S02E01  -  Mr. Robot's Day"))
    }

    @Test
    fun everythingElseHasNoTitle() {
        // Rename the file or add an NFO to give these a title.
        assertNull(ReleaseName.episodeTitle("Dark.S01E01.Secrets.German.DL.1080p.WEB.x264-GRP"))
        assertNull(ReleaseName.episodeTitle("Firefly.S01E02.The.Train.Job.720p.BluRay.x264-DEMAND"))
        assertNull(ReleaseName.episodeTitle("Show - S01E03 - Pilot"))
        assertNull(ReleaseName.episodeTitle("S01E03.Pilot"))
        assertNull(ReleaseName.episodeTitle("S01E03 Pilot"))
        assertNull(ReleaseName.episodeTitle("S01E03 - "))
        assertNull(ReleaseName.episodeTitle("s04e09"))
    }
}
