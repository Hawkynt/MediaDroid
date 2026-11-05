package com.hawkynt.mediadroid.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.hawkynt.mediadroid.engine.desktop.MediaReader
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Art read once and kept while the app runs: JPEG and PNG through Skia (TIFF, TGA and PCX through the cores later). */
object Art {
    private val cache = ConcurrentHashMap<String, ImageBitmap?>()

    fun load(path: String?): ImageBitmap? = path?.let { p ->
        cache.getOrPut(p) {
            runCatching { Image.makeFromEncoded(File(p).readBytes()).toComposeImageBitmap() }.getOrNull()
        }
    }
}

/** A decoded picture as a bitmap the window can draw, reusing one Skia bitmap while the size stays. */
class PictureBitmap {
    private var bitmap: Bitmap? = null

    fun of(picture: MediaReader.Picture): ImageBitmap {
        val current = bitmap?.takeIf { it.width == picture.width && it.height == picture.height }
            ?: Bitmap().also {
                it.allocPixels(ImageInfo(picture.width, picture.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
                bitmap = it
            }
        current.installPixels(picture.rgba)
        return current.asComposeImageBitmap()
    }
}
