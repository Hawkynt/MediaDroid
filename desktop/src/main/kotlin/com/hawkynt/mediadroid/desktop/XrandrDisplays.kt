package com.hawkynt.mediadroid.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * X11's display modes through XRandR, by its own command-line client (`xrandr`), which speaks the RandR protocol to
 * the X server: each connected output with its position, the mode it is in (`*`) and the rates every mode offers;
 * an output switched with `--output <name> --mode <w>x<h> --rate <rate>`, the rate as `xrandr` names it.
 */
class XrandrDisplays(private val run: (List<String>) -> String?) : DisplaySystem {
    override val unavailable: String? = null

    override fun outputs(): List<DisplayOutput> = run(listOf("xrandr", "--query"))?.let(::parse).orEmpty()

    override fun switch(output: String, mode: DisplayMode): Boolean =
        run(listOf("xrandr", "--output", output, "--mode", "${mode.width}x${mode.height}", "--rate", mode.rate)) != null

    companion object {
        /**
         * X11's, where the app's session is X11 and `xrandr` is installed; under Wayland none — its compositors keep
         * the outputs' modes to themselves (XWayland's are only pretended), and there is no protocol every
         * compositor offers an app for it.
         */
        fun forThisSession(env: Map<String, String>): DisplaySystem = when {
            env["XDG_SESSION_TYPE"] == "wayland" || !env["WAYLAND_DISPLAY"].isNullOrEmpty() ->
                FixedDisplays("Under Wayland the desktop keeps the screen's mode to itself.")
            env["DISPLAY"].isNullOrEmpty() -> FixedDisplays("There is no X display to switch.")
            !onPath("xrandr", env["PATH"].orEmpty()) -> FixedDisplays("Switching needs xrandr, which is not installed.")
            else -> XrandrDisplays(::runCommand)
        }

        private fun onPath(name: String, path: String) =
            path.split(File.pathSeparator).any { File(it, name).canExecute() }

        /** [command]'s output, or null where it failed or took too long. */
        private fun runCommand(command: List<String>): String? = runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) null else text
        }.getOrNull()

        private val OUTPUT = Regex("""^(\S+) connected(?: primary)? (\d+)x(\d+)\+(-?\d+)\+(-?\d+)""")
        private val MODE = Regex("""^\s+(\d+)x(\d+)i?\s+(.*)$""")
        private val RATE = Regex("""(\d+(?:\.\d+)?)([ *+]*)""")

        /** The connected outputs `xrandr --query` lists in [text], each with every mode and rate it offers. */
        fun parse(text: String): List<DisplayOutput> {
            val outputs = ArrayList<DisplayOutput>()
            var name: String? = null
            var x = 0
            var y = 0
            var modes = ArrayList<DisplayMode>()
            var current: DisplayMode? = null
            fun close() {
                val shown = current
                if (name != null && shown != null) outputs += DisplayOutput(name!!, x, y, shown, modes)
                name = null
                current = null
                modes = ArrayList()
            }
            for (line in text.lines()) {
                val output = OUTPUT.find(line)
                if (output != null) {
                    close()
                    name = output.groupValues[1]
                    x = output.groupValues[4].toInt()
                    y = output.groupValues[5].toInt()
                    continue
                }
                if (!line.startsWith(" ")) {
                    close()
                    continue
                }
                if (name == null) continue
                val mode = MODE.find(line) ?: continue
                // Interlaced modes are no use for a film's rate.
                if (line.trimStart().substringBefore(' ').endsWith("i")) continue
                val width = mode.groupValues[1].toInt()
                val height = mode.groupValues[2].toInt()
                for (rate in RATE.findAll(mode.groupValues[3])) {
                    val shown = DisplayMode(width, height, rate.groupValues[1].toFloat(), rate.groupValues[1])
                    modes += shown
                    if ('*' in rate.groupValues[2]) current = shown
                }
            }
            close()
            return outputs
        }
    }
}
