package com.hawkynt.mediadroyd.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.hawkynt.mediadroyd.engine.desktop.MediaReader
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

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
