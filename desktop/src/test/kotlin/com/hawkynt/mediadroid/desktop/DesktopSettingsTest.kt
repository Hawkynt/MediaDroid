package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.player.MpvShaders
import com.hawkynt.mediadroid.ui.platform.PlatformSetting
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
            setOf(PlatformSetting.SHADER_FOLDER, PlatformSetting.BINGE_WATCHING, PlatformSetting.SKIP_INTRO_CREDITS),
            DesktopSettingsPlatform.OFFERED,
        )
        // Not acted on here yet: a switch for them would change nothing.
        listOf(
            PlatformSetting.MATCH_REFRESH_RATE,
            PlatformSetting.AUDIO_PASSTHROUGH,
            PlatformSetting.DECODED_BUFFER,
            PlatformSetting.AUDIO_LANGUAGE,
        ).forEach { assertFalse(it.name, it in DesktopSettingsPlatform.OFFERED) }
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
