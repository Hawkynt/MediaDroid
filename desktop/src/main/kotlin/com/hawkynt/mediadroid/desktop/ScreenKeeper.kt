package com.hawkynt.mediadroid.desktop

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
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
        /**
         * The way this system offers: on Linux logind's idle lock and the desktop's own screensaver
         * (org.freedesktop.ScreenSaver), on Windows the thread's execution state.
         */
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
     * logind before blanking honour it. Beside it the desktop's screensaver is asked directly over the session bus
     * (org.freedesktop.ScreenSaver: KDE, GNOME, Xfce, Cinnamon, xdg-desktop-portal setups), for those that do not.
     */
    private class LinuxKeeper : ScreenKeeper {
        private val screensaver = ScreenSaverInhibit { SessionBus.connect() }

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
            worker.execute {
                apply(false)
                screensaver.close()
            }
            worker.shutdown()
            worker.awaitTermination(2, TimeUnit.SECONDS)
        }

        @Synchronized
        private fun apply(on: Boolean) {
            screensaver.hold(on)
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

/** The freedesktop screensaver's D-Bus interface (specifications.freedesktop.org/idle-inhibit-spec). */
@DBusInterfaceName("org.freedesktop.ScreenSaver")
@Suppress("FunctionName")
interface FreedesktopScreenSaver : DBusInterface {
    /** Holds the screensaver off for [application], saying [reason]; the cookie lets go again. */
    fun Inhibit(application: String, reason: String): UInt32

    fun UnInhibit(cookie: UInt32)
}

/**
 * Holds the desktop's screensaver off through org.freedesktop.ScreenSaver on the session bus [connect] reaches (made
 * when first needed). The hold belongs to the connection, so it also ends when the app does. Where no screensaver
 * answers, nothing happens.
 */
internal class ScreenSaverInhibit(private val connect: () -> DBusConnection?) {
    private var bus: DBusConnection? = null
    private var tried = false
    private var cookie: UInt32? = null

    @Synchronized
    fun hold(on: Boolean) {
        if (on == (cookie != null)) return
        val screensaver = screensaver() ?: return
        cookie = if (on) {
            runCatching { screensaver.Inhibit("MediaDroid", "Playing a video") }.getOrNull()
        } else {
            cookie?.let { runCatching { screensaver.UnInhibit(it) } }
            null
        }
    }

    /** Lets go and closes the connection; holding again connects afresh. */
    @Synchronized
    fun close() {
        hold(false)
        bus?.let { runCatching { it.close() } }
        bus = null
        tried = false
    }

    private fun screensaver(): FreedesktopScreenSaver? {
        if (!tried) {
            tried = true
            bus = connect()
        }
        return bus?.let {
            runCatching { it.getRemoteObject(NAME, PATH, FreedesktopScreenSaver::class.java, true) }.getOrNull()
        }
    }

    private companion object {
        const val NAME = "org.freedesktop.ScreenSaver"
        const val PATH = "/org/freedesktop/ScreenSaver"
    }
}
