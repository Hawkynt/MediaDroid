package com.hawkynt.mediadroid.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFormatsTest {

    @Test
    fun everyPictureTheAppCanShowCountsAsArt() {
        val all = listOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "tif", "tiff", "tga", "pcx",
        )
        for (ext in all) {
            assertTrue(ext, ImageFormats.isImage("poster.$ext"))
            assertTrue(ext, ImageFormats.isImage("POSTER.${ext.uppercase()}"))
        }
        assertFalse(ImageFormats.isImage("movie.nfo"))
        assertFalse(ImageFormats.isImage("poster"))
    }

    @Test
    fun onlyFormatsAndroidCannotReadAreDecodedHere() {
        assertEquals(setOf("tif", "tiff", "tga", "pcx"), ImageFormats.decodedHere)
    }

    @Test
    fun decodeDispatchesByExtensionAndContent() {
        assertNotNull(ImageFormats.decode(Oracle.bytes("tga/200x32_rgb_tl_rle.tga"), "tga"))
        assertNotNull(ImageFormats.decode(Oracle.bytes("pcx/hopper_rgb.pcx"), "pcx"))
        // A PCX renamed to .tif is still recognized by its signature.
        assertNotNull(ImageFormats.decode(Oracle.bytes("pcx/hopper_rgb.pcx"), "tif"))
        assertNull(ImageFormats.decode(Oracle.bytes("pcx/hopper_rgb.pcx"), "jpg"))
    }
}
