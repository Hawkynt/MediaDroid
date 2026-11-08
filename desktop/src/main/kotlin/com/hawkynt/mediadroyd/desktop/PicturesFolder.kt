package com.hawkynt.mediadroyd.desktop

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File

/** Where a snapshot of the picture goes: a MediaDroyd folder in the user's own Pictures folder. */
object PicturesFolder {

    /** FOLDERID_Pictures, from the Windows SDK's KnownFolders.h. */
    const val FOLDERID_PICTURES = "33e28130-4e1e-4676-835a-98395c3bc3bb"

    /**
     * The folder for [osName] with [env], the user's [home] and the text of xdg-user-dirs' `user-dirs.dirs`
     * ([userDirs], null where there is none). On Windows the Pictures known folder ([knownPictures], wherever the user
     * or OneDrive moved it), else `%USERPROFILE%\Pictures`; elsewhere the `XDG_PICTURES_DIR` it names (`$HOME/…` or
     * absolute; `$HOME` alone means switched off), else `~/Pictures` — each with `MediaDroyd` inside.
     */
    fun of(
        osName: String,
        env: Map<String, String>,
        home: String,
        userDirs: String?,
        knownPictures: String? = null,
    ): File {
        if (osName.startsWith("Windows")) {
            val profiles = File(env["USERPROFILE"] ?: home, "Pictures").path
            return File(knownPictures?.takeIf { it.isNotEmpty() } ?: profiles, "MediaDroyd")
        }
        val named = userDirs?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("XDG_PICTURES_DIR=") }
            ?.substringAfter('=')?.trim()?.removeSurrounding("\"")
            ?.let { if (it.startsWith("\$HOME")) home + it.removePrefix("\$HOME") else it }
            ?.trimEnd('/')
            ?.takeIf { it.startsWith("/") && it != home.trimEnd('/') }
        return File(named ?: "$home/Pictures", "MediaDroyd")
    }

    /** Where xdg-user-dirs keeps its list: `$XDG_CONFIG_HOME/user-dirs.dirs`, `~/.config` without it. */
    fun userDirsFile(env: Map<String, String>, home: String): File =
        File(env["XDG_CONFIG_HOME"] ?: "$home/.config", "user-dirs.dirs")

    /**
     * Windows' own answer for the Pictures folder — SHGetKnownFolderPath(FOLDERID_Pictures) — or null where there is
     * none (not Windows, or the call failed).
     */
    fun knownPictures(): String? = runCatching {
        val shell32 = NativeLibrary.getInstance("shell32")
        val ole32 = NativeLibrary.getInstance("ole32")
        val id = Memory(16).apply { write(0, Smtc.guidBytes(FOLDERID_PICTURES), 0, 16) }
        val out = PointerByReference()
        val result = shell32.getFunction("SHGetKnownFolderPath").invokeInt(arrayOf(id, 0, Pointer.NULL, out))
        val path = out.value
        // The string is the caller's to free with CoTaskMemFree, whether the call succeeded or not.
        try {
            if (result == 0 && path != null) path.getWideString(0) else null
        } finally {
            if (path != null) ole32.getFunction("CoTaskMemFree").invokeVoid(arrayOf(path))
        }
    }.getOrNull()

    /** This machine's folder, created if need be. */
    fun current(): File {
        val env = System.getenv()
        val home = System.getProperty("user.home")
        val osName = System.getProperty("os.name")
        val userDirs = runCatching { userDirsFile(env, home).readText() }.getOrNull()
        val known = if (osName.startsWith("Windows")) knownPictures() else null
        return of(osName, env, home, userDirs, known).apply { mkdirs() }
    }
}
