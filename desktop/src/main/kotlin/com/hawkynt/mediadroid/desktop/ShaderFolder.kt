package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.player.MpvShaders
import java.io.File

/**
 * The folder of mpv user shaders (.glsl) the picture offers (MpvShaders): the one picked in Settings while it is
 * there, else `shaders/` in the app-data folder, made when first asked for.
 */
object ShaderFolder {
    /** The folder the shaders are read from: [picked] (the household's setting) where it is a folder, else [app]'s. */
    fun of(picked: String, app: File): File =
        picked.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isDirectory }
            ?: File(app, "shaders").apply { mkdirs() }

    /** Reads every file in [folder] into MpvShaders, by its name; returns how many shaders it offers then. */
    fun load(folder: File): Int {
        MpvShaders.load(folder.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readText() })
        return MpvShaders.choices.size
    }
}
