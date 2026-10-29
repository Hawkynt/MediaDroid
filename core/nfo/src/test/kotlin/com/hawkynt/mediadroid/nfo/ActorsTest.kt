package com.hawkynt.mediadroid.nfo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Kodi writes every `<actor>` straight under the NFO's root (kodi.wiki/view/NFO_files/Movies, …/TV_shows,
 * …/Episodes), as tinyMediaManager and Kodi's own export do; a `<cast>` or `<gueststars>` wrapper some
 * tools write is read too.
 */
class ActorsTest {

    private val actors = """
        <actor><name>Anthony Gonzalez</name><role>Miguel</role><order>0</order></actor>
        <actor><name>Gael García Bernal</name><role>Héctor</role><order>1</order></actor>
    """

    @Test
    fun `a movie's actors stand straight under its root`() {
        val movie = parseMovieNfo("<movie><title>Coco</title>$actors</movie>")
        assertEquals(
            listOf(CastMember("Anthony Gonzalez", "Miguel"), CastMember("Gael García Bernal", "Héctor")),
            movie.cast,
        )
    }

    @Test
    fun `a cast wrapper still counts`() {
        val movie = parseMovieNfo("<movie><title>Coco</title><cast>$actors</cast></movie>")
        assertEquals(2, movie.cast.size)
    }

    @Test
    fun `a show's and an episode's actors are read the same way`() {
        assertEquals(2, parseShowNfo("<tvshow><title>Knuckles</title>$actors</tvshow>").cast.size)
        val episode = parseEpisodeNfo("<episodedetails><title>Pilot</title>$actors</episodedetails>")
        assertEquals("Anthony Gonzalez", episode.guests.first().name)
        assertEquals(
            1,
            parseEpisodeNfo(
                "<episodedetails><title>Pilot</title><gueststars><actor><name>A</name></actor></gueststars>" +
                    "</episodedetails>",
            ).guests.size,
        )
    }
}
