package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import com.hawkynt.mediadroid.player.AspectOverride
import com.hawkynt.mediadroid.player.NeuralEnlarging
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.ScreenSetup
import com.hawkynt.mediadroid.player.ScreenShape
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
    fun `everything set is kept as JSON in settings_json and reads back in a new store`() {
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
        val json = Json.parseToJsonElement(File(data, "settings.json").readText()).jsonObject
        assertEquals("/home/me/Videos", json["library"]?.jsonPrimitive?.content)
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
        val passed = com.hawkynt.mediadroid.player.NeuralInterpolation.Passed("GPU", mapOf(256 to 120.0))
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
    fun `a network's passed self-test is kept, so it is not tested again`() {
        val data = folder.newFolder("data")
        val passed = com.hawkynt.mediadroid.player.NeuralInterpolation.Passed("GPU", mapOf(480 to 6.5, 720 to 14.0))
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
        val json = Json.parseToJsonElement(File(data, "settings.json").readText()).jsonObject
        assertEquals("{\"/v/a.mkv\":1500}", json["subtitleDelays"].toString())
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
        assertTrue(File(data, "settings.json").exists())
        assertFalse(File(data, "state.properties").exists())
        assertTrue(File(data, "state.properties.old").exists())
        assertEquals("/home/me/Videos", DesktopStore(data).libraryFolder)
    }

    @Test
    fun `a damaged settings file starts from the defaults and is kept aside, not lost`() {
        val data = folder.newFolder("data")
        File(data, "settings.json").writeText("{ not json")
        val store = DesktopStore(data)
        assertNull(store.libraryFolder)
        store.speed = 1.25f
        assertEquals("{ not json", File(data, "settings.json.damaged").readText())
        assertEquals(1.25f, DesktopStore(data).speed)
    }

    @Test
    fun `settings written by a later version with fields this one does not know still read`() {
        val data = folder.newFolder("data")
        File(data, "settings.json").writeText("""{"library":"/x","speed":0.75,"somethingNew":{"a":1}}""")
        val store = DesktopStore(data)
        assertEquals("/x", store.libraryFolder)
        assertEquals(0.75f, store.speed)
    }

    @Test
    fun `a video's shape and each screen's setup are kept, each for its own`() {
        val data = folder.newFolder("data")
        DesktopStore(data).apply {
            setAspect("/v/dvd.mkv", AspectOverride.SIXTEEN_NINE)
            setScreenSetup(
                "projector",
                ScreenSetup(ScreenShape.LENS_133, mirror = true, flip = true, keystoneVertical = 0.1f),
            )
        }
        val again = DesktopStore(data)
        assertEquals(AspectOverride.SIXTEEN_NINE, again.aspectOf("/v/dvd.mkv"))
        assertEquals(AspectOverride.AUTO, again.aspectOf("/v/other.mkv"))
        assertEquals(
            ScreenSetup(ScreenShape.LENS_133, mirror = true, flip = true, keystoneVertical = 0.1f),
            again.screenSetup("projector"),
        )
        assertEquals(ScreenSetup.Default, again.screenSetup("monitor"))
        // Back to the defaults: nothing is kept for them.
        again.setAspect("/v/dvd.mkv", AspectOverride.AUTO)
        again.setScreenSetup("projector", ScreenSetup.Default)
        val text = File(data, "settings.json").readText()
        assertFalse(text.contains("/v/dvd.mkv"))
        assertFalse(text.contains("projector"))
    }
}
