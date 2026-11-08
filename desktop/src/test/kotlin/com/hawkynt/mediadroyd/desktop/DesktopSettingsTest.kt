package com.hawkynt.mediadroyd.desktop

import com.hawkynt.mediadroyd.player.MpvShaders
import com.hawkynt.mediadroyd.ui.platform.PlatformSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.GraphicsEnvironment
import java.io.File

class DesktopSettingsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `Settings shows only the platform rows the desktop player acts on`() {
        assertEquals(
            setOf(
                PlatformSetting.SHADER_FOLDER,
                PlatformSetting.BINGE_WATCHING,
                PlatformSetting.SKIP_INTRO_CREDITS,
                PlatformSetting.EVEN_FRAMES,
                PlatformSetting.SUBTITLE_LOOK,
                PlatformSetting.AUDIO_LANGUAGE,
            ),
            DesktopSettingsPlatform.OFFERED,
        )
        // Nothing on a desktop for them to change (DesktopSettingsPlatform says why).
        listOf(
            PlatformSetting.AUDIO_PASSTHROUGH,
            PlatformSetting.HDR_TONE_MAPPING,
            PlatformSetting.LARGE_VIDEO,
            PlatformSetting.DECODED_BUFFER,
        ).forEach { assertFalse(it.name, it in DesktopSettingsPlatform.OFFERED) }
    }

    @Test
    fun `matching the refresh rate shows where the system lets the monitor's mode be switched`() {
        val store = DesktopStore(temp.newFolder("data"))
        val switching = object : DisplaySystem {
            override val unavailable: String? = null
            override fun outputs() = emptyList<DisplayOutput>()
            override fun switch(output: String, mode: DisplayMode) = false
        }
        assertTrue(PlatformSetting.MATCH_REFRESH_RATE in DesktopSettingsPlatform(store, temp.root, switching).offered)
        val wayland = FixedDisplays("Under Wayland the desktop keeps the screen's mode to itself.")
        assertFalse(PlatformSetting.MATCH_REFRESH_RATE in DesktopSettingsPlatform(store, temp.root, wayland).offered)
    }

    @Test
    fun `the shaders come from the folder picked while it is there, else the app-data folder's shaders`() {
        val app = temp.newFolder("app")
        val picked = temp.newFolder("picked")
        assertEquals(picked, ShaderFolder.of(picked.path, app))
        assertEquals(File(app, "shaders"), ShaderFolder.of("", app))
        assertTrue(File(app, "shaders").isDirectory)
        assertEquals(File(app, "shaders"), ShaderFolder.of(File(temp.root, "gone").path, app))
    }

    @Test
    fun `loading a shader folder offers its shaders`() {
        val folder = temp.newFolder("shaders")
        assertEquals(0, ShaderFolder.load(folder))
        File(folder, "sharpen.glsl").writeText(
            """
            //!HOOK LUMA
            //!BIND HOOKED
            //!DESC sharpen
            vec4 hook() { return HOOKED_tex(HOOKED_pos); }
            """.trimIndent(),
        )
        assertEquals(1, ShaderFolder.load(folder))
        assertEquals(listOf("sharpen.glsl"), MpvShaders.choices)
        ShaderFolder.load(temp.newFolder("empty"))
    }

    @Test
    fun `every monitor is offered once under the key the player keeps its setup by`() {
        val screens = OutputScreen.all()
        if (GraphicsEnvironment.isHeadless()) {
            assertEquals(listOf(OutputScreen.Unknown), screens)
        } else {
            assertTrue(screens.isNotEmpty())
            assertEquals(screens.size, screens.map { it.key }.toSet().size)
        }
    }
}
