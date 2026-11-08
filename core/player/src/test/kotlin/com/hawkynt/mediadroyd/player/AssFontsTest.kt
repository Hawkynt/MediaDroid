package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssFontsTest {
    @Test
    fun `an attachment is a font by its MIME type`() {
        listOf(
            "application/x-truetype-font", "application/vnd.ms-opentype", "application/x-font-ttf",
            "application/x-font", "application/font-sfnt", "font/collection", "font/otf", "font/sfnt", "font/ttf",
        ).forEach { assertTrue(it, AssFonts.isFont(it, "x.bin")) }
        assertTrue(AssFonts.isFont("FONT/TTF", "x"))
        assertFalse(AssFonts.isFont("image/jpeg", "cover.jpg"))
    }

    @Test
    fun `without a font MIME type, its extension says`() {
        assertTrue(AssFonts.isFont(null, "Title.TTF"))
        assertTrue(AssFonts.isFont("application/octet-stream", "sign.otf"))
        assertTrue(AssFonts.isFont("", "cjk.ttc"))
        assertTrue(AssFonts.isFont(null, "cjk.otc"))
        assertFalse(AssFonts.isFont(null, "notes.txt"))
        assertFalse(AssFonts.isFont("image/png", "cover.ttf.png"))
    }
}
