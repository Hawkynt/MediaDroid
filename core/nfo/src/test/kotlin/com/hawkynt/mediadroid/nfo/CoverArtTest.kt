package com.hawkynt.mediadroid.nfo

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverArtTest {

    @Test
    fun folderJpgIsTheCoverWhenNoPosterExists() {
        val art = findArt(listOf("folder.jpg", "fanart.jpg", "movie.mkv"))
        assertEquals("folder.jpg", art[ArtKind.Poster])
        assertEquals("fanart.jpg", art[ArtKind.Fanart])
    }

    @Test
    fun posterBeatsFolder() {
        val art = findArt(listOf("folder.jpg", "poster.jpg"))
        assertEquals("poster.jpg", art[ArtKind.Poster])
    }

    @Test
    fun suffixedVariantsStillCount() {
        val art = findArt(listOf("poster-1.jpg", "tvshow-banner.png"))
        assertEquals("poster-1.jpg", art[ArtKind.Poster])
        assertEquals("tvshow-banner.png", art[ArtKind.Banner])
    }

    @Test
    fun nonImagesAreIgnored() {
        assertEquals(emptyMap<ArtKind, String>(), findArt(listOf("poster.txt", "fanart.nfo")))
    }

    @Test
    fun everyPictureFormatTheAppReadsCounts() {
        for (ext in listOf("gif", "bmp", "heic", "heif", "avif", "tif", "tiff", "tga", "pcx")) {
            assertEquals(ext, "folder.$ext", findArt(listOf("folder.$ext"))[ArtKind.Poster])
        }
    }
}
