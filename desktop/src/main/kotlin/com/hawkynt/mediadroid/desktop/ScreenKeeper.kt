package com.hawkynt.mediadroid.desktop

import org.lwjgl.system.JNI
import org.lwjgl.system.Library
import org.lwjgl.system.SharedLibrary
import java.util.concurrent.TimeUnit

/**
 * Keeps the screen on while something plays: no screensaver, no blanking, no going to sleep. Where the system offers
 * no way, it does nothing.
 */
interface ScreenKeeper : AutoCloseable {

    /** [on]: hold the screen on from now; false: let the system do as it likes again. */
    fun keepOn(on: Boolean)

    override fun close() = keepOn(false)

    companion object {
        /** The way this system offers: logind's idle lock on Linux, the thread's execution state on Windows. */
        fun forThisSystem(): ScreenKeeper {
            val os = System.getProperty("os.name")
            return when {
                os.startsWith("Windows") -> runCatching { WindowsKeeper() }.getOrNull() ?: Nothing
                os.startsWith("Linux") -> LinuxKeeper()
                else -> Nothing
            }
        }
    }

    private object Nothing : ScreenKeeper {
        override fun keepOn(on: Boolean) = Unit
    }

    /**
     * An idle lock from systemd-logind, held by a `systemd-inhibit` child for as long as it runs; desktops that ask
     * logind before blanking (KDE, GNOME, most screensavers) honour it.
     */
    private class LinuxKeeper : ScreenKeeper {
        private companion object {
            /** A refused lock makes systemd-inhibit end at once; one still running after this was granted. */
            const val REFUSED_WITHIN_MS = 300L
        }

        @Volatile private var holder: Process? = null

        private fun hold(what: String): Process? = runCatching {
            ProcessBuilder(
                "systemd-inhibit", "--what=$what", "--who=MediaDroid", "--why=Playing a video", "--mode=block",
                "sleep", "infinity",
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull()

        // Taking a lock waits to see it was not refused: on a thread of its own, never the window's, in call order.
        private val worker = java.util.concurrent.Executors.newSingleThreadExecutor {
            Thread(it, "mediadroid-screen").apply { isDaemon = true }
        }

        // However the app ends, the lock ends with it.
        init {
            Runtime.getRuntime().addShutdownHook(Thread { apply(false) })
        }

        override fun keepOn(on: Boolean) {
            worker.execute { apply(on) }
        }

        override fun close() {
            worker.execute { apply(false) }
            worker.shutdown()
            worker.awaitTermination(2, TimeUnit.SECONDS)
        }

        @Synchronized
        private fun apply(on: Boolean) {
            if (on == (holder?.isAlive == true)) return
            if (on) {
                // Screen and sleep where the system allows both; some refuse the sleep lock to a user (a machine
                // without a seat), and there the screen alone is still worth holding.
                holder = hold("idle:sleep")?.takeIf { !it.waitFor(REFUSED_WITHIN_MS, TimeUnit.MILLISECONDS) }
                    ?: hold("idle")?.takeIf { !it.waitFor(REFUSED_WITHIN_MS, TimeUnit.MILLISECONDS) }
            } else {
                holder?.let { process ->
                    process.descendants().forEach { it.destroy() }
                    process.destroy()
                }
                holder = null
            }
        }
    }

    /**
     * SetThreadExecutionState from kernel32, called through LWJGL's native calls: display and system required while
     * playing. The state belongs to the calling thread, so it is set from the window's own thread, which lives as
     * long as the app.
     */
    private class WindowsKeeper : ScreenKeeper {
        private val kernel32: SharedLibrary = Library.loadNative(ScreenKeeper::class.java, "org.lwjgl", "kernel32")
        private val setState = kernel32.getFunctionAddress("SetThreadExecutionState").also { check(it != 0L) }

        override fun keepOn(on: Boolean) {
            JNI.invokeI(if (on) ES_CONTINUOUS or ES_DISPLAY_REQUIRED or ES_SYSTEM_REQUIRED else ES_CONTINUOUS, setState)
        }

        override fun close() {
            keepOn(false)
            kernel32.free()
        }

        private companion object {
            // winbase.h
            const val ES_CONTINUOUS = 0x80000000.toInt()
            const val ES_DISPLAY_REQUIRED = 0x00000002
            const val ES_SYSTEM_REQUIRED = 0x00000001
        }
    }
}
