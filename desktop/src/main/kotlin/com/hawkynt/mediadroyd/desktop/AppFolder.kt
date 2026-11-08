package com.hawkynt.mediadroyd.desktop

import java.io.File

/** Where the desktop app keeps its settings, watched marks and resume points. */
object AppFolder {

    /**
     * The folder for [osName] with [env] and the user's [home]: beside the program in a portable unpack ([portable],
     * the folder holding a `portable` marker — its `data` folder), else `%APPDATA%\MediaDroyd` on Windows and
     * `$XDG_DATA_HOME/MediaDroyd` (`~/.local/share/MediaDroyd` without it) elsewhere.
     */
    fun of(osName: String, env: Map<String, String>, home: String, portable: File?): File = when {
        portable != null -> File(portable, "data")
        osName.startsWith("Windows") -> File(env["APPDATA"] ?: "$home\\AppData\\Roaming", "MediaDroyd")
        else -> File(env["XDG_DATA_HOME"] ?: "$home/.local/share", "MediaDroyd")
    }

    /**
     * The folder the program was unpacked into: two up from its [resources] folder (`…/lib/resources`, or
     * `…/app/resources` in a jpackage image) — made absolute first, as a launcher may hand it over relative.
     */
    fun programOf(resources: String?): File? = resources?.let { File(it).absoluteFile.parentFile?.parentFile }

    /** This machine's folder, created if need be. */
    fun current(): File {
        val program = programOf(System.getProperty("compose.application.resources.dir"))
        val portable = program?.takeIf { File(it, "portable").exists() }
        return of(System.getProperty("os.name"), System.getenv(), System.getProperty("user.home"), portable)
            .apply { if (portable == null) movedFromOld(this) }
            .apply { mkdirs() }
    }

    /**
     * The app was called MediaDroid before (#471): where [folder] does not exist yet and the folder of that name beside
     * it does, that one is renamed to it — settings, watched marks and resume points kept.
     */
    fun movedFromOld(folder: File) {
        val old = File(folder.parentFile ?: return, OLD_NAME)
        if (!folder.exists() && old.isDirectory) old.renameTo(folder)
    }

    // The app's name before the rename, for its folder.
    private const val OLD_NAME = "MediaDroid"
}
