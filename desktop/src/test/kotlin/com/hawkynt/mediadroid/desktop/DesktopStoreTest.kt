package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import com.hawkynt.mediadroid.player.PictureAdjustment
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
        assertEquals("10.0,20.0,800.0,600.0,false", again.window)
        assertEquals(listOf(nas), again.sources)
        assertEquals(42_000L, again.resumeMs("/v/a.mkv"))
        assertTrue(again.watched("/v/b.mkv"))
        assertFalse(again.watched("/v/a.mkv"))
    }

    @Test
    fun `a fresh store has the defaults, and watching a video forgets where it was left`() {
        val store = DesktopStore(folder.newFolder("data"))
        assertNull(store.libraryFolder)
        // Black bars are cropped unless the household says otherwise.
        assertEquals(PictureAdjustment.Neutral.copy(cropBlackBars = true), store.picture)
        assertEquals("", store.subtitleLanguage)
        assertEquals(1f, store.speed)
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
}
