package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.engine.desktop.MediaReader
import com.hawkynt.mediadroid.player.Snapshot
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** A snapshot of the picture on screen — as the stage drew it, filters and all — saved as a PNG. */
object FrameSnapshot {

    /**
     * [picture] as `Title HH-MM-SS.png` (its own position) in [folder]; a name already there gets the next free
     * number. Returns the file written.
     */
    fun save(picture: MediaReader.Picture, title: String, folder: File): File {
        val info = ImageInfo(picture.width, picture.height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
        val png = Image.makeRaster(info, picture.rgba, picture.width * 4).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("the picture could not be encoded")
        }
        folder.mkdirs()
        // Created only where nothing is yet: two snapshots in one second, or another program's file, stay as they are.
        while (true) {
            val name = Snapshot.freeName(Snapshot.fileName(title, picture.ptsMs)) { File(folder, it).exists() }
            val file = File(folder, name)
            try {
                Files.write(file.toPath(), png, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                return file
            } catch (_: FileAlreadyExistsException) {
                continue
            }
        }
    }
}
