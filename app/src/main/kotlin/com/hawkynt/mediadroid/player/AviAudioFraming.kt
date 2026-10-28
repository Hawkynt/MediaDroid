package com.hawkynt.mediadroid.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.avi.AviExtractor
import java.io.EOFException

/**
 * Media3's extractors, with AVI's AC3 / E-AC3 audio handed on in whole frames. AVI keeps that audio as
 * a byte stream cut into chunks that ignore frame boundaries; Media3's AVI reader passes the chunks on
 * as they are, and the FFmpeg decoder — which gets no parser in front of it — rejects every frame
 * that was cut. See [FrameSplitter].
 */
@OptIn(UnstableApi::class)
object AviAudioFraming {

    /**
     * The extractors to play local files with; FFmpeg's demuxers come last, for the containers none of
     * Media3's own recognises (see [FfmpegExtractor]).
     */
    val extractors: ExtractorsFactory = object : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> = wrap(DefaultExtractorsFactory().createExtractors())

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            wrap(DefaultExtractorsFactory().createExtractors(uri, responseHeaders)) + FfmpegExtractor(uri)
    }

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        extractors.map { if (it is AviExtractor) FramingExtractor(it) else it }.toTypedArray()

    private class FramingExtractor(private val inner: Extractor) : Extractor {
        private val tracks = ArrayList<FramingTrackOutput>()

        override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

        override fun init(output: ExtractorOutput) {
            inner.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    val track = output.track(id, type)
                    return if (type == C.TRACK_TYPE_AUDIO) FramingTrackOutput(track).also { tracks += it } else track
                }

                override fun endTracks() = output.endTracks()

                override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
            })
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)

        override fun seek(position: Long, timeUs: Long) {
            tracks.forEach { it.reset() }
            inner.seek(position, timeUs)
        }

        override fun release() = inner.release()

        override fun getUnderlyingImplementation(): Extractor = inner
    }

    /** Passes everything through until the format says AC3 or E-AC3; from then on re-cuts it into frames. */
    private class FramingTrackOutput(private val out: TrackOutput) : TrackOutput {
        private var splitter: FrameSplitter? = null
        private val scratch = ByteArray(16 * 1024)

        fun reset() {
            splitter?.reset()
        }

        override fun format(format: Format) {
            if (format.sampleMimeType == MimeTypes.AUDIO_AC3 || format.sampleMimeType == MimeTypes.AUDIO_E_AC3) {
                splitter = FrameSplitter(::probeAc3)
            }
            out.format(format)
        }

        override fun durationUs(durationUs: Long) = out.durationUs(durationUs)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val frames = splitter ?: return out.sampleData(input, length, allowEndOfInput, sampleDataPart)
            val read = input.read(scratch, 0, minOf(length, scratch.size))
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            frames.append(scratch, 0, read)
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val frames = splitter ?: return out.sampleData(data, length, sampleDataPart)
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            frames.append(bytes, 0, length)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val frames = splitter ?: return out.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            frames.chunkEnded(timeUs, size) { bytes, at, frameSize, frameTimeUs ->
                out.sampleData(ParsableByteArray(bytes.copyOfRange(at, at + frameSize)), frameSize)
                out.sampleMetadata(frameTimeUs, C.BUFFER_FLAG_KEY_FRAME, frameSize, 0, null)
            }
        }

        /** An AC3 / E-AC3 syncframe (syncword 0x0B77) and its size and duration, as Media3's [Ac3Util] reads them. */
        private fun probeAc3(bytes: ByteArray, at: Int, available: Int): FrameSplitter.Probe {
            if (bytes[at] != 0x0B.toByte()) return FrameSplitter.Probe.NotAFrame
            if (available < 2) return FrameSplitter.Probe.NeedMore
            if (bytes[at + 1] != 0x77.toByte()) return FrameSplitter.Probe.NotAFrame
            if (available < AC3_HEADER_BYTES) return FrameSplitter.Probe.NeedMore
            val info = runCatching {
                Ac3Util.parseAc3SyncframeInfo(ParsableBitArray(bytes.copyOfRange(at, at + AC3_HEADER_BYTES)))
            }.getOrNull() ?: return FrameSplitter.Probe.NotAFrame
            if (info.frameSize <= 0 || info.sampleRate <= 0) return FrameSplitter.Probe.NotAFrame
            return FrameSplitter.Probe.Frame(info.frameSize, info.sampleCount * 1_000_000L / info.sampleRate)
        }
    }

    /** Enough of a syncframe for Ac3Util to read an E-AC3 header too. */
    private const val AC3_HEADER_BYTES = 64
}
