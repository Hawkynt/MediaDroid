package com.hawkynt.mediadroyd.desktop

import com.hawkynt.mediadroyd.library.Source
import com.hawkynt.mediadroyd.library.SourceKind
import com.hawkynt.mediadroyd.player.AspectOverride
import com.hawkynt.mediadroyd.player.LogoMask
import com.hawkynt.mediadroyd.player.NeuralEnlarging
import com.hawkynt.mediadroyd.player.PictureAdjustment
import com.hawkynt.mediadroyd.player.ScreenSetup
import com.hawkynt.mediadroyd.player.ScreenShape
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val nas = Source("nas", "NAS", SourceKind.SMB, host = "nas", path = "/media", user = "me")

    @Test
    fun `everything set is kept as JSON in state_json and reads back in a new store`() {
        val data = folder.newFolder("data")
        DesktopStore(data).apply {
            libraryFolder = "/home/me/Videos"
            picture = PictureAdjustment(
                brightness = 0.25f, deband = 0.5f, deinterlace = PictureAdjustment.Deinterlace.YADIF,
                cropBlackBars = false,
            )
            subtitleLanguage = "de"
            speed = 1.5f
            sound = SoundSettings(loudnessDb = -4f, evenLoudness = true, audioDelayMs = -150)
            window = "10.0,20.0,800.0,600.0,false"
            sources = listOf(nas)
            setResume("/v/a.mkv", 42_000)
            markWatched("/v/b.mkv")
        }
        val json = Json.parseToJsonElement(File(data, "state.json").readText()).jsonObject
        assertEquals("/home/me/Videos", json["rootPath"]?.jsonPrimitive?.content)
        assertFalse(File(data, "state.properties").exists())

        val again = DesktopStore(data)
        assertEquals("/home/me/Videos", again.libraryFolder)
        assertEquals(0.25f, again.picture.brightness)
        assertEquals(0.5f, again.picture.deband)
        assertEquals(PictureAdjustment.Deinterlace.YADIF, again.picture.deinterlace)
        assertFalse(again.picture.cropBlackBars)
        assertEquals("de", again.subtitleLanguage)
        assertEquals(1.5f, again.speed)
        assertEquals(SoundSettings(loudnessDb = -4f, evenLoudness = true, audioDelayMs = -150), again.sound)
        assertEquals("10.0,20.0,800.0,600.0,false", again.window)
        assertEquals(listOf(nas), again.sources)
        assertEquals(42_000L, again.resumeMs("/v/a.mkv"))
        assertTrue(again.watched("/v/b.mkv"))
        assertFalse(again.watched("/v/a.mkv"))
    }

    @Test
    fun `smooth motion, field rate and the upscaler's strength are the household's, the 3D eye is not`() {
        val data = folder.newFolder("data")
        DesktopStore(data).picture = PictureAdjustment(
            deinterlace = PictureAdjustment.Deinterlace.BWDIF,
            fieldRate = true,
            upscaler = PictureAdjustment.Upscaler.FSR,
            upscalerStrength = 0.6f,
            interpolation = PictureAdjustment.Interpolation.MOTION,
            stereo = PictureAdjustment.Stereo.LEFT,
        )
        val again = DesktopStore(data).picture
        assertEquals(PictureAdjustment.Interpolation.MOTION, again.interpolation)
        assertTrue(again.fieldRate)
        assertEquals(0.6f, again.upscalerStrength)
        assertEquals(PictureAdjustment.Stereo.OFF, again.stereo)
    }

    @Test
    fun `the upscaler's model and its eight-way run are kept with the picture`() {
        val data = folder.newFolder("data")
        DesktopStore(data).picture = PictureAdjustment(
            upscaler = PictureAdjustment.Upscaler.WAIFU2X,
            upscalerModel = "noise3",
            upscalerTta = true,
        )
        val again = DesktopStore(data).picture
        assertEquals("noise3", again.upscalerModel)
        assertTrue(again.upscalerTta)
    }

    @Test
    fun `colour and the colouriser's passed self-test are kept`() {
        val data = folder.newFolder("data")
        val passed = com.hawkynt.mediadroyd.player.NeuralInterpolation.Passed("GPU", mapOf(256 to 120.0))
        DesktopStore(data).apply {
            picture = PictureAdjustment(colorize = PictureAdjustment.Colorize.DDCOLOR)
            keepColorizer(passed)
        }
        val again = DesktopStore(data)
        assertEquals(PictureAdjustment.Colorize.DDCOLOR, again.picture.colorize)
        assertEquals(passed, again.colorizerPassed)
        assertNull(DesktopStore(folder.newFolder("fresh")).colorizerPassed)
    }

    @Test
    fun `a station logo is kept for the show's folder, every episode in it, no other`() {
        val data = folder.newFolder("data")
        val logo = LogoMask(LogoMask.Corner.TOP_RIGHT, 0.2f, LogoMask.Mode.DIM)
        DesktopStore(data).saveLogo("/shows/News/Monday.mkv", logo)
        val again = DesktopStore(data)
        assertEquals(logo, again.logoFor("/shows/News/Tuesday.mkv"))
        assertEquals(null, again.logoFor("/shows/Films/Night.mkv"))
        again.saveLogo("/shows/News/Monday.mkv", null)
        assertEquals(null, DesktopStore(data).logoFor("/shows/News/Tuesday.mkv"))
        // The household's own picture keeps no logo.
        assertEquals(null, DesktopStore(data).picture.logo)
    }

    @Test
    fun `a network's passed self-test is kept, so it is not tested again`() {
        val data = folder.newFolder("data")
        val passed = com.hawkynt.mediadroyd.player.NeuralInterpolation.Passed("GPU", mapOf(480 to 6.5, 720 to 14.0))
        DesktopStore(data).apply {
            keepMotion(PictureAdjustment.Interpolation.DAIN, passed)
            keepEnlarger(NeuralEnlarging.choice("REALSR")!!, passed)
            keepEnlarger(NeuralEnlarging.choice("REALCUGAN:se-up4x-denoise3x+tta")!!, passed)
        }
        val again = DesktopStore(data)
        assertEquals(passed, again.motionPassed(PictureAdjustment.Interpolation.DAIN))
        // Each model of an upscaler, and each run eight ways, is kept on its own.
        assertEquals(
            mapOf(
                NeuralEnlarging.choice("REALSR")!! to passed,
                NeuralEnlarging.choice("REALCUGAN:se-up4x-denoise3x+tta")!! to passed,
            ),
            again.neuralEnlargers,
        )
        assertEquals(null, again.enlargerPassed(NeuralEnlarging.choice("REALCUGAN")!!))
        assertEquals(null, again.motionPassed(PictureAdjustment.Interpolation.RIFE))
    }

    @Test
    fun `a video keeps its own subtitle delay, in sync forgets it, and the others start in sync`() {
        val data = folder.newFolder("data")
        DesktopStore(data).apply {
            setSubtitleDelay("/v/a.mkv", 1_500)
            setSubtitleDelay("/v/b.mkv", -300)
            setSubtitleDelay("/v/b.mkv", 0)
        }
        val again = DesktopStore(data)
        assertEquals(1_500, again.subtitleDelayMs("/v/a.mkv"))
        assertEquals(0, again.subtitleDelayMs("/v/b.mkv"))
        assertEquals(0, again.subtitleDelayMs("/v/c.mkv"))
        val json = Json.parseToJsonElement(File(data, "state.json").readText()).jsonObject
        assertEquals("{\"/v/a.mkv\":{\"delayMs\":1500}}", json["subtitles"].toString())
    }

    @Test
    fun `software decoding first is off until the household picks it, then kept`() {
        val data = folder.newFolder("data")
        assertFalse(DesktopStore(data).softwareDecoding)
        DesktopStore(data).softwareDecoding = true
        assertTrue(DesktopStore(data).softwareDecoding)
        val json = Json.parseToJsonElement(File(data, "state.json").readText()).jsonObject
        assertEquals("true", json["settings"]?.jsonObject?.get("softwareVideoDecoding")?.jsonPrimitive?.content)
    }

    @Test
    fun `a fresh store has the defaults, and watching a video forgets where it was left`() {
        val store = DesktopStore(folder.newFolder("data"))
        assertNull(store.libraryFolder)
        // Black bars are cropped unless the household says otherwise.
        assertEquals(PictureAdjustment.Neutral.copy(cropBlackBars = true), store.picture)
        assertEquals("", store.subtitleLanguage)
        assertEquals(1f, store.speed)
        assertEquals(SoundSettings(0f, false, 0), store.sound)
        assertEquals(emptyList<Source>(), store.sources)
        store.setResume("/v/a.mkv", 5_000)
        store.markWatched("/v/a.mkv")
        assertEquals(0L, store.resumeMs("/v/a.mkv"))
        store.setResume("/v/c.mkv", 5_000)
        store.setResume("/v/c.mkv", 0)
        assertEquals(0L, store.resumeMs("/v/c.mkv"))
    }

    @Test
    fun `the properties file of earlier versions is taken over once and moved aside`() {
        val data = folder.newFolder("data")
        File(data, "state.properties").writeText(
            """
            library=/home/me/Videos
            picture.brightness=0.5
            picture.upscaler=LANCZOS
            picture.deband=0.75
            subtitles.language=en
            speed=2.0
            window=1.0,2.0,3.0,4.0,true
            source.000=${Source.encode(nas).replace("\\", "\\\\").replace(":", "\\:")}
            resume./v/a.mkv=1234
            watched./v/b.mkv=1
            """.trimIndent(),
        )
        val store = DesktopStore(data)
        assertEquals("/home/me/Videos", store.libraryFolder)
        assertEquals(0.5f, store.picture.brightness)
        assertEquals(PictureAdjustment.Upscaler.LANCZOS, store.picture.upscaler)
        assertEquals(0.75f, store.picture.deband)
        assertTrue(store.picture.cropBlackBars)
        assertEquals("en", store.subtitleLanguage)
        assertEquals(2f, store.speed)
        assertEquals("1.0,2.0,3.0,4.0,true", store.window)
        assertEquals(listOf(nas), store.sources)
        assertEquals(1234L, store.resumeMs("/v/a.mkv"))
        assertTrue(store.watched("/v/b.mkv"))
        assertTrue(File(data, "state.json").exists())
        assertFalse(File(data, "state.properties").exists())
        assertTrue(File(data, "state.properties.old").exists())
        assertEquals("/home/me/Videos", DesktopStore(data).libraryFolder)
    }

    @Test
    fun `a damaged state file starts from the defaults and is kept aside, not lost`() {
        val data = folder.newFolder("data")
        File(data, "state.json").writeText("{ not json")
        val store = DesktopStore(data)
        assertNull(store.libraryFolder)
        store.speed = 1.25f
        assertEquals("{ not json", File(data, "state.json.damaged").readText())
        assertEquals(1.25f, DesktopStore(data).speed)
    }

    @Test
    fun `a damaged settings file of an earlier version is kept aside, and the app starts afresh`() {
        val data = folder.newFolder("data")
        File(data, "settings.json").writeText("{ not json")
        assertNull(DesktopStore(data).libraryFolder)
        assertEquals("{ not json", File(data, "settings.json.damaged").readText())
        assertFalse(File(data, "settings.json").exists())
    }

    @Test
    fun `state written by a later version with fields this one does not know still reads`() {
        val data = folder.newFolder("data")
        File(data, "state.json").writeText(
            """{"rootPath":"/x","settings":{"defaultSpeed":0.75,"later":1},"somethingNew":{"a":1}}""",
        )
        val store = DesktopStore(data)
        assertEquals("/x", store.libraryFolder)
        assertEquals(0.75f, store.speed)
    }

    @Test
    fun `a folder's picture is what its videos start with, the nearest folder's first, else the household's`() {
        val data = folder.newFolder("data")
        DesktopStore(data).apply {
            sources = listOf(nas)
            picture = PictureAdjustment(brightness = 0.1f)
            setFolderPicture(folderOf("smb://nas/media/VHS/a.mkv"), PictureAdjustment(
                deinterlace = PictureAdjustment.Deinterlace.BWDIF, noise = -0.5f,
            ))
        }
        val again = DesktopStore(data)
        val vhs = again.pictureFor("smb://nas/media/VHS/1990/tape.mkv")
        assertEquals(PictureAdjustment.Deinterlace.BWDIF, vhs.deinterlace)
        assertEquals(-0.5f, vhs.noise)
        assertEquals(com.hawkynt.mediadroyd.library.FolderPlace("nas", "VHS"),
            again.pictureFolderOf("smb://nas/media/VHS/1990/tape.mkv"))
        // Beside it, the household's.
        assertEquals(0.1f, again.pictureFor("smb://nas/media/VHS-rips/x.mkv").brightness)
        assertNull(again.pictureFolderOf("/home/me/a.mkv"))
        again.setFolderPicture(com.hawkynt.mediadroyd.library.FolderPlace("nas", "VHS"), null)
        assertEquals(0.1f, DesktopStore(data).pictureFor("smb://nas/media/VHS/1990/tape.mkv").brightness)
    }

    @Test
    fun `a video keeps a picture of its own over its folder's and the household's, until it is reset`() {
        val data = folder.newFolder("data")
        DesktopStore(data).apply {
            sources = listOf(nas)
            picture = PictureAdjustment(brightness = 0.1f)
            setFolderPicture(folderOf("smb://nas/media/VHS/a.mkv"), PictureAdjustment(noise = -0.5f))
            savePicture("smb://nas/media/VHS/a.mkv", PictureAdjustment(contrast = 1.5f, noise = -0.5f))
            // What it would start with anyway is not kept as its own.
            savePicture("smb://nas/media/VHS/b.mkv", pictureFor("smb://nas/media/VHS/b.mkv"))
        }
        val again = DesktopStore(data)
        assertEquals(1.5f, again.pictureFor("smb://nas/media/VHS/a.mkv").contrast)
        assertEquals(1f, again.pictureFor("smb://nas/media/VHS/b.mkv").contrast)
        assertEquals(1f, again.pictureFor("smb://nas/media/Films/c.mkv").contrast)
        assertEquals(0.1f, again.pictureFor("smb://nas/media/Films/c.mkv").brightness)
        assertFalse(File(data, "state.json").readText().contains("VHS/b.mkv"))
        // Reset: back to its folder's.
        again.savePicture("smb://nas/media/VHS/a.mkv", null)
        assertEquals(PictureAdjustment(noise = -0.5f), DesktopStore(data).pictureFor("smb://nas/media/VHS/a.mkv"))
    }

    @Test
    fun `a video's shape and each screen's setup are kept, each for its own`() {
        val data = folder.newFolder("data")
        DesktopStore(data).apply {
            savePicture("/v/dvd.mkv", pictureFor("/v/dvd.mkv").copy(aspect = AspectOverride.SIXTEEN_NINE))
            setScreenSetup(
                "projector",
                ScreenSetup(ScreenShape.LENS_133, true, true, keystoneVertical = 0.1f, burnInCare = true),
            )
        }
        val again = DesktopStore(data)
        assertEquals(AspectOverride.SIXTEEN_NINE, again.pictureFor("/v/dvd.mkv").aspect)
        assertEquals(AspectOverride.AUTO, again.pictureFor("/v/other.mkv").aspect)
        assertEquals(
            ScreenSetup(ScreenShape.LENS_133, true, true, keystoneVertical = 0.1f, burnInCare = true),
            again.screenSetup("projector"),
        )
        assertEquals(ScreenSetup.Default, again.screenSetup("monitor"))
        // Back to the defaults: nothing is kept for them.
        again.savePicture("/v/dvd.mkv", null)
        again.setScreenSetup("projector", ScreenSetup.Default)
        val text = File(data, "state.json").readText()
        assertFalse(text.contains("/v/dvd.mkv"))
        assertFalse(text.contains("projector"))
    }

    @Test
    fun `the settings file of earlier versions is taken over once, into the model every platform keeps, and moved aside`() {
        val data = folder.newFolder("data")
        File(data, "settings.json").writeText(
            """
            {
              "library": "/home/me/Videos",
              "picture": { "brightness": 0.25, "deinterlace": "BWDIF", "colorize": "DDCOLOR", "cropBlackBars": false },
              "subtitleLanguage": "de",
              "speed": 1.5,
              "sound": { "loudnessDb": -4.0, "evenLoudness": true, "audioDelayMs": -150 },
              "window": "1.0,2.0,3.0,4.0,true",
              "sources": [${Json.encodeToString(Source.serializer(), nas)}],
              "resume": { "/v/a.mkv": 42000 },
              "watched": [ "/v/b.mkv" ],
              "neuralMotion": { "DAIN": "GPU|480=6.5" },
              "aspects": { "/v/dvd.mkv": "SIXTEEN_NINE" },
              "screens": { "projector": { "shape": "LENS_133", "mirror": true } },
              "subtitleDelays": { "/v/a.mkv": 1500 },
              "colourSystems": { "/v/tape.mkv": "PAL" },
              "logos": { "/v/News": { "corner": "TOP_RIGHT", "size": 0.2, "mode": "DIM" } },
              "folderPictures": {
                "|/home/me/Videos/VHS": { "noise": -0.5 },
                "nas|Films": { "brightness": -0.1 }
              },
              "softwareDecoding": true,
              "somethingNew": 1
            }
            """.trimIndent(),
        )
        val store = DesktopStore(data)
        assertEquals("/home/me/Videos", store.libraryFolder)
        assertEquals(listOf(nas), store.sources)
        assertEquals(0.25f, store.picture.brightness)
        assertEquals(PictureAdjustment.Deinterlace.BWDIF, store.picture.deinterlace)
        assertEquals(PictureAdjustment.Colorize.DDCOLOR, store.picture.colorize)
        assertFalse(store.picture.cropBlackBars)
        assertEquals("de", store.subtitleLanguage)
        assertEquals(1.5f, store.speed)
        assertEquals(SoundSettings(loudnessDb = -4f, evenLoudness = true, audioDelayMs = -150), store.sound)
        assertEquals("1.0,2.0,3.0,4.0,true", store.window)
        assertEquals(42_000L, store.resumeMs("/v/a.mkv"))
        assertTrue(store.watched("/v/b.mkv"))
        assertEquals("GPU", store.motionPassed(PictureAdjustment.Interpolation.DAIN)?.unit)
        assertEquals(AspectOverride.SIXTEEN_NINE, store.pictureFor("/v/dvd.mkv").aspect)
        assertEquals(ScreenShape.LENS_133, store.screenSetup("projector").shape)
        assertTrue(store.screenSetup("projector").mirror)
        assertEquals(1_500, store.subtitleDelayMs("/v/a.mkv"))
        assertEquals(PictureAdjustment.ColourSystem.PAL, store.pictureFor("/v/tape.mkv").colourSystem)
        assertEquals(LogoMask(LogoMask.Corner.TOP_RIGHT, 0.2f, LogoMask.Mode.DIM), store.logoFor("/v/News/1.mkv"))
        assertTrue(store.softwareDecoding)
        // This computer's folder now lies under the media folder, as on Android; a share's stays as it was.
        val vhs = com.hawkynt.mediadroyd.library.FolderPlace(com.hawkynt.mediadroyd.state.PRIMARY_SOURCE, "VHS")
        assertEquals(vhs, store.pictureFolderOf("/home/me/Videos/VHS/1990/tape.mkv"))
        assertEquals(-0.5f, store.pictureFor("/home/me/Videos/VHS/1990/tape.mkv").noise)
        assertEquals(-0.1f, store.pictureFor("smb://nas/media/Films/x.mkv").brightness)
        // Moved aside, and the model's file is what is read from now on — the very model the Android app keeps.
        assertFalse(File(data, "settings.json").exists())
        assertTrue(File(data, "settings.json.old").exists())
        val kept = com.hawkynt.mediadroyd.state.StateStore.decode(File(data, "state.json").readText())!!
        assertEquals("/home/me/Videos", kept.rootPath)
        assertEquals(1.5f, kept.settings.defaultSpeed)
        assertEquals(42_000L, kept.resume["/v/a.mkv"]?.positionMs)
        assertEquals(1.5f, DesktopStore(data).speed)
    }

    @Test
    fun `a resume point keeps how long the video runs, for the walls' progress bars`() {
        val data = folder.newFolder("data")
        DesktopStore(data).setResume("/v/a.mkv", 30_000, 120_000)
        val kept = com.hawkynt.mediadroyd.state.StateStore.decode(File(data, "state.json").readText())!!
        assertEquals(com.hawkynt.mediadroyd.state.ResumeInfo(30_000, 120_000), kept.resume["/v/a.mkv"])
        assertEquals(30_000L, DesktopStore(data).resumeMs("/v/a.mkv"))
    }

    @Test
    fun `the media folder and the sources beside it are one list, the media folder first, as on Android`() {
        val data = folder.newFolder("data")
        val store = DesktopStore(data).apply {
            sources = listOf(nas)
            libraryFolder = "/home/me/Videos"
        }
        assertEquals(listOf(nas), store.sources)
        assertEquals(listOf(com.hawkynt.mediadroyd.state.PRIMARY_SOURCE, "nas"), store.allSources.map { it.id })
        assertEquals("/home/me/Videos", store.allSources.first().path)
        store.libraryFolder = "/media/films"
        assertEquals("/media/films", DesktopStore(data).allSources.first().path)
        store.sources = emptyList()
        assertEquals(listOf(com.hawkynt.mediadroyd.state.PRIMARY_SOURCE), DesktopStore(data).allSources.map { it.id })
    }

    @Test
    fun `turning subtitles off keeps the language for the household's next pick, as Android's setting does`() {
        val data = folder.newFolder("data")
        DesktopStore(data).subtitleLanguage = "de"
        DesktopStore(data).subtitleLanguage = ""
        val again = DesktopStore(data)
        assertEquals("", again.subtitleLanguage)
        assertFalse(again.state.data.settings.subtitlesOn)
        assertEquals("de", again.state.data.settings.subtitleLanguage)
    }
}
