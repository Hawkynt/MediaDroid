package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.engine.MediaRequest
import com.hawkynt.mediadroid.engine.PlaybackTuning
import com.hawkynt.mediadroid.engine.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

class DesktopPlaybackEngineTest {

    private fun fixture(name: String): String = File(requireNotNull(javaClass.getResource("/$name")).toURI()).path
    private val clip: String = fixture("clip.mp4")

    /** A sound output whose played time the test moves on by hand. */
    private class ManualSink : AudioSink {
        @Volatile var playedMs = 0L
        @Volatile var flushed = 0
        @Volatile var written = 0L
        @Volatile var peak = 0
        override fun open(sampleRate: Int, channels: Int) = Unit
        override fun write(pcm: ByteArray) {
            peak = maxOf(peak, peakOf(pcm))
            written += pcm.size
            Thread.sleep(1)
        }
        override fun playedMs(): Long = playedMs
        override fun pause() = Unit
        override fun resume() = Unit
        override fun flush() {
            flushed++
            playedMs = 0
        }
        override var volume: Float = 1f
        override fun close() = Unit
    }

    private companion object {
        /** The loudest sample in 16-bit little-endian PCM. */
        fun peakOf(pcm: ByteArray): Int {
            var peak = 0
            for (i in 0 until pcm.size / 2) {
                val sample = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
                peak = maxOf(peak, kotlin.math.abs(sample.toShort().toInt()))
            }
            return peak
        }
    }

    /** The loudest sample of a file's sound as decoded, untouched. */
    private fun decodedPeak(path: String): Int = MediaReader(path).use { r ->
        generateSequence { r.read() }.filterIsInstance<MediaReader.Sound>().maxOf { peakOf(it.pcm) }
    }

    /** Plays [path] with [tuning] to near its end into a sink and returns the loudest sample the sink was given. */
    private fun playedPeak(path: String, tuning: PlaybackTuning): Int {
        val sink = ManualSink()
        DesktopPlaybackEngine(tuning) { sink }.use { engine ->
            engine.open(MediaRequest(path))
            // The clock moved on by hand, so the pictures are taken and the reader goes on.
            waitFor {
                sink.playedMs += 20
                engine.picture()
                engine.durationMs > 0 && sink.written >= engine.durationMs * 48 * 4 * 9 / 10
            }
        }
        return sink.peak
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `the picture shown is the latest one due by the sound's clock`() {
        val sink = ManualSink()
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { sink }.use { engine ->
            engine.open(MediaRequest(clip))
            waitFor { engine.durationMs > 0 && engine.isPlaying }
            waitFor { engine.picture()?.ptsMs == 0L }
            sink.playedMs = 130
            waitFor { engine.picture()?.ptsMs == 120L }
            assertEquals(130L, engine.positionMs)
        }
    }

    @Test
    fun `a moment holds the picture shown, the one before, the two decoded after it and how far the clock is`() {
        val sink = ManualSink()
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { sink }.use { engine ->
            engine.open(MediaRequest(clip))
            waitFor { engine.picture()?.ptsMs == 0L }
            sink.playedMs = 90
            waitFor { engine.picture()?.ptsMs == 80L }
            sink.playedMs = 130
            waitFor { engine.moment()?.let { it.shown.ptsMs == 120L && it.after != null } == true }
            val moment = requireNotNull(engine.moment())
            assertEquals(80L, moment.previous?.ptsMs)
            assertEquals(160L, moment.next?.ptsMs)
            assertEquals(200L, moment.after?.ptsMs)
            assertEquals(0.25f, moment.phase, 1e-6f)
        }
    }

    @Test
    fun `with Film on, progressive pictures still come through, every one of a cycle without a repeat`() {
        val sink = ManualSink()
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { sink }.use { engine ->
            engine.film = true
            engine.open(MediaRequest(clip))
            waitFor { engine.picture() != null }
            sink.playedMs = 400
            waitFor { (engine.picture()?.ptsMs ?: 0L) >= 360L }
        }
    }

    @Test
    fun `a seek starts the clock and the pictures at the new time`() {
        val sink = ManualSink()
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { sink }.use { engine ->
            engine.open(MediaRequest(clip))
            waitFor { engine.isPlaying }
            engine.seekTo(1000)
            waitFor { sink.flushed > 0 && engine.picture()?.ptsMs == 1000L }
            assertEquals(1000L, engine.positionMs)
            sink.playedMs = 90
            waitFor { engine.picture()?.ptsMs == 1080L }
        }
    }

    @Test
    fun `a seek while paused shows the picture there at once, as a frame step needs`() {
        val sink = ManualSink()
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { sink }.use { engine ->
            engine.open(MediaRequest(clip), autoPlay = false)
            waitFor { engine.durationMs > 0 }
            assertEquals(25f, engine.frameRate, 0.001f)
            // Between two pictures: the one after it is the one to see, though the clock has not reached it.
            engine.seekTo(1001)
            waitFor { engine.picture()?.ptsMs == 1040L }
            assertEquals(1001L, engine.positionMs)
        }
    }

    @Test
    fun `nothing is shown before anything is open`() {
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { ManualSink() }.use { engine ->
            assertNull(engine.picture())
            assertEquals(0L, engine.durationMs)
            assertTrue(engine.tracks.isEmpty())
        }
    }

    @Test
    fun `a subtitle file beside the video is a track, and its line shows while it is due`() {
        val sink = ManualSink()
        val srt = MediaRequest.SubtitleFile(fixture("clip.de.srt"), null, "de", "Deutsch", forced = false)
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { sink }.use { engine ->
            engine.open(MediaRequest(clip, subtitles = listOf(srt)))
            waitFor { engine.isPlaying }
            val track = engine.tracks.single { it.kind == Track.Kind.TEXT }
            assertEquals(1, track.source)
            assertNull(engine.subtitle())
            engine.select(Track.Kind.TEXT, track.id)
            assertTrue(engine.tracks.single { it.kind == Track.Kind.TEXT }.selected)
            Thread.sleep(100) // the reader thread takes the switch in
            sink.playedMs = 300
            waitFor { engine.subtitle() == "First line" }
            sink.playedMs = 900
            waitFor { engine.subtitle() == null }
            engine.subtitlesOn = false
            sink.playedMs = 1200
            Thread.sleep(20)
            assertNull(engine.subtitle())
        }
    }

    @Test
    fun `a file's own subtitle stream shows the same way once picked`() {
        val sink = ManualSink()
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { sink }.use { engine ->
            engine.open(MediaRequest(fixture("subtitled.mkv")))
            waitFor { engine.isPlaying }
            val track = engine.tracks.single { it.kind == Track.Kind.TEXT }
            assertEquals(0, track.source)
            engine.select(Track.Kind.TEXT, track.id)
            sink.playedMs = 1200
            waitFor { engine.subtitle() == "Second line\nwith two rows" }
        }
    }

    @Test
    fun `a sound file beside the video is a track, and once picked its sound plays with the pictures`() {
        val sink = ManualSink()
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { sink }.use { engine ->
            engine.open(MediaRequest(clip, audio = listOf(fixture("clip.en.ac3"))))
            waitFor { engine.isPlaying }
            val sound = engine.tracks.filter { it.kind == Track.Kind.AUDIO }
            assertEquals(listOf(0, 1), sound.map { it.source })
            assertEquals(listOf(true, false), sound.map { it.selected })
            engine.select(Track.Kind.AUDIO, sound[1].id)
            waitFor { sink.flushed > 0 }
            assertEquals(listOf(false, true), engine.tracks.filter { it.kind == Track.Kind.AUDIO }.map { it.selected })
            val before = sink.written
            waitFor { sink.written > before + 4 * 48_000 / 10 }
            sink.playedMs = 600
            waitFor { (engine.picture()?.ptsMs ?: 0) >= 560 }
        }
    }

    @Test
    fun `at twice the speed the clock runs twice as fast as the sound played`() {
        val sink = ManualSink()
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { sink }.use { engine ->
            engine.open(MediaRequest(clip))
            waitFor { engine.isPlaying }
            engine.speed = 2f
            waitFor { sink.flushed > 0 }
            val from = engine.positionMs
            sink.playedMs = 300
            assertEquals(from + 600, engine.positionMs)
            waitFor { engine.picture()?.ptsMs == (from + 600) / 40 * 40 }
        }
    }

    @Test
    fun `the loudness setting turns the sound down or up on its way to the output`() {
        val untouched = decodedPeak(clip)
        val quieter = playedPeak(clip, PlaybackTuning.Mutable().apply { loudnessDb = -20f })
        assertEquals(untouched / 10.0, quieter.toDouble(), 2.0)
        val louder = playedPeak(clip, PlaybackTuning.Mutable().apply { loudnessDb = 6f })
        assertTrue("$louder against $untouched", louder > untouched * 3 / 2)
    }

    @Test
    fun `with even loudness a file plays at its ReplayGain`() {
        val tagged = fixture("replaygain.flac")
        val untouched = decodedPeak(tagged)
        val even = playedPeak(tagged, PlaybackTuning.Mutable().apply { normalizeLoudness = true })
        assertEquals(untouched * 10.0.pow(-7.5 / 20), even.toDouble(), 2.0)
        assertEquals(untouched, playedPeak(tagged, PlaybackTuning.Mutable()))
    }

    @Test
    fun `night mode lifts a quiet sound`() {
        val untouched = decodedPeak(clip)
        val night = playedPeak(clip, PlaybackTuning.Mutable().apply { nightMode = true })
        assertTrue("$night against $untouched", night > untouched * 3 / 2)
    }

    @Test
    fun `an audio delay moves the pictures against the sound`() {
        val sink = ManualSink()
        val tuning = PlaybackTuning.Mutable().apply { audioDelayMs = 200 }
        DesktopPlaybackEngine(tuning) { sink }.use { engine ->
            engine.open(MediaRequest(clip))
            waitFor { engine.isPlaying }
            // The sound later: the picture is ahead of what is heard.
            waitFor { engine.picture()?.ptsMs == 200L }
            assertEquals(0L, engine.positionMs)
            // The sound sooner: the picture waits, held back further than the few pictures usually decoded ahead.
            tuning.audioDelayMs = -400
            sink.playedMs = 800
            waitFor { engine.picture()?.ptsMs == 400L }
            sink.playedMs = 1000
            waitFor { engine.picture()?.ptsMs == 600L }
        }
    }
}
