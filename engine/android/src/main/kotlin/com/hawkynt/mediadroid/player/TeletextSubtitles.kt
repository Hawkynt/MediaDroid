package com.hawkynt.mediadroid.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import com.google.common.collect.ImmutableList

/**
 * Teletext subtitles of DVB recordings on Android: Media3 reads no teletext, so its transport stream extractor is
 * given a reader of ours for the streams a teletext descriptor marks ([payloadReaders]) — each subtitle page it lists
 * a subtitle track of its own, the stream's PES packets its samples — and a parser of ours ([parsers]) that decodes a
 * page ([Teletext]) into Media3's cues as it is read, each page shown replacing the one before, as DVB's picture
 * subtitles are.
 */
@OptIn(UnstableApi::class)
object TeletextSubtitles {
    init {
        // Media3 takes a track for subtitles by its MIME type; ours is told to be text.
        MimeTypes.registerCustomMimeType(Teletext.MIME_TYPE, "tx", C.TRACK_TYPE_TEXT)
    }

    /** Media3's own transport stream extractor with teletext read ([payloadReaders]) and parsed by [parsers]. */
    fun tsExtractor(parsers: SubtitleParser.Factory): Extractor = TsExtractor(
        TsExtractor.MODE_SINGLE_PMT,
        0,
        parsers,
        TimestampAdjuster(0),
        payloadReaders(DefaultTsPayloadReaderFactory(0, ImmutableList.of())),
        TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
    )

    /** [media3]'s payload readers, and ours for a private stream with a teletext descriptor listing subtitle pages. */
    fun payloadReaders(media3: TsPayloadReader.Factory): TsPayloadReader.Factory = object : TsPayloadReader.Factory {
        override fun createInitialPayloadReaders() = media3.createInitialPayloadReaders()

        override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? {
            media3.createPayloadReader(streamType, esInfo)?.let { return it }
            val pages = Teletext.pagesInDescriptors(esInfo.descriptorBytes).filter { it.isSubtitles }.distinct()
            return if (pages.isEmpty()) null else PesReader(TeletextReader(pages))
        }
    }

    /** [media3]'s subtitle parsers, and ours for teletext pages. */
    fun parsers(media3: SubtitleParser.Factory): SubtitleParser.Factory = object : SubtitleParser.Factory {
        override fun supportsFormat(format: Format) =
            format.sampleMimeType == Teletext.MIME_TYPE || media3.supportsFormat(format)

        override fun getCueReplacementBehavior(format: Format) = if (format.sampleMimeType == Teletext.MIME_TYPE) {
            Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
        } else {
            media3.getCueReplacementBehavior(format)
        }

        override fun create(format: Format): SubtitleParser = if (format.sampleMimeType == Teletext.MIME_TYPE) {
            TeletextParser(pageOf(format))
        } else {
            media3.create(format)
        }
    }

    /** The page a track's format is for (its initialization data: the page, magazine first, in two bytes). */
    private fun pageOf(format: Format): Int {
        val data = format.initializationData.firstOrNull() ?: return 0x888
        return (data[0].toInt() and 0xFF) shl 8 or (data[1].toInt() and 0xFF)
    }

    /** A teletext stream's PES packets, each a sample of every subtitle page's track. */
    private class TeletextReader(private val pages: List<Teletext.Page>) : ElementaryStreamReader {
        private val outputs = arrayOfNulls<TrackOutput>(pages.size)
        private var writing = false
        private var size = 0
        private var timeUs = C.TIME_UNSET
        private var lastTimeUs = C.TIME_UNSET

        override fun seek() {
            writing = false
            lastTimeUs = C.TIME_UNSET
        }

        override fun createTracks(extractorOutput: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
            pages.forEachIndexed { i, page ->
                idGenerator.generateNewId()
                val output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_TEXT)
                output.format(
                    Format.Builder()
                        .setId(idGenerator.formatId)
                        .setSampleMimeType(Teletext.MIME_TYPE)
                        .setLanguage(page.language.trim().takeIf { it.isNotEmpty() })
                        .setLabel("Teletext ${page.label}")
                        .setRoleFlags(
                            if (page.type == Teletext.TYPE_SUBTITLE_HARD_OF_HEARING) {
                                C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND
                            } else {
                                0
                            },
                        )
                        .setInitializationData(listOf(byteArrayOf((page.id shr 8).toByte(), page.id.toByte())))
                        .build(),
                )
                outputs[i] = output
            }
        }

        override fun packetStarted(pesTimeUs: Long, flags: Int) {
            // EN 300 472 annex A: a stream may carry no time stamps; the last one then stands in.
            timeUs = if (pesTimeUs != C.TIME_UNSET) pesTimeUs else lastTimeUs
            writing = timeUs != C.TIME_UNSET
            size = 0
        }

        override fun consume(data: ParsableByteArray) {
            if (!writing) return
            val at = data.position
            val count = data.bytesLeft()
            for (output in outputs) {
                data.position = at
                output?.sampleData(data, count)
            }
            size += count
        }

        override fun packetFinished() {
            if (!writing) return
            writing = false
            lastTimeUs = timeUs
            for (output in outputs) output?.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
        }
    }

    /** One page's samples as Media3's cues: what the page shows, from when it shows it until the next. */
    private class TeletextParser(page: Int) : SubtitleParser {
        private val decoder = Teletext.Decoder(page)

        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>,
        ) {
            val shown = decoder.feed(data, offset, length) ?: return
            output.accept(CuesWithTiming(cues(shown), C.TIME_UNSET, C.TIME_UNSET))
        }

        override fun reset() = decoder.reset()

        override fun getCueReplacementBehavior() = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
    }

    /** What a page shows as one cue: its lines in their colours (white left to the household's subtitle look). */
    fun cues(shown: Teletext.Shown): ImmutableList<Cue> {
        if (shown.lines.isEmpty()) return ImmutableList.of()
        val text = SpannableStringBuilder()
        shown.lines.forEachIndexed { i, line ->
            if (i > 0) text.append('\n')
            for (span in line.spans) {
                val start = text.length
                text.append(span.text)
                if (span.colour != WHITE) {
                    text.setSpan(
                        ForegroundColorSpan(0xFF000000.toInt() or span.colour),
                        start,
                        text.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
        return ImmutableList.of(Cue.Builder().setText(text).build())
    }

    private const val WHITE = 0xFFFFFF
}
