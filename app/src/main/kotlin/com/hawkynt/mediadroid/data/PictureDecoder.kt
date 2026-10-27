package com.hawkynt.mediadroid.data

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Dimension
import com.hawkynt.mediadroid.imaging.ImageFormats

/**
 * Lets Coil show the pictures Android has no codec for — TIFF, TGA and PCX — through the decoders
 * in `:core:imaging`. Everything else stays with Coil's own platform decoders.
 */
class PictureDecoder(
    private val source: ImageSource,
    private val extension: String,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val bytes = source.source().use { it.readByteArray() }
        val raster = ImageFormats.decode(bytes, extension, pixelBudget()) ?: return null
        val bitmap = Bitmap.createBitmap(raster.argb, raster.width, raster.height, Bitmap.Config.ARGB_8888)
        return DecodeResult(bitmap.asImage(), isSampled = raster.sampled)
    }

    /** Four times the pixels of the target size, so sampling down never shows; 8 MP when the size is open. */
    private fun pixelBudget(): Int {
        val width = (options.size.width as? Dimension.Pixels)?.px
        val height = (options.size.height as? Dimension.Pixels)?.px
        return if (width != null && height != null) {
            (width.toLong() * height * 4).coerceIn(1L, MAX_PIXELS.toLong()).toInt()
        } else {
            MAX_PIXELS
        }
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val name = result.source.fileOrNull()?.name ?: return null
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension !in ImageFormats.decodedHere) return null
            return PictureDecoder(result.source, extension, options)
        }
    }

    companion object {
        private const val MAX_PIXELS = 8_000_000

        /** Installs the app-wide image loader with this decoder added; call once at start-up. */
        fun install() {
            SingletonImageLoader.setSafe { ctx ->
                ImageLoader.Builder(ctx)
                    .components { add(Factory()) }
                    .build()
            }
        }
    }
}
