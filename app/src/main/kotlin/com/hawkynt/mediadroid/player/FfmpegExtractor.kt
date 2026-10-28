package com.hawkynt.mediadroid.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import com.hawkynt.mediadroid.ffmpeg.FfmpegDemuxer
import com.hawkynt.mediadroid.library.FsFile
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * FFmpeg's demuxers as a Media3 [Extractor], last in line: it gets a file only when none of Media3's
 * own extractors recognised it — ASF/WMV, RealMedia, DV, NUT, MXF and the like. FFmpeg reads the file
 * itself, with the random access its demuxers need; Media3's [ExtractorInput] is only advanced along
 * with it, so Media3 still sees how far loading got and stops buffering in time.
 */
@OptIn(UnstableApi::class)
class FfmpegExtractor(
    private val uri: Uri?,
    /** The file behind a network location; local files are read directly. */
    private val remote: (String) -> FsFile? = { null },
) : Extractor {

    private lateinit var output: ExtractorOutput
    private var file: RandomAccessFile? = null
    private var demuxer: FfmpegDemuxer? = null
    private val tracks = HashMap<Int, TrackOutput>()
    private val lastTimeUs = HashMap<Int, Long>()
    private var declared = false
    private var pendingSeekUs: Long? = null
    private var buffer: ByteBuffer = ByteBuffer.allocateDirect(INITIAL_BUFFER)
    private val packet = FfmpegDemuxer.Packet()
    private val sample = ParsableByteArray()

    override fun sniff(input: ExtractorInput): Boolean = demuxer != null || open()

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val demuxer = demuxer ?: if (open()) demuxer!! else throw ParserException.createForMalformedContainer(
            "FFmpeg cannot read this file.",
            null,
        )
        if (!declared) declare(demuxer, input.length)
        pendingSeekUs?.let {
            demuxer.seekTo(it)
            lastTimeUs.clear()
            pendingSeekUs = null
        }
        val data = demuxer.readPacket(buffer, packet) ?: return Extractor.RESULT_END_OF_INPUT
        buffer = data
        follow(input, packet.position)
        val track = tracks[packet.streamIndex] ?: return Extractor.RESULT_CONTINUE
        // A packet without a time of its own continues where the last one of its stream left off.
        val timeUs = packet.timeUs.takeIf { it != FfmpegDemuxer.TIME_UNSET }
            ?: lastTimeUs[packet.streamIndex]
            ?: 0L
        lastTimeUs[packet.streamIndex] = timeUs
        if (sample.capacity() < packet.size) sample.reset(ByteArray(packet.size * 3 / 2))
        data.get(sample.data, 0, packet.size)
        sample.setPosition(0)
        sample.setLimit(packet.size)
        track.sampleData(sample, packet.size)
        track.sampleMetadata(timeUs, if (packet.keyframe) C.BUFFER_FLAG_KEY_FRAME else 0, packet.size, 0, null)
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        pendingSeekUs = timeUs
    }

    override fun release() {
        demuxer?.close()
        demuxer = null
        file?.close()
        file = null
    }

    private fun open(): Boolean {
        if (!FfmpegDemuxer.isAvailable()) return false
        val uri = uri ?: return false
        // Anything but a plain file — a share, a WebDAV folder, a cloud document — is read through its source.
        if (uri.scheme != null && uri.scheme != "file") return openRemote(uri)
        val path = uri.takeIf { it.scheme == null || it.scheme == "file" }?.path ?: return false
        val raf = runCatching { RandomAccessFile(File(path), "r") }.getOrNull() ?: return false
        val opened = FfmpegDemuxer.open(object : FfmpegDemuxer.Input {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = raf.read(buffer, offset, length)

            override fun seek(position: Long): Long {
                raf.seek(position)
                return position
            }

            override fun length(): Long = raf.length()
        })
        if (opened == null) {
            raf.close()
            return false
        }
        file = raf
        demuxer = opened
        return true
    }

    /** A file on a network source: read as a stream, opened again further in when FFmpeg seeks. */
    private fun openRemote(uri: Uri): Boolean {
        val file = remote(uri.toString()) ?: return false
        val input = object : FfmpegDemuxer.Input {
            private var stream: java.io.InputStream? = null
            private var position = 0L

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val current = stream ?: java.io.BufferedInputStream(file.open(position), REMOTE_BUFFER)
                    .also { stream = it }
                val read = current.read(buffer, offset, length)
                if (read > 0) position += read
                return read
            }

            override fun seek(position: Long): Long {
                if (position != this.position) {
                    stream?.close()
                    stream = null
                    this.position = position
                }
                return position
            }

            override fun length(): Long = file.length().takeIf { it > 0 } ?: -1
        }
        demuxer = runCatching { FfmpegDemuxer.open(input) }.getOrNull() ?: return false
        return true
    }

    /** Declares the streams Media3 can be given and how seeking works. */
    private fun declare(demuxer: FfmpegDemuxer, inputLength: Long) {
        for (stream in demuxer.streams()) {
            val mapping = DemuxedTrack.map(stream.kind, stream.codecName, stream.parameters, stream.decoderName)
                ?: continue
            val type = if (stream.kind == DemuxedTrack.VIDEO) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO
            val format = Format.Builder()
                .setId(stream.index)
                .setSampleMimeType(mapping.mimeType)
                .setCodecs(stream.codecName)
                .setInitializationData(mapping.initializationData)
                .setLanguage(stream.language)
                .setSelectionFlags(if (stream.isDefault) C.SELECTION_FLAG_DEFAULT else 0)
                .setAverageBitrate(stream.bitRate.toIntOrUnset())
                .apply {
                    if (type == C.TRACK_TYPE_VIDEO) {
                        setWidth(stream.width.orUnset())
                        setHeight(stream.height.orUnset())
                        setRotationDegrees(stream.rotationDegrees)
                        if (stream.frameRate > 0f) setFrameRate(stream.frameRate)
                    } else {
                        setSampleRate(stream.sampleRate.orUnset())
                        setChannelCount(stream.channels.orUnset())
                    }
                }
                .build()
            tracks[stream.index] = output.track(stream.index, type).also { it.format(format) }
        }
        output.endTracks()
        output.seekMap(seekMap(demuxer.durationUs(), inputLength))
        declared = true
    }

    /**
     * Seeking goes by time — FFmpeg finds the keyframe — so the byte position handed to Media3 is only
     * an estimate by share of the duration, which keeps its reloading near where FFmpeg will read.
     */
    private fun seekMap(durationUs: Long, length: Long): SeekMap {
        if (durationUs == FfmpegDemuxer.TIME_UNSET || durationUs <= 0) return SeekMap.Unseekable(C.TIME_UNSET)
        return object : SeekMap {
            override fun isSeekable() = true

            override fun getDurationUs() = durationUs

            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val share = timeUs.coerceIn(0, durationUs).toDouble() / durationUs
                val position = if (length > 0) (length * share).toLong() else 0L
                return SeekMap.SeekPoints(SeekPoint(timeUs, position))
            }
        }
    }

    /** Advances Media3's input to where FFmpeg has read to, at most a chunk at a time. */
    private fun follow(input: ExtractorInput, position: Long) {
        val ahead = position - input.position
        if (ahead > 0) input.skip(minOf(ahead, FOLLOW_CHUNK.toLong()).toInt())
    }

    private fun Int.orUnset(): Int = if (this > 0) this else Format.NO_VALUE

    private fun Long.toIntOrUnset(): Int = if (this in 1..Int.MAX_VALUE) toInt() else Format.NO_VALUE

    private companion object {
        const val INITIAL_BUFFER = 1 shl 20
        const val FOLLOW_CHUNK = 1 shl 20
        const val REMOTE_BUFFER = 256 * 1024
    }
}
