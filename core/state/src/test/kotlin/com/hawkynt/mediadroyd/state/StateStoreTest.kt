package com.hawkynt.mediadroyd.state

import com.hawkynt.mediadroyd.library.FolderPlace
import com.hawkynt.mediadroyd.library.Source
import com.hawkynt.mediadroyd.library.SourceKind
import com.hawkynt.mediadroyd.library.WallMode
import com.hawkynt.mediadroyd.player.AspectOverride
import com.hawkynt.mediadroyd.player.LogoMask
import com.hawkynt.mediadroyd.player.PictureAdjustment
import com.hawkynt.mediadroyd.player.ScreenSetup
import com.hawkynt.mediadroyd.player.ScreenShape
import com.hawkynt.mediadroyd.nfo.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateStoreTest {

    /** A file in memory. */
    private class Memory(var text: String? = null) : StateFile {
        override fun read(): String? = text

        override fun write(text: String) {
            this.text = text
        }
    }

    @Test
    fun `what is changed is written whole and reads back in a new store`() {
        val file = Memory()
        StateStore(file).apply {
            data.rootPath = "/media"
            saveResume("/media/a.mkv", 60_000, 6_000_000)
            markWatched("/media/b.mkv")
            saveWallMode(WallMode.LIST)
            saveScreenSetup("projector", ScreenSetup(ScreenShape.LENS_133, mirror = true))
        }
        val again = StateStore(file)
        assertEquals("/media", again.data.rootPath)
        assertEquals(ResumeInfo(60_000, 6_000_000), again.resumeFor("/media/a.mkv"))
        assertTrue(again.isWatched("/media/b.mkv"))
        assertEquals(WallMode.LIST, again.wallMode)
        assertEquals(ScreenSetup(ScreenShape.LENS_133, mirror = true), again.screenSetup("projector"))
    }

    @Test
    fun `the Android app's file of before the shared model reads as it was`() {
        val file = Memory(
            """{"rootPath":"/storage/emulated/0/Movies","watched":["/a.mkv"],"resume":{"/b.mkv":""" +
                """{"positionMs":5000,"durationMs":90000}},"wallMode":"DETAILS","settings":{"maxAgeYears":12,""" +
                """"brightness":0.2,"denoise":3,"subtitleLanguage":"en"}}""",
        )
        val store = StateStore(file)
        assertEquals("/storage/emulated/0/Movies", store.data.rootPath)
        assertEquals(listOf("primary"), store.sources.map { it.id })
        assertTrue(store.isWatched("/a.mkv"))
        assertEquals(5_000L, store.resumeFor("/b.mkv")?.positionMs)
        assertEquals(WallMode.DETAILS, store.wallMode)
        assertEquals(12, store.data.settings.maxAgeYears)
        assertEquals(0.2f, store.defaultPicture().brightness)
        // The old denoise step reads as the noise slider.
        assertEquals(-1f, store.defaultPicture().noise)
        // Nothing the desktop added is written where it was not set.
        store.save()
        assertFalse(file.text!!.contains("window"))
        assertFalse(file.text!!.contains("colorize"))
    }

    @Test
    fun `a file that is not this memory starts afresh, and a later version's unknown fields are read past`() {
        assertEquals(AppData(), StateStore(Memory("{ not json")).data)
        val later = StateStore(Memory("""{"rootPath":"/x","later":{"a":1},"settings":{"later":true}}"""))
        assertEquals("/x", later.data.rootPath)
    }

    @Test
    fun `the household's picture keeps what every video is offered, not the 3D eye, the shape or the logo`() {
        val file = Memory()
        val picture = PictureAdjustment(
            brightness = 0.1f, noise = 0.3f, deinterlace = PictureAdjustment.Deinterlace.BWDIF,
            upscaler = PictureAdjustment.Upscaler.FSR, colorize = PictureAdjustment.Colorize.DDCOLOR,
            cropBlackBars = true, stereo = PictureAdjustment.Stereo.LEFT, aspect = AspectOverride.SIXTEEN_NINE,
            logo = LogoMask(LogoMask.Corner.TOP_LEFT, 0.1f, LogoMask.Mode.BLUR), shader = "crt.glsl",
        )
        StateStore(file).saveDefaultPicture(picture)
        assertEquals(
            picture.copy(stereo = PictureAdjustment.Stereo.OFF, aspect = AspectOverride.AUTO, logo = null),
            StateStore(file).defaultPicture(),
        )
    }

    @Test
    fun `a video's own picture is kept only where it differs from what it starts with`() {
        val store = StateStore(Memory())
        store.saveDefaultPicture(PictureAdjustment(brightness = 0.2f, cropBlackBars = true))
        store.savePicture("/v/a.mkv", store.defaultPicture())
        assertNull(store.ownPictureOf("/v/a.mkv"))
        store.savePicture("/v/a.mkv", store.defaultPicture().copy(gamma = 1.5f))
        assertEquals(1.5f, store.pictureFor("/v/a.mkv").gamma)
        assertEquals(0.2f, store.pictureFor("/v/b.mkv").brightness)
    }

    @Test
    fun `a folder's picture is what its videos start with, under the media folder`() {
        val store = StateStore(Memory())
        store.data.rootPath = "/media"
        store.saveFolderPicture(store.folderOf("/media/VHS/tape.mkv"), PictureAdjustment(noise = -0.5f))
        assertEquals(FolderPlace(PRIMARY_SOURCE, "VHS"), store.pictureFolderOf("/media/VHS/1990/tape.mkv"))
        assertEquals(-0.5f, store.pictureFor("/media/VHS/1990/tape.mkv").noise)
        assertEquals(0f, store.pictureFor("/media/Films/x.mkv").noise)
    }

    @Test
    fun `a station logo is the show's, whichever slash the path uses`() {
        val store = StateStore(Memory())
        val logo = LogoMask(LogoMask.Corner.TOP_RIGHT, 0.2f, LogoMask.Mode.DIM)
        store.saveLogo("C:\\Shows\\News\\Monday.mkv", logo)
        assertEquals(logo, store.logoFor("C:/Shows/News/Tuesday.mkv"))
        assertEquals(logo, store.pictureFor("C:\\Shows\\News\\Wednesday.mkv").logo)
        assertNull(store.logoFor("C:/Shows/Films/Night.mkv"))
    }

    @Test
    fun `the sources start as the media folder, and a saved list is the list`() {
        val store = StateStore(Memory())
        assertEquals(emptyList<Source>(), store.sources)
        store.data.rootPath = "/media"
        assertEquals(listOf(Source.local(PRIMARY_SOURCE, "Media folder", "/media")), store.sources)
        val nas = Source("nas", "NAS", SourceKind.SMB, host = "nas", path = "/films")
        store.saveSources(listOf(Source.local(PRIMARY_SOURCE, "Media folder", "/other"), nas))
        assertEquals("/other", store.data.rootPath)
        assertEquals(listOf(PRIMARY_SOURCE, "nas"), store.sources.map { it.id })
    }

    @Test
    fun `a screen reading the memory is told when it changes`() {
        var reads = 0
        var changes = 0
        val store = StateStore(Memory(), object : StateWatcher {
            override fun read() {
                reads++
            }

            override fun changed() {
                changes++
            }
        })
        store.data.rootPath
        store.markWatched("/a.mkv")
        assertTrue(reads > 0)
        assertEquals(1, changes)
    }

    @Test
    fun `the last seconds count as finished, not as a place to resume`() {
        val store = StateStore(Memory())
        store.saveResume("/a.mkv", 5_990_000, 6_000_000)
        assertNull(store.resumeFor("/a.mkv"))
        store.saveResume("/a.mkv", 0, 6_000_000)
        assertNull(store.resumeFor("/a.mkv"))
    }

    @Test
    fun `a short look into a video leaves no resume point, a longer one does, and one under way moves on`() {
        val store = StateStore(Memory())
        // Some seconds into an episode: not something to continue.
        store.saveResume("/e1.mkv", 20_000, 2_400_000, playedMs = 20_000)
        assertNull(store.resumeFor("/e1.mkv"))
        // Three minutes played: it is.
        store.saveResume("/e1.mkv", 180_000, 2_400_000, playedMs = StateStore.MIN_PLAYED_MS)
        assertEquals(180_000L, store.resumeFor("/e1.mkv")?.positionMs)
        // Under way already, a short stretch more still moves the point on.
        store.saveResume("/e1.mkv", 200_000, 2_400_000, playedMs = 20_000)
        assertEquals(200_000L, store.resumeFor("/e1.mkv")?.positionMs)
        // A short clip resumes once half of it has played.
        store.saveResume("/clip.mkv", 70_000, 120_000, playedMs = 60_000)
        assertEquals(70_000L, store.resumeFor("/clip.mkv")?.positionMs)
    }

    @Test
    fun `a resume point can be forgotten, and only that one`() {
        val store = StateStore(Memory())
        store.saveResume("/a.mkv", 600_000, 6_000_000)
        store.saveResume("/b.mkv", 600_000, 6_000_000)
        store.forgetResume("/a.mkv")
        assertNull(store.resumeFor("/a.mkv"))
        assertEquals(600_000L, store.resumeFor("/b.mkv")?.positionMs)
        assertNull(StateStore(Memory(null)).resumeFor("/a.mkv"))
    }

    @Test
    fun `finishing a video already marked watched still drops the resume point it kept`() {
        val store = StateStore(Memory())
        store.markWatched("/e1.mkv")
        // A later look left a point behind (or an older version of the app did).
        store.data.resume["/e1.mkv"] = ResumeInfo(600_000, 2_400_000)
        store.markWatched("/e1.mkv")
        assertNull(store.resumeFor("/e1.mkv"))
        assertTrue(store.isWatched("/e1.mkv"))
    }

    @Test
    fun `a backup restored into another store brings everything along and is kept there`() {
        val old = StateStore(Memory()).apply {
            data.rootPath = "/media"
            saveResume("/media/a.mkv", 60_000, 6_000_000)
            markWatched("/media/b.mkv")
            saveWallMode(WallMode.LIST)
        }
        val file = Memory()
        val fresh = StateStore(file)
        assertTrue(fresh.restore(old.backup()))
        assertEquals("/media", fresh.data.rootPath)
        assertTrue(fresh.isWatched("/media/b.mkv"))
        assertEquals(60_000L, fresh.resumeFor("/media/a.mkv")?.positionMs)
        assertEquals(WallMode.LIST, StateStore(file).wallMode)
    }

    @Test
    fun `what is no backup restores nothing and leaves the memory as it was`() {
        val store = StateStore(Memory()).apply { markWatched("/media/b.mkv") }
        assertFalse(store.restore("not json"))
        assertFalse(store.restore("""{"name":"something else"}"""))
        assertFalse(store.restore("{}"))
        assertTrue(store.isWatched("/media/b.mkv"))
    }

    @Test
    fun `intros and credits found by their sound are kept per video, an episode checked without a find too`() {
        val file = Memory()
        StateStore(file).apply {
            saveFoundSkip(
                "/s/e1.mkv",
                FoundSkip(introStartMs = 30_000, introEndMs = 85_000, creditsStartMs = 2_500_000),
            )
            saveFoundSkip("/s/e2.mkv", FoundSkip())
        }
        val again = StateStore(file)
        assertEquals(TimeRange(30_000, 85_000), again.foundSkipFor("/s/e1.mkv")?.intro)
        assertEquals(2_500_000L, again.foundSkipFor("/s/e1.mkv")?.creditsStartMs)
        assertNull(again.foundSkipFor("/s/e2.mkv")?.intro)
        assertTrue(again.hasLookedForSkips("/s/e2.mkv"))
        assertFalse(again.hasLookedForSkips("/s/e3.mkv"))
    }
}
