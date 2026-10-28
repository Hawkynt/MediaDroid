package com.hawkynt.mediadroid.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.inspector.MetadataRetriever
import com.hawkynt.mediadroid.player.AviAudioFraming
import com.hawkynt.mediadroid.player.SourceDataSource
import com.hawkynt.mediadroid.player.playableUri
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One track of a file, and the decoder that would play it here; null for subtitles and unplayable tracks. */
data class TrackReport(val type: Int, val format: Format, val decoder: String?)

/**
 * What a file holds, for the details' File section: every track as the player's extractors see it,
 * and which decoder this device would use — its own first, the bundled FFmpeg when it has none.
 */
@OptIn(UnstableApi::class)
object FileReport {
    private const val TIMEOUT_S = 20L

    /** The tracks of the video at [location], or null when it cannot be read. */
    suspend fun tracks(context: Context, location: String): List<TrackReport>? = withContext(Dispatchers.IO) {
        runCatching {
            MetadataRetriever.Builder(context, MediaItem.fromUri(playableUri(location)))
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(
                        SourceDataSource.Factory(context) { SourceAccess.resolve(context, it) },
                        AviAudioFraming.extractors,
                    ),
                )
                .build()
                .use { retriever ->
                    val groups = retriever.retrieveTrackGroups().get(TIMEOUT_S, TimeUnit.SECONDS)
                    (0 until groups.length).flatMap { g ->
                        val group = groups[g]
                        (0 until group.length).map { i ->
                            val format = group.getFormat(i)
                            TrackReport(group.type, format, decoderFor(format))
                        }
                    }.sortedBy { order(it.type) }
                }
        }.getOrNull()
    }

    private fun decoderFor(format: Format): String? {
        val mime = format.sampleMimeType ?: return null
        if (!MimeTypes.isVideo(mime) && !MimeTypes.isAudio(mime)) return null
        val device = runCatching { MediaCodecUtil.getDecoderInfos(mime, false, false).firstOrNull()?.name }.getOrNull()
        return device ?: if (FfmpegLibrary.supportsFormat(mime)) "ffmpeg" else null
    }

    private fun order(type: Int) = when (type) {
        C.TRACK_TYPE_VIDEO -> 0
        C.TRACK_TYPE_AUDIO -> 1
        C.TRACK_TYPE_TEXT -> 2
        else -> 3
    }
}
