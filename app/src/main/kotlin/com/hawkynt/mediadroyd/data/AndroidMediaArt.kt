package com.hawkynt.mediadroyd.data

import android.content.Context
import androidx.media3.common.C
import com.hawkynt.mediadroyd.engine.Track
import com.hawkynt.mediadroyd.nfo.StreamDetails
import com.hawkynt.mediadroyd.player.AndroidPlaybackEngine
import com.hawkynt.mediadroyd.ui.describe
import com.hawkynt.mediadroyd.ui.platform.MediaArt
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
        val (label, kind) = when (track.type) {
            C.TRACK_TYPE_VIDEO -> "Video" to Track.Kind.VIDEO
            C.TRACK_TYPE_AUDIO -> "Audio" to Track.Kind.AUDIO
            C.TRACK_TYPE_TEXT -> "Subtitles" to Track.Kind.TEXT
            else -> "Other" to Track.Kind.VIDEO
        }
        val format = track.format
        val engineFormat = AndroidPlaybackEngine.formatOf(format, kind)
        "$label: ${describe(Track("", kind, format.language, format.label, format = engineFormat))}"
    }
}
