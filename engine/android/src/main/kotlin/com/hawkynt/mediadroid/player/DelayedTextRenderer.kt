package com.hawkynt.mediadroid.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer

/**
 * Subtitle delay: the text renderer is asked for the cues of a shifted moment — [delayMs] earlier, so
 * positive delays show subtitles later and negative ones earlier (their samples are read well ahead).
 */
@OptIn(UnstableApi::class)
class DelayedTextRenderer(renderer: Renderer, private val delayMs: () -> Int) : ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) =
        super.render(positionUs - delayMs() * 1000L, elapsedRealtimeUs)
}
