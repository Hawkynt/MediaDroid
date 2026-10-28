package com.hawkynt.mediadroid.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieVersionsTest {

    private fun heat(file: String, resolution: String?, size: Long, hdr: String? = null, plot: String? = null) = Movie(
        title = "Heat",
        year = 1995,
        path = "/m/$file",
        videoPath = "/m/$file.mkv",
        quality = Quality(resolution = resolution, hdr = hdr),
        sizeBytes = size,
        plot = plot,
    )

    @Test
    fun theSameTitleAndYearIsOneCardWithTheBestPictureFirst() {
        val hd = heat("hd", "1080p", 8, plot = "LA crime saga.")
        val uhd = heat("uhd", "4K", 40, hdr = "HDR10")
        val sd = heat("sd", "480p", 2)
        val remake = heat("remake", "1080p", 9).copy(year = 2030)

        val merged = MovieVersions.merge(listOf(hd, sd, uhd, remake))

        assertEquals(2, merged.size)
        val heat = merged.first { it.year == 1995 }
        assertEquals("/m/uhd.mkv", heat.videoPath)
        assertEquals(listOf("/m/uhd.mkv", "/m/hd.mkv", "/m/sd.mkv"), heat.versions.map { it.videoPath })
        assertEquals("LA crime saga.", heat.plot)
        assertEquals(emptyList<Movie>(), merged.first { it.year == 2030 }.versions)
    }

    @Test
    fun withoutQualityTheBiggerFileCountsAsTheBetterPicture() {
        val merged = MovieVersions.merge(listOf(heat("small", null, 2), heat("big", null, 9))).single()
        assertEquals("/m/big.mkv", merged.videoPath)
    }

    @Test
    fun playTakesTheVersionWithAResumePointElseTheBest() {
        val merged = MovieVersions.merge(listOf(heat("hd", "1080p", 8), heat("uhd", "4K", 40))).single()
        assertEquals("/m/uhd.mkv", MovieVersions.toPlay(merged) { false }.videoPath)
        assertEquals("/m/hd.mkv", MovieVersions.toPlay(merged) { it == "/m/hd.mkv" }.videoPath)
        val single = heat("only", "720p", 1)
        assertEquals(single, MovieVersions.toPlay(single) { false })
    }

    @Test
    fun aFilmCountsAsWatchedWhenAnyOfItsVersionsIs() {
        val merged = MovieVersions.merge(listOf(heat("hd", "1080p", 8), heat("uhd", "4K", 40))).single()
        val marks = WatchMarks(mutableListOf(), mutableListOf())
        assertFalse(marks.isWatched(merged))
        marks.set(listOf("/m/hd.mkv"), watched = true)
        assertTrue(marks.isWatched(merged))
        assertEquals(listOf("/m/uhd.mkv", "/m/hd.mkv"), merged.allParts)
    }
}
