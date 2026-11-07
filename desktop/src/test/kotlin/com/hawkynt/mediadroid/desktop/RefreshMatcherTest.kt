package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.player.PlaybackMath
import com.hawkynt.mediadroid.state.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

// Settings → Match the refresh rate on the desktop: the monitor switched to the film's rate family and put back.
class RefreshMatcherTest {

    private fun mode(hz: Float) = DisplayMode(1920, 1080, hz, "%.2f".format(java.util.Locale.ROOT, hz))

    /** A monitor offering 1080p at the usual rates (and 720p at 60), now at [current] Hz; what it was told, kept. */
    private class FakeDisplays(current: Float, private val refuses: Boolean = false) : DisplaySystem {
        override val unavailable: String? = null
        val switches = ArrayList<Float>()
        var now = DisplayMode(1920, 1080, current, "%.2f".format(current))
        private val offered = listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)
            .map { DisplayMode(1920, 1080, it, "%.2f".format(it)) } + DisplayMode(1280, 720, 60f, "60.00")

        override fun outputs() = listOf(DisplayOutput("HDMI-1", 0, 0, now, offered))

        override fun switch(output: String, mode: DisplayMode): Boolean {
            if (refuses) return false
            switches += mode.refreshHz
            now = mode
            return true
        }
    }

    private fun RefreshMatcher.match(displays: FakeDisplays, frameRate: Float, speed: Float = 1f) =
        match(displays.outputs().single(), frameRate, speed, 0.05f, PlaybackMath.JudderDirection.NEAREST)

    @Test
    fun `a film is shown at its own rate family, at the monitor's size, and the mode it had comes back`() {
        val displays = FakeDisplays(60f)
        val matcher = RefreshMatcher(displays)
        val plan = matcher.match(displays, 23.976f)
        assertEquals(23.976f, plan.refreshHz)
        assertEquals(1f, plan.speedFactor)
        assertTrue(matcher.switched)
        // The next video, PAL: switched again; the monitor's own mode is still the one it had before the first.
        matcher.match(displays, 25f)
        assertEquals(listOf(23.976f, 25f), displays.switches)
        matcher.restore()
        assertEquals(60f, displays.now.refreshHz)
        assertFalse(matcher.switched)
        // Put back once: nothing more to do.
        matcher.restore()
        assertEquals(3, displays.switches.size)
    }

    @Test
    fun `a monitor already at a multiple of the film's rate keeps its mode`() {
        val displays = FakeDisplays(50f)
        val matcher = RefreshMatcher(displays)
        assertEquals(50f, matcher.match(displays, 25f).refreshHz)
        assertTrue(displays.switches.isEmpty())
        assertFalse(matcher.switched)
    }

    @Test
    fun `refused, the film plays at the mode the monitor has, Even frames' way`() {
        val displays = FakeDisplays(60f, refuses = true)
        val matcher = RefreshMatcher(displays)
        val plan = matcher.match(displays, 29.97f)
        assertEquals(60f, plan.refreshHz)
        assertEquals(30f / 29.97f, plan.speedFactor, 1e-4f)
        assertFalse(matcher.switched)
    }

    @Test
    fun `Even frames switches the monitor where matching is on and back when it is switched off`() {
        val displays = FakeDisplays(60f)
        val matcher = RefreshMatcher(displays)
        val settings = Settings(matchRefreshRate = true, judderLimitPercent = 5)
        val even = EvenFrames(settings, matcher)
        even.plan(23.976f, 1f, 1_000_000_000L / 60) { displays.outputs().single() }
        assertEquals(1f, even.speedFactor)
        assertEquals(23.976f, displays.now.refreshHz)
        settings.matchRefreshRate = false
        even.plan(23.976f, 1f, 1_000_000_000L / 24) { displays.outputs().single() }
        assertEquals(60f, displays.now.refreshHz)
    }

    @Test
    fun `the window's monitor is the one starting where its screen does`() {
        val left = DisplayOutput("DP-1", 0, 0, mode(60f), emptyList())
        val right = DisplayOutput("HDMI-1", 1920, 0, mode(60f), emptyList())
        assertEquals(right, RefreshMatcher.outputAt(listOf(left, right), 1920, 0))
        assertEquals(right, RefreshMatcher.outputAt(listOf(left, right), 1280, 0, scale = 1.5))
        assertEquals(left, RefreshMatcher.outputAt(listOf(left), 5, 5))
        assertNull(RefreshMatcher.outputAt(emptyList(), 0, 0))
    }

    @Test
    fun `xrandr's listing gives each connected output, its place, its mode now and every rate`() {
        val text = """
            Screen 0: minimum 8 x 8, current 3840 x 1080, maximum 32767 x 32767
            HDMI-1 connected primary 1920x1080+0+0 (normal left inverted right x axis y axis) 527mm x 296mm
               1920x1080     60.00*+  50.00    59.94    30.00    24.00    23.98
               1920x1080i    60.00    50.00
               1280x720      60.00    50.00    59.94
            DP-1 connected 1920x1080+1920+0 (normal left inverted right x axis y axis) 527mm x 296mm
               1920x1080     59.94*
            DP-2 disconnected (normal left inverted right x axis y axis)
        """.trimIndent()
        val outputs = XrandrDisplays.parse(text)
        assertEquals(listOf("HDMI-1", "DP-1"), outputs.map { it.name })
        val hdmi = outputs[0]
        assertEquals(DisplayMode(1920, 1080, 60f, "60.00"), hdmi.current)
        assertEquals(
            listOf(60f, 50f, 59.94f, 30f, 24f, 23.98f, 60f, 50f, 59.94f),
            hdmi.modes.map { it.refreshHz },
        )
        assertEquals("23.98", hdmi.modes[5].rate)
        assertEquals(1920 to 0, outputs[1].x to outputs[1].y)
        assertEquals(59.94f, outputs[1].current.refreshHz)
    }

    @Test
    fun `under Wayland, or without X or xrandr, the modes stay as they are and Settings says why`() {
        val wayland = XrandrDisplays.forThisSession(mapOf("XDG_SESSION_TYPE" to "wayland", "DISPLAY" to ":0"))
        assertNotNull(wayland.unavailable)
        assertTrue(wayland.outputs().isEmpty())
        assertNotNull(XrandrDisplays.forThisSession(emptyMap()).unavailable)
        assertNotNull(XrandrDisplays.forThisSession(mapOf("DISPLAY" to ":0", "PATH" to "/nowhere")).unavailable)
    }

    @Test
    fun `Windows' whole hertz one below a multiple of 24 or 30 are the NTSC rates`() {
        assertEquals(24000f / 1001f, WindowsDisplays.refreshOf(23), 1e-4f)
        assertEquals(30000f / 1001f, WindowsDisplays.refreshOf(29), 1e-4f)
        assertEquals(60000f / 1001f, WindowsDisplays.refreshOf(59), 1e-4f)
        assertEquals(120000f / 1001f, WindowsDisplays.refreshOf(119), 1e-4f)
        listOf(24, 25, 30, 50, 60, 75, 120, 144).forEach { assertEquals(it.toFloat(), WindowsDisplays.refreshOf(it)) }
    }

    @Test
    fun `on X11 the outputs are read and one can be set to the mode it has`() {
        val path = System.getenv("PATH").orEmpty()
        assumeTrue(
            !System.getenv("DISPLAY").isNullOrEmpty() &&
                path.split(File.pathSeparator).any { File(it, "xrandr").canExecute() } &&
                !System.getProperty("os.name").startsWith("Windows"),
        )
        val displays = XrandrDisplays.forThisSession(mapOf("DISPLAY" to System.getenv("DISPLAY"), "PATH" to path))
        val output = displays.outputs().firstOrNull()
        assertNotNull(output)
        assertTrue(displays.switch(output!!.name, output.current))
    }

    @Test
    fun `on Windows every monitor attached is listed with its mode among those it offers`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val displays = WindowsDisplays()
        assertNull(displays.unavailable)
        val outputs = displays.outputs()
        assumeTrue(outputs.isNotEmpty())
        outputs.forEach { output ->
            assertTrue(output.current.width > 0 && output.current.height > 0 && output.current.refreshHz > 0f)
            assertTrue(output.name, output.current in output.modes)
        }
    }
}
