package com.hawkynt.mediadroid.state

import com.hawkynt.mediadroid.library.FolderPlace
import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import com.hawkynt.mediadroid.library.WallMode
import com.hawkynt.mediadroid.player.AspectOverride
import com.hawkynt.mediadroid.player.LogoMask
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.ScreenSetup
import com.hawkynt.mediadroid.player.ScreenShape
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
}
