package com.hawkynt.mediadroid.nfo

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeNfoTest {

    private val ozymandias = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <episodedetails>
          <showtitle>Breaking Bad</showtitle>
          <title>Ozymandias</title>
          <season>5</season>
          <episode>1</episode>
          <plot>Jesse wakes up in a hospital.</plot>
          <aired>2012-07-15</aired>
          <director>Vince Gilligan</director>
          <imdbid>tt1797054</imdbid>
          <showid>tt0903747</showid>
        </episodedetails>
    """.trimIndent()

    @Test
    fun readsEpisodeFields() {
        val episode = parseEpisodeNfo(ozymandias)
        assertEquals("Breaking Bad", episode.showTitle)
        assertEquals("Ozymandias", episode.title)
        assertEquals(5, episode.season)
        assertEquals(1, episode.episode)
        assertEquals("2012-07-15", episode.firstAired)
        assertEquals(listOf("Vince Gilligan"), episode.directors)
        assertEquals("tt1797054", episode.imdbId)
        assertEquals("tt0903747", episode.showImdbId)
    }

    @Test
    fun introAndCreditsSpansAreRead() {
        val episode = parseEpisodeNfo(
            "<episodedetails><title>X</title><intro>20,90</intro><credits>1180,1260</credits></episodedetails>",
        )
        assertEquals(TimeRange(20, 90), episode.intro)
        assertEquals(TimeRange(1180, 1260), episode.credits)
    }

    @Test
    fun episodeStreamLanguagesComeFromStreamdetails() {
        val info = parseEpisodeNfo(
            """
            <episodedetails><title>Pilot</title>
              <fileinfo><streamdetails>
                <audio><language>jpn</language></audio>
                <subtitle><language>eng</language></subtitle>
              </streamdetails></fileinfo>
            </episodedetails>
            """.trimIndent(),
        )
        assertEquals(listOf("jpn"), info.audioLanguages)
        assertEquals(listOf("eng"), info.subtitleLanguages)
    }
}
