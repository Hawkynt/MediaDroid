package com.hawkynt.mediadroid.desktop

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skiko.SkiaLayer
import java.awt.Container
import java.awt.Window
import java.io.File

/**
 * The app's own window as a picture, straight from the layer Compose draws it into — no screen capture (which a
 * Wayland session would not allow anyway), nothing but the window's content.
 */
object Snapshot {
    fun of(window: Window, target: File) {
        val layer = find(window) ?: error("no drawing layer in the window")
        val bitmap = layer.screenshot() ?: error("the layer gave no picture")
        val png = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG) ?: error("cannot encode")
        target.parentFile?.mkdirs()
        target.writeBytes(png.bytes)
    }

    private fun find(container: Container): SkiaLayer? {
        for (child in container.components) {
            if (child is SkiaLayer) return child
            if (child is Container) find(child)?.let { return it }
        }
        return null
    }
}
