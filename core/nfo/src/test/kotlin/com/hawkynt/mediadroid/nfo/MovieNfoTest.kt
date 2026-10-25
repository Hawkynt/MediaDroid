package com.hawkynt.mediadroid.nfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovieNfoTest {

    private val bigNfo = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <movie>
          <title>Blade Runner 2049</title>
          <originaltitle>Blade Runner 2049</originaltitle>
          <sorttitle>Blade Runner 2049</sorttitle>
          <plot>A young blade runner discovers a long-buried secret.</plot>
          <outline>A young blade runner's discovery of a long-buried secret.</outline>
          <tagline>The key to the future is finally unearthed.</tagline>
          <runtime>164</runtime>
          <premiered>2017-10-06</premiered>
          <mpaa>R (for some violent content)</mpaa>
          <studio>Alcon Entertainment</studio>
          <genre>Action</genre>
          <genre>Science Fiction</genre>
          <tag>cyberpunk</tag>
          <set><name>Blade Runner</name></set>
          <director>Denis Villeneuve</director>
          <writer>Hampton Fancher</writer>
          <cast>
            <actor><name>Ryan Gosling</name><role>Officer K</role></actor>
            <actor><name>Harrison Ford</name><role>Rick Deckard</role></actor>
          </cast>
          <imdbid>tt1856101</imdbid>
          <tmdbid>375262</tmdbid>
          <trailer>plugin://plugin.video.youtube/play/?video_id=CbaMia4M3pg</trailer>
        </movie>
    """.trimIndent()

    @Test
    fun readsEveryFieldAScraperWrites() {
        val movie = parseMovieNfo(bigNfo)
        assertEquals("Blade Runner 2049", movie.title)
        assertEquals("A young blade runner discovers a long-buried secret.", movie.plot)
        assertEquals("The key to the future is finally unearthed.", movie.tagline)
        assertEquals(164, movie.runtimeMinutes)
        assertEquals(2017, movie.year)
        assertEquals(listOf("Action", "Science Fiction"), movie.genres)
        assertEquals(listOf("cyberpunk"), movie.tags)
        assertEquals(listOf("Alcon Entertainment"), movie.studios)
        assertEquals(listOf("Denis Villeneuve"), movie.directors)
        assertEquals("Blade Runner", movie.set?.name)
        assertEquals(
            listOf(CastMember("Ryan Gosling", "Officer K"), CastMember("Harrison Ford", "Rick Deckard")),
            movie.cast,
        )
        assertEquals("tt1856101", movie.imdbId)
        assertEquals("375262", movie.tmdbId)
    }

    @Test
    fun bareTitleIsEnough() {
        val movie = parseMovieNfo("<movie><title>Some Film</title></movie>")
        assertEquals("Some Film", movie.title)
        assertNull(movie.plot)
        assertNull(movie.year)
        assertEquals(emptyList<String>(), movie.genres)
    }

    @Test
    fun yearFallsBackToYearElement() {
        val movie = parseMovieNfo("<movie><title>X</title><year>1982</year></movie>")
        assertEquals(1982, movie.year)
    }

    @Test(expected = NfoFormatException::class)
    fun titlelessNfoIsRejected() {
        parseMovieNfo("<movie><plot>no title here</plot></movie>")
    }
}
