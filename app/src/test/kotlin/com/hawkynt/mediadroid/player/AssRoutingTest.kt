package com.hawkynt.mediadroid.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What reaches libass on Android: Media3's own Matroska extractor, told by AssSubtitles' parsers to leave SSA alone,
 * hands the stream over raw — its header in the format, each event as a sample AssEvents reads. With Media3's own
 * parsers (no libass) the same stream comes out parsed into cues instead. Under Robolectric (Media3's byte reader
 * asks android.os.Build whether it runs in a test), so it runs with the screenshots' tier (-Pscreenshots).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@androidx.annotation.OptIn(UnstableApi::class)
class AssRoutingTest {
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

    private fun extract(libass: Boolean): Map<Int, Track> {
        val file = checkNotNull(javaClass.getResourceAsStream("/ass/styled.mkv")).readBytes()
        val tracks = mutableMapOf<Int, Track>()
        val extractor: Extractor = MatroskaExtractor(AssSubtitles.parsers(libass), 0)
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { Track() }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        var position = 0L
        val reader = DataReader { buffer, offset, length ->
            if (position >= file.size) return@DataReader C.RESULT_END_OF_INPUT
            val n = minOf(length.toLong(), file.size - position).toInt()
            System.arraycopy(file, position.toInt(), buffer, offset, n)
            position += n
            n
        }
        // Read to the end, going where the extractor asks (Matroska looks up its cues and comes back).
        val seek = PositionHolder()
        var input = DefaultExtractorInput(reader, 0, file.size.toLong())
        while (true) {
            when (extractor.read(input, seek)) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> {
                    position = seek.position
                    input = DefaultExtractorInput(reader, position, file.size.toLong())
                }
                else -> break
            }
        }
        return tracks
    }

    @Test
    fun `with libass the Matroska stream reaches the renderer raw, header and events as libass takes them`() {
        val ssa = extract(libass = true).values.single { AssEvents.isAss(it.format?.sampleMimeType) }
        val header = String(AssEvents.header(ssa.format!!.initializationData)!!)
        assertTrue(header.contains("Style: Karaoke,Noto Sans,30"))
        val events = ssa.samples.map { checkNotNull(AssEvents.fromMatroskaSample(it.data)) { String(it.data) } }
        assertEquals(5, events.size)
        assertTrue(events.all { it.durationMs == 3000L })
        assertEquals("1,1,Sign,,0,0,0,,{\\pos(40,60)\\frz-8}EXIT", String(events[1].data))
    }

    @Test
    fun `without libass Media3 parses the stream as before`() {
        val tracks = extract(libass = false).values
        assertTrue(tracks.none { AssEvents.isAss(it.format?.sampleMimeType) })
        assertTrue(tracks.any { it.format?.codecs == AssEvents.MIME_TYPE })
    }
}
