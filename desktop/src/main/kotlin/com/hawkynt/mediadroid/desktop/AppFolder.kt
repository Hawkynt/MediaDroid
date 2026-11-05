package com.hawkynt.mediadroid.desktop

import java.io.File

/** Where the desktop app keeps its settings, watched marks and resume points. */
object AppFolder {

    /**
     * The folder for [osName] with [env] and the user's [home]: beside the program in a portable unpack ([portable],
     * the folder holding a `portable` marker — its `data` folder), else `%APPDATA%\MediaDroid` on Windows and
     * `$XDG_DATA_HOME/MediaDroid` (`~/.local/share/MediaDroid` without it) elsewhere.
     */
    fun of(osName: String, env: Map<String, String>, home: String, portable: File?): File = when {
        portable != null -> File(portable, "data")
        osName.startsWith("Windows") -> File(env["APPDATA"] ?: "$home\\AppData\\Roaming", "MediaDroid")
        else -> File(env["XDG_DATA_HOME"] ?: "$home/.local/share", "MediaDroid")
    }

    /** This machine's folder, created if need be. */
    fun current(): File {
        val program = System.getProperty("compose.application.resources.dir")?.let { File(it).parentFile?.parentFile }
        val portable = program?.takeIf { File(it, "portable").exists() }
        return of(System.getProperty("os.name"), System.getenv(), System.getProperty("user.home"), portable)
            .apply { mkdirs() }
    }
}
