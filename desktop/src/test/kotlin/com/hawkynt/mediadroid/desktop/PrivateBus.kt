package com.hawkynt.mediadroid.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A D-Bus session bus of the test's own, from `dbus-daemon`, so the tests never touch the desktop's; null where
 * dbus-daemon is not installed (Windows).
 */
class PrivateBus private constructor(private val daemon: Process, val address: String) : AutoCloseable {

    override fun close() {
        daemon.destroy()
        daemon.waitFor(2, TimeUnit.SECONDS)
    }

    companion object {
        fun start(): PrivateBus? {
            if (System.getProperty("os.name").startsWith("Windows")) return null
            val binary = listOf("/usr/bin/dbus-daemon", "/bin/dbus-daemon").firstOrNull { File(it).canExecute() }
                ?: return null
            val daemon = ProcessBuilder(binary, "--session", "--nofork", "--print-address=1")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val address = daemon.inputStream.bufferedReader().readLine()?.trim()
            if (address.isNullOrEmpty()) {
                daemon.destroy()
                return null
            }
            return PrivateBus(daemon, address)
        }
    }
}
