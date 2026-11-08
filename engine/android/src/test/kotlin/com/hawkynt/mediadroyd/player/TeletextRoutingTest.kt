package com.hawkynt.mediadroyd.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Teletext subtitles on Android: Media3's transport stream extractor, given our teletext reader and parser
 * (TeletextSubtitles), turns a DVB recording's teletext stream (resources/teletext/subtitles.ts, made by
 * engine/desktop's make.py) into a subtitle track for each subtitle page its descriptor lists — language, label and
 * the hard of hearing marked — whose samples are the page's cues as it shows them, each replacing the last.
 * Under Robolectric, so it runs with the screenshots' tier (-Pscreenshots).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@androidx.annotation.OptIn(UnstableApi::class)
class TeletextRoutingTest {
    private class Sample(val timeUs: Long, val data: ByteArray)

    private class Track : TrackOutput {
        var format: Format? = null
        val samples = mutableListOf<Sample>()
        private var pending = ByteArray(0)

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val bytes = ByteArray(length)
            val read = input.read(bytes, 0, length)
            if (read == C.RESULT_END_OF_INPUT) return read
            pending += bytes.copyOf(read)
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            pending += bytes
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            val end = pending.size - offset
            samples += Sample(timeUs, pending.copyOfRange(end - size, end))
            pending = pending.copyOfRange(end, pending.size)
        }
    }

    private fun extract(): List<Track> {
        val file = checkNotNull(javaClass.getResourceAsStream("/teletext/subtitles.ts")).readBytes()
        val tracks = linkedMapOf<Int, Track>()
        val parsers = TeletextSubtitles.parsers(DefaultSubtitleParserFactory())
        val extractor: Extractor = TeletextSubtitles.tsExtractor(parsers)
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { Track() }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        var position = 0
        val reader = DataReader { buffer, offset, length ->
            if (position >= file.size) return@DataReader C.RESULT_END_OF_INPUT
            val n = minOf(length, file.size - position)
            System.arraycopy(file, position, buffer, offset, n)
            position += n
            n
        }
        // Read to the end, going where the extractor asks (it looks at the end for the duration first).
        val seek = PositionHolder()
        var input = DefaultExtractorInput(reader, 0, file.size.toLong())
        while (true) {
            when (extractor.read(input, seek)) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> {
                    position = seek.position.toInt()
                    input = DefaultExtractorInput(reader, seek.position, file.size.toLong())
                }
                else -> break
            }
        }
        return tracks.values.filter { it.format?.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES }
    }

    /** A sample's cues as their text, lines joined; "" for a page cleared. */
    private fun text(sample: Sample): String =
        CueDecoder().decode(sample.timeUs, sample.data, 0, sample.data.size).cues.joinToString("\n") { it.text.toString() }

    @Test
    fun `each subtitle page is a track of its own with the page's language and label`() {
        val tracks = extract()
        assertEquals(listOf("Teletext 888", "Teletext 777"), tracks.map { it.format?.label })
        assertEquals(listOf("de", "en"), tracks.map { it.format?.language })
        assertTrue(tracks.all { it.format?.codecs == Teletext.MIME_TYPE })
        assertEquals(C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND, tracks[1].format!!.roleFlags)
    }

    @Test
    fun `a page's samples are what it shows, from the moment it shows it`() {
        val (german, hardOfHearing) = extract()
        assertEquals(
            listOf("Hallo!\nGrüße aus Hamburg", "", "Bis bald."),
            german.samples.map(::text),
        )
        assertEquals(listOf("(Musik)", "(Applaus)", ""), hardOfHearing.samples.map(::text))
        // Two seconds apart, as the stream's time stamps have them.
        val times = german.samples.map { it.timeUs }
        assertEquals(listOf(2_000_000L, 4_000_000L), times.map { it - times[0] }.drop(1))
        // The yellow line keeps its colour; white is left to the household's look.
        val cue = CueDecoder().decode(0, german.samples[0].data, 0, german.samples[0].data.size).cues.single()
        val spans = (cue.text as android.text.Spanned)
            .getSpans(0, cue.text!!.length, android.text.style.ForegroundColorSpan::class.java)
        assertEquals(listOf(0xFFFFFF00.toInt()), spans.map { it.foregroundColor })
    }
}
