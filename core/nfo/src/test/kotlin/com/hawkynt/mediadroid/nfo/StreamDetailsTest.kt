package com.hawkynt.mediadroid.nfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `<fileinfo><streamdetails>` as Kodi writes it (field names from the Kodi wiki's NFO pages). */
class StreamDetailsTest {

    private val nfo = """
        <episodedetails>
          <title>Ozymandias</title>
          <fileinfo>
            <streamdetails>
              <video>
                <codec>hevc</codec><aspect>2.39</aspect><width>3840</width><height>1606</height>
                <durationinseconds>2880</durationinseconds><hdrtype>dolbyvision</hdrtype>
              </video>
              <audio><codec>truehd_atmos</codec><language>eng</language><channels>8</channels></audio>
              <audio><codec>ac3</codec><language>ger</language><channels>6</channels></audio>
              <subtitle><language>eng</language></subtitle>
            </streamdetails>
          </fileinfo>
        </episodedetails>
    """.trimIndent()

    @Test
    fun videoAndEveryAudioStreamAreRead() {
        val details = parseEpisodeNfo(nfo).streamDetails!!
        assertEquals(3840, details.videoWidth)
        assertEquals(1606, details.videoHeight)
        assertEquals("hevc", details.videoCodec)
        assertEquals("dolbyvision", details.hdrType)
        assertEquals(
            listOf(AudioStream("truehd_atmos", 8, "eng"), AudioStream("ac3", 6, "ger")),
            details.audio,
        )
    }

    @Test
    fun moviesCarryThemToo() {
        val movie = parseMovieNfo(nfo.replace("episodedetails", "movie"))
        assertEquals(3840, movie.streamDetails!!.videoWidth)
    }

    @Test
    fun noStreamDetailsIsNothing() {
        assertNull(parseEpisodeNfo("<episodedetails><title>X</title></episodedetails>").streamDetails)
    }
}
