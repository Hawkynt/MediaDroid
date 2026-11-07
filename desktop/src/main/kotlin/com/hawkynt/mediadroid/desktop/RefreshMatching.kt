package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.player.PlaybackMath

/** A monitor's display mode: its size in pixels, its refresh rate, and how the system names that rate ([rate]). */
data class DisplayMode(val width: Int, val height: Int, val refreshHz: Float, val rate: String)

/** A monitor as the system knows it: its [name], where it sits on the desktop, its mode now and every one it offers. */
data class DisplayOutput(
    val name: String,
    val x: Int,
    val y: Int,
    val current: DisplayMode,
    val modes: List<DisplayMode>,
)

/** The system's display modes: what each monitor offers, and a monitor switched to another of its modes. */
interface DisplaySystem {
    /** Why this system's modes cannot be switched by the app (shown in Settings); null where they can. */
    val unavailable: String?

    /** The monitors attached now, with their modes. */
    fun outputs(): List<DisplayOutput>

    /** [output] switched to [mode]; false where the system refused. */
    fun switch(output: String, mode: DisplayMode): Boolean

    companion object {
        /** This computer's: Windows' own (ChangeDisplaySettingsEx), X11's XRandR; none under Wayland. */
        fun forThisSystem(): DisplaySystem = when {
            System.getProperty("os.name").startsWith("Windows") -> WindowsDisplays()
            else -> XrandrDisplays.forThisSession(System.getenv())
        }
    }
}

/** A system whose modes stay as they are, and why. */
class FixedDisplays(override val unavailable: String) : DisplaySystem {
    override fun outputs(): List<DisplayOutput> = emptyList()

    override fun switch(output: String, mode: DisplayMode): Boolean = false
}

/**
 * Settings → Match the refresh rate on this computer, as the phone and Kodi do it: the monitor switched to a mode of
 * its size whose refresh is a whole multiple of the film's rate at its speed (PlaybackMath.evenPlayback) — 24 Hz or
 * 48 Hz for a 23.976 film, 50 Hz for 25, and so on — the mode it had kept to be put back ([restore]) when playback
 * ends, the app closes, or the program is stopped (a shutdown hook, [restoreAtExit]).
 */
class RefreshMatcher(private val system: DisplaySystem) {
    /** Each monitor switched, with the mode it had before the first switch. */
    private val original = LinkedHashMap<String, DisplayMode>()

    /** The monitors attached now, with their modes. */
    fun outputs(): List<DisplayOutput> = system.outputs()

    /** Whether a monitor is switched from its own mode now. */
    val switched: Boolean @Synchronized get() = original.isNotEmpty()

    /**
     * The plan for a [frameRate] film at [speed] on [output]: switched to the mode it names where that is another
     * one, else at its mode with the speed factor Even frames reaches ([maxChange], [direction]).
     */
    @Synchronized
    fun match(
        output: DisplayOutput,
        frameRate: Float,
        speed: Float,
        maxChange: Float,
        direction: PlaybackMath.JudderDirection,
    ): PlaybackMath.EvenPlan {
        val current = output.current
        val sameSize = output.modes.filter { it.width == current.width && it.height == current.height }
        val plan = PlaybackMath.evenPlayback(
            frameRate, speed, sameSize.map { it.refreshHz }, current.refreshHz, canSwitch = true,
            maxChange = maxChange, direction = direction,
        )
        if (plan.refreshHz == current.refreshHz) return plan
        val mode = sameSize.firstOrNull { it.refreshHz == plan.refreshHz } ?: return plan
        val before = original[output.name] ?: current
        if (!system.switch(output.name, mode)) {
            // Refused: played at the mode it has, as Even frames plays without switching.
            return PlaybackMath.evenPlayback(
                frameRate, speed, emptyList(), current.refreshHz, canSwitch = false,
                maxChange = maxChange, direction = direction,
            )
        }
        original[output.name] = before
        return plan
    }

    /** Every monitor switched put back to the mode it had; nothing to do where none was. */
    @Synchronized
    fun restore() {
        for ((name, mode) in original) runCatching { system.switch(name, mode) }
        original.clear()
    }

    /** [restore] also when the program is stopped without closing its window (a signal, the system shutting down). */
    fun restoreAtExit(): RefreshMatcher = apply {
        Runtime.getRuntime().addShutdownHook(Thread({ restore() }, "mediadroid-display-mode"))
    }

    companion object {
        /**
         * The output among [outputs] a window whose screen starts at [x], [y] (in the system's pixels, or scaled by
         * [scale]) is on: the one starting there, else the only one, else the first.
         */
        fun outputAt(outputs: List<DisplayOutput>, x: Int, y: Int, scale: Double = 1.0): DisplayOutput? =
            outputs.firstOrNull { it.x == x && it.y == y }
                ?: outputs.firstOrNull { it.x == (x * scale).toInt() && it.y == (y * scale).toInt() }
                ?: outputs.firstOrNull()
    }
}
