package com.hawkynt.mediadroyd.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * A D-Bus session bus of the test's own, from `dbus-daemon`, so the tests never touch the desktop's; null where
 * dbus-daemon is not installed (Windows).
 */
class PrivateBus private constructor(
    private val daemon: Process,
    private val folder: File,
    val address: String,
) : AutoCloseable {

    override fun close() {
        daemon.destroy()
        daemon.waitFor(2, TimeUnit.SECONDS)
        folder.deleteRecursively()
    }

    companion object {
        fun start(): PrivateBus? {
            if (System.getProperty("os.name").startsWith("Windows")) return null
            val binary = listOf("/usr/bin/dbus-daemon", "/bin/dbus-daemon").firstOrNull { File(it).canExecute() }
                ?: return null
            // Its socket a file in a folder of its own: the session configuration's `unix:tmpdir` gives an abstract
            // socket on older dbus (Ubuntu 22.04's), which the JDK's Unix sockets dbus-java connects with cannot reach.
            val folder = Files.createTempDirectory("mediadroyd-bus").toFile()
            val daemon = ProcessBuilder(
                binary, "--session", "--nofork", "--print-address=1", "--address=unix:dir=${folder.path}",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val address = daemon.inputStream.bufferedReader().readLine()?.trim()
            if (address.isNullOrEmpty()) {
                daemon.destroy()
                folder.deleteRecursively()
                return null
            }
            return PrivateBus(daemon, folder, address)
        }
    }
}
