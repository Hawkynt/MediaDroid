package com.hawkynt.mediadroid.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf
import com.hawkynt.mediadroid.nfo.StreamDetails
import java.io.File

/**
 * What the screens need of the platform to show a title's pictures and facts: each app reads its own files and keeps
 * its own caches, the screens only ask. A picture on this device's own storage is shown as it is, without asking.
 */
interface MediaArt {
    /** The picture at [location] (a network source's) as a local file to show, fetched once; null where none. */
    suspend fun picture(location: String): File? = null

    /** A still from [video] itself, the picture a poster would have been, cached; null where none can be had. */
    suspend fun still(video: String): File? = null

    /** What [video]'s file really holds, probed once and cached; null before, or where it cannot be read. */
    suspend fun streamDetails(video: String): StreamDetails? = null

    /** How many bytes the file at [location] holds; null where it cannot be told. */
    suspend fun fileSize(location: String): Long? = null

    /**
     * Every track of the video at [location] as the details' File section lists it, `Video: H.264 1920×1080`, video
     * first, then sound, then subtitles; null where the file cannot be read.
     */
    suspend fun tracks(location: String): List<String>? = null

    /** Nothing beyond this device's own pictures. */
    object None : MediaArt
}

/** The platform's [MediaArt]; each app provides its own at the root of its screens. */
val LocalMediaArt = staticCompositionLocalOf<MediaArt> { MediaArt.None }
