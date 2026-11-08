package com.hawkynt.mediadroyd.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** The fake engine passes the contract every engine passes, so the screens' tests can stand on it. */
class FakePlaybackEngineTest : PlaybackEngineContract() {

    override fun newEngine(): PlaybackEngine = FakePlaybackEngine()

    override val media = MediaRequest("/films/clip.mkv")

    override val unreadable = MediaRequest(FakePlaybackEngine.UNREADABLE + "clip.mkv")

    override fun output(): VideoOutput = FakePlaybackEngine.Output()

    override fun pass(engine: PlaybackEngine, ms: Long) = (engine as FakePlaybackEngine).advance(ms)

    @Test
    fun `it keeps the commands it was given, in order`() {
        val fake = engine as FakePlaybackEngine
        fake.open(media, autoPlay = false)
        fake.seekTo(500)
        fake.play()
        fake.select(Track.Kind.AUDIO, "a2")
        assertEquals(listOf("open /films/clip.mkv", "seek 500", "play", "select AUDIO a2"), fake.commands)
    }
}
