package com.hawkynt.mediadroid.imaging

/**
 * Which picture files the app shows, and the decoding of those Android itself cannot read.
 * Android's own codecs cover JPEG, PNG, WebP, GIF, BMP, HEIF/HEIC (Android 9+) and AVIF (Android 12+);
 * TIFF, TGA and PCX are decoded here.
 */
object ImageFormats {

    /** Extensions decoded by this module instead of the platform. */
    val decodedHere: Set<String> = setOf("tif", "tiff", "tga", "pcx")

    /** Every extension the app treats as a picture. */
    val extensions: Set<String> =
        setOf("jpg", "jpeg", "jpe", "png", "webp", "gif", "bmp", "dib", "heic", "heif", "avif") + decodedHere

    /** Whether [fileName] is a picture the app can show, judged by its extension. */
    fun isImage(fileName: String): Boolean {
        val dot = fileName.lastIndexOf('.')
        return dot > 0 && fileName.substring(dot + 1).lowercase() in extensions
    }

    /**
     * Decodes a TIFF, TGA or PCX file of extension [extension]. TIFF and PCX are recognized by their
     * signature whatever the name says; TGA has none, so only a `.tga` is tried as one. Null for
     * anything else and anything broken.
     */
    fun decode(bytes: ByteArray, extension: String, maxPixels: Int = Int.MAX_VALUE): RasterImage? {
        val ext = extension.lowercase()
        if (ext !in decodedHere) return null
        return when {
            TiffDecoder.accepts(bytes) -> TiffDecoder.decode(bytes, maxPixels)
            PcxDecoder.accepts(bytes) -> PcxDecoder.decode(bytes, maxPixels)
            ext == "tga" -> TgaDecoder.decode(bytes, maxPixels)
            else -> null
        }
    }
}
