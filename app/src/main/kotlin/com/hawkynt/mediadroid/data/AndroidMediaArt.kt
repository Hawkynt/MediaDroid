package com.hawkynt.mediadroid.data

import android.content.Context
import androidx.media3.common.C
import com.hawkynt.mediadroid.nfo.StreamDetails
import com.hawkynt.mediadroid.ui.describe
import com.hawkynt.mediadroid.ui.platform.MediaArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The screens' pictures and facts on Android: network art through ArtCache, stills and probes from the device. */
class AndroidMediaArt(context: Context) : MediaArt {
    private val context = context.applicationContext

    override suspend fun picture(location: String): File? = ArtCache.fileFor(context, location)

    override suspend fun still(video: String): File? = ThumbnailStore.of(context).thumbnailFor(video)

    override suspend fun streamDetails(video: String): StreamDetails? = QualityStore.of(context).detailsFor(video)

    override suspend fun fileSize(location: String): Long? =
        withContext(Dispatchers.IO) { SourceAccess.resolve(context, location)?.length() }

    // As the player's extractors see the file (FileReport), each track named by its codec (describe).
    override suspend fun tracks(location: String): List<String>? = FileReport.tracks(context, location)?.map { track ->
        val kind = when (track.type) {
            C.TRACK_TYPE_VIDEO -> "Video"
            C.TRACK_TYPE_AUDIO -> "Audio"
            C.TRACK_TYPE_TEXT -> "Subtitles"
            else -> "Other"
        }
        "$kind: ${describe(track.format)}"
    }
}
