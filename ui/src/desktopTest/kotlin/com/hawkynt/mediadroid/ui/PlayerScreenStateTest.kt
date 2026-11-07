package com.hawkynt.mediadroid.ui

import com.hawkynt.mediadroid.engine.FakePlaybackEngine
import com.hawkynt.mediadroid.engine.MediaRequest
import com.hawkynt.mediadroid.engine.Track
import com.hawkynt.mediadroid.library.Sidecars
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.state.StateFile
import com.hawkynt.mediadroid.state.StateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// What the one player screen keeps for both apps, over the engine contract's fake engine.
class PlayerScreenStateTest {

    private val store = StateStore(
        object : StateFile {
            override fun read(): String? = null

            override fun write(text: String) = Unit
        },
    )
    private val engine = FakePlaybackEngine(length = 600_000)
    private val queue = (1..2).map { Playable("Episode $it", null, "/show/e$it.mkv", null) }

    private fun state(demo: Boolean = false) =
        PlayerScreenState(queue, 0, store, engine, autoAdvance = true, demo = demo)

    @Test
    fun `the files beside a video go along with it, subtitles and sound apart`() {
        val sidecars = Sidecars.of("e1.mkv", listOf("e1.en.srt", "e1.de.mka", "other.srt"))
            .map { it to "/show/${it.name}" }
        val request = PlayerScreenState.request("/show/e1.mkv", sidecars, startMs = 42_000, audioLanguage = "de")
        assertEquals("/show/e1.mkv", request.path)
        assertEquals(42_000, request.startMs)
        assertEquals("de", request.audioLanguage)
        assertEquals(listOf("/show/e1.en.srt"), request.subtitles.map { it.path })
        assertEquals("en", request.subtitles.single().language)
        assertEquals(listOf("/show/e1.de.mka"), request.audio)
    }

    @Test
    fun `picking subtitles by track makes their language the household's, and none switches them off`() {
        val s = state()
        engine.open(MediaRequest("/show/e1.mkv"))
        val first = engine.tracks.first { it.kind == Track.Kind.TEXT }
        s.pick(Track.Kind.TEXT, first.id, byTrack = true)
        assertTrue(s.session.manualTrackPick)
        assertTrue(store.data.settings.subtitlesOn)
        assertEquals(first.language, store.data.settings.subtitleLanguage)
        assertEquals("select TEXT ${first.id}", engine.commands.last())
        s.pick(Track.Kind.TEXT, null, byTrack = true)
        assertFalse(store.data.settings.subtitlesOn)
        assertEquals("", s.householdSubtitleLanguage(byTrack = true))
    }

    @Test
    fun `on the phone a subtitle pick switches subtitles on and leaves the household's language alone`() {
        val s = state()
        store.data.settings.subtitleLanguage = "en"
        engine.open(MediaRequest("/show/e1.mkv"))
        s.subtitlesOn = false
        engine.subtitlesOn = false
        val track = engine.tracks.first { it.kind == Track.Kind.TEXT }
        s.pick(Track.Kind.TEXT, track.id, byTrack = false)
        assertTrue(s.subtitlesOn)
        assertTrue(engine.subtitlesOn)
        assertEquals("en", store.data.settings.subtitleLanguage)
        assertEquals("en", s.householdSubtitleLanguage(byTrack = false))
    }

    @Test
    fun `a picture kept for the folder is the next video's too, and forgetting it brings the household's back`() {
        val s = state()
        val vivid = PictureAdjustment(saturation = 1.4f)
        s.picture = vivid
        s.useForFolder()
        assertEquals(vivid.saturation, store.pictureFor("/show/e2.mkv").saturation)
        assertTrue(s.pictureFolder != null)
        s.forgetFolder()
        assertNull(s.pictureFolder)
        assertEquals(PictureAdjustment().saturation, store.pictureFor("/show/e2.mkv").saturation)
    }

    @Test
    fun `a reset picture is the one the video would start with`() {
        val s = state()
        s.picture = PictureAdjustment(contrast = 1.3f)
        s.keepPicture()
        assertEquals(1.3f, store.pictureFor("/show/e1.mkv").contrast)
        s.resetPicture()
        assertEquals(PictureAdjustment().contrast, s.picture.contrast)
        assertEquals(PictureAdjustment().contrast, store.pictureFor("/show/e1.mkv").contrast)
    }

    @Test
    fun `each video brings its own picture and is remembered as the last played, but not in the demo`() {
        store.savePicture("/show/e2.mkv", PictureAdjustment(brightness = 0.2f))
        val s = state()
        s.session.next()
        s.videoChanged()
        assertEquals(0.2f, s.picture.brightness)
        assertEquals("/show/e2.mkv", store.data.lastPlayed)
        store.data.lastPlayed = ""
        val demo = state(demo = true)
        demo.videoChanged()
        assertEquals("", store.data.lastPlayed)
    }

    @Test
    fun `the speed keys step by a twentieth, within the slider's range`() {
        val s = state()
        s.speed = 1f
        assertEquals(1.05f, s.steppedSpeed(+1), 1e-4f)
        s.speed = 5f
        assertEquals(5f, s.steppedSpeed(+1), 1e-4f)
        s.speed = 0.2f
        assertEquals(0.2f, s.steppedSpeed(-1), 1e-4f)
    }

    @Test
    fun `a confirmation is shown afresh each time, the accent marking a level past the maximum`() {
        val s = state()
        s.show("+15 s")
        val first = s.feedbackStamp
        s.show("Volume 120 %", boosted = true)
        assertEquals("Volume 120 %", s.feedback)
        assertTrue(s.feedbackBoosted)
        assertEquals(first + 1, s.feedbackStamp)
    }
}
