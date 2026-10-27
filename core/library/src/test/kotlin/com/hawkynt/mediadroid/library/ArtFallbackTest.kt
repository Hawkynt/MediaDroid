package com.hawkynt.mediadroid.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtFallbackTest {

    private fun episode(n: Int, path: String, thumb: String? = null) = Episode(number = n, videoPath = path, thumbPath = thumb)

    private val show = Show(
        title = "Dark",
        path = "/tv/Dark",
        seasons = listOf(
            Season(0, listOf(episode(1, "/tv/Dark/Specials/s00e01.mkv"))),
            Season(1, listOf(episode(2, "/tv/Dark/S1/s01e02.mkv"), episode(1, "/tv/Dark/S1/s01e01.mkv"))),
            Season(2, listOf(episode(1, "/tv/Dark/S2/s02e01.mkv", thumb = "/tv/Dark/S2/s02e01-thumb.jpg"))),
        ),
    )

    @Test
    fun aShowIsPicturedByItsFirstRegularEpisodeNotTheSpecials() {
        assertEquals("/tv/Dark/S1/s01e01.mkv", show.representativeVideo)
    }

    @Test
    fun aShowOfOnlySpecialsStillHasAPicture() {
        val specials = show.copy(seasons = show.seasons.take(1))
        assertEquals("/tv/Dark/Specials/s00e01.mkv", specials.representativeVideo)
    }

    @Test
    fun aSeasonIsPicturedByItsFirstEpisode() {
        assertEquals("/tv/Dark/S1/s01e01.mkv", show.seasons[1].representativeVideo)
        assertEquals("/tv/Dark/S2/s02e01-thumb.jpg", show.seasons[2].representativeThumb)
        assertNull(show.seasons[1].representativeThumb)
    }

    @Test
    fun emptyShowsAndSeasonsHaveNothing() {
        assertNull(Show(title = "Empty", path = "/tv/Empty").representativeVideo)
        assertNull(Season(1).representativeVideo)
    }
}
