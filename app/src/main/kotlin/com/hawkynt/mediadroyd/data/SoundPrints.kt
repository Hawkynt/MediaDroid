package com.hawkynt.mediadroyd.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.hawkynt.mediadroyd.player.AndroidNeural
import com.hawkynt.mediadroyd.player.IntroCredits
import com.hawkynt.mediadroyd.player.NativeFingerprint
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/**
 * A video's sound fingerprinted on Android for finding its intro and credits (PlayerPlatform.soundPrint): its first
 * audio track decoded by the device's own decoder (MediaExtractor and MediaCodec; a file on a network source read
 * through FsMediaDataSource) to 16-bit PCM, and fingerprinted by Chromaprint inside the app's native library
 * (NativeFingerprint). A track the device cannot decode (DTS on many phones) gives no print, and the episode is left to
 * its chapters and cut files.
 */
object SoundPrints {
    private const val TIMEOUT_US = 10_000L

    /**
     * The video at [location]: its first IntroCredits.OPENING_MS, or where [closing] its last IntroCredits.CLOSING_MS;
     * null where its sound cannot be read or decoded here.
     */
    fun of(context: Context, location: String, closing: Boolean): IntroCredits.SoundPrint? {
        if (!AndroidNeural.loaded) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            val remote = if (location.startsWith("/")) null else SourceAccess.resolve(context, location)
            if (remote == null) {
                extractor.setDataSource(location)
            } else {
                extractor.setDataSource(FsMediaDataSource(remote))
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1000
            } else {
                0L
            }
            val startMs = if (closing) (durationMs - IntroCredits.CLOSING_MS).coerceAtLeast(0) else 0L
            val endMs = startMs + if (closing) IntroCredits.CLOSING_MS else IntroCredits.OPENING_MS
            if (startMs > 0) extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()
            decode(extractor, decoder, endMs)?.let { (pcm, firstMs, rate, channels) ->
                NativeFingerprint.fingerprint(pcm, rate, channels)?.let { IntroCredits.SoundPrint(it, firstMs) }
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private data class Decoded(val pcm: ShortArray, val firstMs: Long, val rate: Int, val channels: Int)

    // The decoder's output up to [endMs]: its samples, where they begin, and their rate and channels.
    private fun decode(extractor: MediaExtractor, decoder: MediaCodec, endMs: Long): Decoded? {
        val pcm = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var firstMs = -1L
        var inputDone = false
        var rate = 0
        var channels = 0
        while (true) {
            if (!inputDone) {
                val slot = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (slot >= 0) {
                    val size = extractor.readSampleData(decoder.getInputBuffer(slot)!!, 0)
                    if (size < 0 || extractor.sampleTime / 1000 >= endMs) {
                        decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(slot, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val out = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                out >= 0 -> {
                    if (rate == 0) {
                        rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (info.size > 0) {
                        if (firstMs < 0) firstMs = info.presentationTimeUs / 1000
                        val bytes = ByteArray(info.size)
                        decoder.getOutputBuffer(out)!!.apply { position(info.offset) }.get(bytes)
                        pcm.write(bytes)
                    }
                    decoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        val bytes = pcm.toByteArray()
        if (bytes.isEmpty() || rate <= 0 || channels <= 0) return null
        val samples = ShortArray(bytes.size / 2)
        java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer().get(samples)
        return Decoded(samples, firstMs, rate, channels)
    }
}
