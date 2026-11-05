package com.hawkynt.mediadroid.desktop

import java.io.File

/** Where a snapshot of the picture goes: a MediaDroid folder in the user's own Pictures folder. */
object PicturesFolder {

    /**
     * The folder for [osName] with [env], the user's [home] and the text of xdg-user-dirs' `user-dirs.dirs`
     * ([userDirs], null where there is none): `%USERPROFILE%\Pictures\MediaDroid` on Windows; elsewhere the
     * `XDG_PICTURES_DIR` it names (`$HOME/…` or absolute; `$HOME` alone means switched off), else `~/Pictures`.
     */
    fun of(osName: String, env: Map<String, String>, home: String, userDirs: String?): File {
        if (osName.startsWith("Windows")) return File(File(env["USERPROFILE"] ?: home, "Pictures"), "MediaDroid")
        val named = userDirs?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("XDG_PICTURES_DIR=") }
            ?.substringAfter('=')?.trim()?.removeSurrounding("\"")
            ?.let { if (it.startsWith("\$HOME")) home + it.removePrefix("\$HOME") else it }
            ?.trimEnd('/')
            ?.takeIf { it.startsWith("/") && it != home.trimEnd('/') }
        return File(named ?: "$home/Pictures", "MediaDroid")
    }

    /** Where xdg-user-dirs keeps its list: `$XDG_CONFIG_HOME/user-dirs.dirs`, `~/.config` without it. */
    fun userDirsFile(env: Map<String, String>, home: String): File =
        File(env["XDG_CONFIG_HOME"] ?: "$home/.config", "user-dirs.dirs")

    /** This machine's folder, created if need be. */
    fun current(): File {
        val env = System.getenv()
        val home = System.getProperty("user.home")
        val userDirs = runCatching { userDirsFile(env, home).readText() }.getOrNull()
        return of(System.getProperty("os.name"), env, home, userDirs).apply { mkdirs() }
    }
}
