package com.hawkynt.mediadroid.player

import androidx.annotation.OptIn
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.extractor.ConstantBitrateSeekMap
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.Ac3Extractor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

/**
 * The audio files beside a video, as a [MediaItem]'s tag: the player merges each in as one more source,
 * so their tracks sit in the audio picker beside the file's own. Subtitle files go the way Media3 has
 * for them, as the item's subtitle configurations.
 */
data class SidecarAudio(val locations: List<String>)

/**
 * Builds an item's sources with [inner], then merges in the audio files its [SidecarAudio] tag names,
 * read through [dataSource] with the app's extractors. Media3 prefixes the ids of a merged source's
 * track groups with its index: the video (with its subtitles) is `0:`, the audio files `1:`, `2:` …
 */
@OptIn(UnstableApi::class)
class SidecarAudioSourceFactory(
    private val inner: MediaSource.Factory,
    private val dataSource: DataSource.Factory,
) : MediaSource.Factory {

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider) = apply {
        inner.setDrmSessionManagerProvider(drmSessionManagerProvider)
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy) = apply {
        inner.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    }

    override fun getSupportedTypes(): IntArray = inner.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val main = inner.createMediaSource(mediaItem)
        val audio = (mediaItem.localConfiguration?.tag as? SidecarAudio)?.locations.orEmpty()
        if (audio.isEmpty()) return main
        val sources = audio.map { location ->
            ProgressiveMediaSource.Factory(dataSource, EXTRACTORS)
                .createMediaSource(MediaItem.fromUri(playableUri(location)))
        }
        return MergingMediaSource(main, *sources.toTypedArray())
    }

    private companion object {
        /**
         * Merged sources must all seek to where the video does; a raw AC3, DTS, MP3 or AAC file only can
         * with constant-bitrate seeking. FFmpeg's demuxers come last, as for the videos.
         */
        val EXTRACTORS = object : ExtractorsFactory {
            private fun media3() = DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true)

            override fun createExtractors(): Array<Extractor> = seekable(media3().createExtractors())

            override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
                AviAudioFraming.recording(seekable(media3().createExtractors(uri, responseHeaders)), uri) +
                    FfmpegExtractor(uri, AviAudioFraming.remote)
        }

        fun seekable(extractors: Array<Extractor>): Array<Extractor> =
            extractors.map { if (it is Ac3Extractor) ConstantBitrateExtractor(it) else it }.toTypedArray()
    }
}

/**
 * Media3's raw AC3 / E-AC3 extractor reports its stream unseekable, though AC3 runs at a constant
 * bitrate: this one seeks it by that bitrate (Media3's [ConstantBitrateSeekMap]), taken from the first
 * frame's format; the AC3 reader finds the next frame from wherever a seek lands.
 */
@OptIn(UnstableApi::class)
private class ConstantBitrateExtractor(private val inner: Extractor) : Extractor {
    private var length = C.LENGTH_UNSET.toLong()

    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

    override fun init(output: ExtractorOutput) {
        var declared = false
        inner.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val track = output.track(id, type)
                return object : TrackOutput by track {
                    override fun format(format: Format) {
                        track.format(format)
                        val bitrate = format.averageBitrate.takeIf { it > 0 } ?: format.peakBitrate
                        if (!declared && bitrate > 0) {
                            declared = true
                            output.seekMap(ConstantBitrateSeekMap(length, 0, bitrate, C.LENGTH_UNSET))
                        }
                    }
                }
            }

            override fun endTracks() = output.endTracks()

            // The unseekable map is replaced by the constant-bitrate one once the first frame is known.
            override fun seekMap(seekMap: SeekMap) {
                if (seekMap.isSeekable) output.seekMap(seekMap)
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        length = input.length
        return inner.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)

    override fun release() = inner.release()

    override fun getUnderlyingImplementation(): Extractor = inner
}
