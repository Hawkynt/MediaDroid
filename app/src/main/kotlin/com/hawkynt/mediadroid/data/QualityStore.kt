package com.hawkynt.mediadroid.data

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.inspector.MetadataRetriever
import com.hawkynt.mediadroid.library.FsFile
import com.hawkynt.mediadroid.library.Quality
import com.hawkynt.mediadroid.nfo.AudioStream
import com.hawkynt.mediadroid.nfo.StreamDetails
import com.hawkynt.mediadroid.player.AviAudioFraming
import com.hawkynt.mediadroid.player.SourceDataSource
import com.hawkynt.mediadroid.player.playableUri
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * What a video file really holds — resolution, HDR, codecs, audio channels and languages — read out of
 * the file once by Media3's [MetadataRetriever] (the same extractors the player uses, AVI included) and
 * kept in a small JSON cache keyed by path, size and modification time. A changed file is probed again.
 */
@OptIn(UnstableApi::class)
class QualityStore private constructor(private val context: Context) {

    @Serializable
    private data class Audio(val codec: String? = null, val channels: Int? = null, val language: String? = null)

    @Serializable
    private data class Entry(
        val width: Int? = null,
        val height: Int? = null,
        val videoCodec: String? = null,
        val hdr: String? = null,
        val audio: List<Audio> = emptyList(),
        val durationMs: Long? = null,
    )

    // Version 2 added the duration; the old cache is simply probed again.
    private val file = File(context.filesDir, "media-info-2.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Entry.serializer())
    private val lock = Mutex()
    private var cache: MutableMap<String, Entry>? = null

    /** The stream details of [videoPath] as the file says, or null when it cannot be read. */
    suspend fun detailsFor(videoPath: String): StreamDetails? {
        val entry = entryFor(videoPath) ?: return null
        return StreamDetails(
            entry.width,
            entry.height,
            entry.videoCodec,
            entry.hdr,
            entry.audio.map { AudioStream(it.codec, it.channels, it.language) },
        )
    }

    /** How long [videoPath] plays, as the file says; null when it cannot be read. */
    suspend fun durationMsFor(videoPath: String): Long? = entryFor(videoPath)?.durationMs

    private suspend fun entryFor(videoPath: String): Entry? {
        val video = SourceAccess.resolve(context, videoPath) ?: return null
        val key = "$videoPath|${video.length()}|${video.lastModified()}"
        return lock.withLock {
            val entries = cache ?: withContext(Dispatchers.IO) { load() }.also { cache = it }
            entries[key] ?: withContext(Dispatchers.IO) { probe(video) }?.also {
                entries[key] = it
                withContext(Dispatchers.IO) { save(entries) }
            }
        }
    }

    private fun probe(video: FsFile): Entry? = runCatching {
        MetadataRetriever.Builder(context, MediaItem.fromUri(playableUri(video.path)))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    SourceDataSource.Factory(context) { SourceAccess.resolve(context, it) },
                    AviAudioFraming.extractors,
                ),
            )
            .build()
            .use { retriever ->
                val groups = retriever.retrieveTrackGroups().get(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
                val formats = (0 until groups.length).flatMap { g ->
                    val group = groups[g]
                    (0 until group.length).map { group.getFormat(it) }
                }
                val video = formats.firstOrNull { MimeTypes.isVideo(it.sampleMimeType) }
                val hdr = when {
                    video?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "dolbyvision"
                    video?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "hdr10"
                    video?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "hlg"
                    else -> null
                }
                Entry(
                    width = video?.width?.takeIf { it > 0 },
                    height = video?.height?.takeIf { it > 0 },
                    videoCodec = Quality.codecOf(video?.sampleMimeType, video?.codecs),
                    hdr = hdr,
                    audio = formats.filter { MimeTypes.isAudio(it.sampleMimeType) }.map {
                        Audio(Quality.codecOf(it.sampleMimeType, it.codecs), it.channelCount.takeIf { c -> c > 0 }, it.language)
                    },
                    durationMs = runCatching { retriever.retrieveDurationUs().get(PROBE_TIMEOUT_S, TimeUnit.SECONDS) }
                        .getOrNull()
                        ?.takeIf { it != C.TIME_UNSET && it > 0 }
                        ?.let { it / 1000 },
                )
            }
    }.getOrNull()

    private fun load(): MutableMap<String, Entry> =
        runCatching { json.decodeFromString(serializer, file.readText()).toMutableMap() }.getOrDefault(HashMap())

    private fun save(entries: Map<String, Entry>) {
        runCatching { file.writeText(json.encodeToString(serializer, entries)) }
    }

    companion object {
        private const val PROBE_TIMEOUT_S = 20L

        @Volatile
        private var instance: QualityStore? = null

        fun of(context: Context): QualityStore =
            instance ?: synchronized(this) {
                instance ?: QualityStore(context.applicationContext).also { instance = it }
            }
    }
}
