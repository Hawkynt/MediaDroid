package com.hawkynt.mediadroid.player

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.CodecParameters
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Passes the video renderers' events on, with the pixel shape the container declared put back into
 * the video size: Media3's software video renderers (the FFmpeg one among them) report every picture
 * with square pixels, so PAL DV or an anamorphic DVD would be shown squeezed. Every event is forwarded
 * by hand: Kotlin's `by` does not forward the listener's Java default methods.
 */
@OptIn(UnstableApi::class)
class PixelShapeListener(private val delegate: VideoRendererEventListener) : VideoRendererEventListener {

    @Volatile
    private var declared = Format.NO_VALUE.toFloat()

    override fun onVideoInputFormatChanged(format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        declared = format.pixelWidthHeightRatio
        delegate.onVideoInputFormatChanged(format, decoderReuseEvaluation)
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        val ratio = PlaybackMath.pixelRatio(videoSize.pixelWidthHeightRatio, declared)
        delegate.onVideoSizeChanged(
            if (ratio == videoSize.pixelWidthHeightRatio) {
                videoSize
            } else {
                VideoSize(videoSize.width, videoSize.height, ratio)
            },
        )
    }

    override fun onVideoEnabled(counters: DecoderCounters) = delegate.onVideoEnabled(counters)

    override fun onVideoDecoderInitialized(
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) = delegate.onVideoDecoderInitialized(decoderName, initializedTimestampMs, initializationDurationMs)

    override fun onDroppedFrames(count: Int, elapsedMs: Long) = delegate.onDroppedFrames(count, elapsedMs)

    override fun onVideoFrameProcessingOffset(totalProcessingOffsetUs: Long, frameCount: Int) =
        delegate.onVideoFrameProcessingOffset(totalProcessingOffsetUs, frameCount)

    override fun onRenderedFirstFrame(output: Any, renderTimeMs: Long) =
        delegate.onRenderedFirstFrame(output, renderTimeMs)

    override fun onVideoDecoderReleased(decoderName: String) = delegate.onVideoDecoderReleased(decoderName)

    override fun onVideoDisabled(counters: DecoderCounters) = delegate.onVideoDisabled(counters)

    override fun onVideoCodecError(videoCodecError: Exception) = delegate.onVideoCodecError(videoCodecError)

    override fun onVideoCodecParametersChanged(codecParameters: CodecParameters) =
        delegate.onVideoCodecParametersChanged(codecParameters)
}
