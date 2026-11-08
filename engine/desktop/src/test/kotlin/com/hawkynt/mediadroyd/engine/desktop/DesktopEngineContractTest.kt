package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.engine.MediaRequest
import com.hawkynt.mediadroyd.engine.PlaybackEngine
import com.hawkynt.mediadroyd.engine.PlaybackEngineContract
import com.hawkynt.mediadroyd.engine.PlaybackTuning
import com.hawkynt.mediadroyd.engine.VideoOutput
import java.io.File

/**
 * The desktop engine passes the contract every engine passes: on clip.mp4 (two seconds, MPEG-4 and AAC) with the AC-3
 * file beside it, its clock moved on by hand through a sound output that plays nothing, its pictures taken as the
 * window's stage takes them.
 */
class DesktopEngineContractTest : PlaybackEngineContract() {

    private fun fixture(name: String): String = File(requireNotNull(javaClass.getResource("/$name")).toURI()).path

    /** A sound output whose played time [pass] moves on while the engine plays. */
    private class HandSink : AudioSink {
        @Volatile var playedMs = 0L
        override fun open(sampleRate: Int, channels: Int) = Unit
        override fun write(pcm: ByteArray) = Unit
        override fun available(): Int = Int.MAX_VALUE
        override fun playedMs(): Long = playedMs
        override fun pause() = Unit
        override fun resume() = Unit
        override fun flush() {
            playedMs = 0
        }
        override var volume: Float = 1f
        override fun close() = Unit
    }

    private val sinks = mutableListOf<HandSink>()

    override fun newEngine(): PlaybackEngine =
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { HandSink().also { synchronized(sinks) { sinks += it } } }

    override val media: MediaRequest
        get() = MediaRequest(fixture("clip.mp4"), audio = listOf(fixture("clip.en.ac3")))

    override fun output(): VideoOutput = DesktopPlaybackEngine.StageOutput()

    override fun pass(engine: PlaybackEngine, ms: Long) {
        if (engine.isPlaying) synchronized(sinks) { sinks.lastOrNull() }?.let { it.playedMs += ms }
        (engine as DesktopPlaybackEngine).moment()
        Thread.sleep(ms / 2)
    }
}
