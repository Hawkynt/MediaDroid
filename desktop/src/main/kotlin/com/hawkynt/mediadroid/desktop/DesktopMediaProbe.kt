package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.engine.desktop.MediaProbe
import com.hawkynt.mediadroid.engine.desktop.MediaReader
import com.hawkynt.mediadroid.nfo.StreamDetails
import com.hawkynt.mediadroid.player.ToneMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * What the desktop knows of its videos without playing them, as the Android app's QualityStore and ThumbnailStore
 * know it: each file read once by FFmpeg's demuxer ([MediaProbe]) — its length, its tracks — and kept in [CACHE] in
 * the app-data [folder] by location, size and modification time, so a changed file is read again; and a still from
 * a tenth of the way in, decoded once and kept as a small JPEG under `thumbs/`. Everything runs off the window's
 * thread, one file at a time.
 */
class DesktopMediaProbe(private val places: DesktopLibrary, private val folder: File) {

    @Serializable
    private data class Stream(
        val kind: MediaProbe.Kind,
        val codec: String,
        val profile: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val frameRate: Float = 0f,
        val hdr: ToneMap.Transfer? = null,
        val channels: Int = 0,
        val language: String? = null,
        val title: String? = null,
    )

    @Serializable
    private data class Entry(val durationMs: Long = 0, val streams: List<Stream> = emptyList())

    private val file = File(folder, CACHE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Entry.serializer())
    private val reading = Mutex()
    private var cache: MutableMap<String, Entry>? = null
    private val stills = File(folder, "thumbs")
    private val extracting = Mutex()

    /** How long [video] runs; null where it cannot be read or does not say. */
    suspend fun durationMs(video: String): Long? = factsOf(video)?.durationMs?.takeIf { it > 0 }

    /** What [video] holds as its badges show it; null where it cannot be read. */
    suspend fun streamDetails(video: String): StreamDetails? = MediaFacts.details(factsOf(video))

    /** Every track of [video] as the details' File section lists it; null where it cannot be read. */
    suspend fun tracks(video: String): List<String>? = factsOf(video)?.let(MediaFacts::tracks)

    /** A still from [video], a tenth of the way in, as a JPEG at most [MAX_SIDE] wide or high; null where none. */
    suspend fun still(video: String): File? {
        val key = withContext(Dispatchers.IO) { keyOf(video) } ?: return null
        val target = File(stills, "$key.jpg")
        if (target.isFile && target.length() > 0) return target
        return extracting.withLock {
            if (target.isFile && target.length() > 0) return@withLock target
            withContext(Dispatchers.IO) { extract(video, target) }
        }
    }

    private suspend fun factsOf(video: String): MediaProbe.Facts? {
        val key = withContext(Dispatchers.IO) { keyOf(video) } ?: return null
        val entry = reading.withLock {
            val entries = cache ?: withContext(Dispatchers.IO) { load() }.also { cache = it }
            entries[key] ?: withContext(Dispatchers.IO) { probe(video) }?.also {
                entries[key] = it
                withContext(Dispatchers.IO) { save(entries) }
            }
        } ?: return null
        return MediaProbe.Facts(
            entry.durationMs,
            entry.streams.map {
                MediaProbe.Stream(
                    it.kind, it.codec, it.profile, it.width, it.height, it.frameRate, it.hdr, it.channels,
                    it.language, it.title,
                )
            },
        )
    }

    private fun probe(video: String): Entry? {
        val facts = MediaProbe.read(video, places.remoteFile(video)) ?: return null
        if (facts.streams.isEmpty()) return null
        return Entry(
            facts.durationMs,
            facts.streams.map {
                Stream(
                    it.kind, it.codec, it.profile, it.width, it.height, it.frameRate, it.hdr, it.channels,
                    it.language, it.title,
                )
            },
        )
    }

    // The file's location, size and modification time; null where it is not there.
    private fun keyOf(video: String): String? = runCatching {
        val file = places.file(video)
        if (!file.isFile || file.length() <= 0) return null
        val stamp = "$video|${file.length()}|${file.lastModified()}"
        MessageDigest.getInstance("SHA-1").digest(stamp.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun extract(video: String, target: File): File? = runCatching {
        val picture = MediaReader(video, places.remoteFile(video), decoders = emptyList()).use { reader ->
            if (reader.width <= 0) return null
            reader.seek(if (reader.durationMs > 0) reader.durationMs / 10 else 1_000)
            generateSequence { reader.read() }.take(MAX_ITEMS).filterIsInstance<MediaReader.Picture>().firstOrNull()
        }?.eightBit() ?: return null
        stills.mkdirs()
        val aside = File(stills, target.name + ".part")
        ImageIO.write(scaled(picture), "jpg", aside)
        if (!aside.renameTo(target)) return null
        target
    }.getOrElse {
        target.delete()
        null
    }

    private fun scaled(picture: MediaReader.Picture): BufferedImage {
        val full = BufferedImage(picture.width, picture.height, BufferedImage.TYPE_INT_RGB)
        val rgba = picture.rgba
        val row = IntArray(picture.width)
        for (y in 0 until picture.height) {
            for (x in 0 until picture.width) {
                val i = (y * picture.width + x) * 4
                row[x] = (rgba[i].toInt() and 0xFF shl 16) or (rgba[i + 1].toInt() and 0xFF shl 8) or
                    (rgba[i + 2].toInt() and 0xFF)
            }
            full.setRGB(0, y, picture.width, 1, row, 0, picture.width)
        }
        val longest = maxOf(picture.width, picture.height)
        if (longest <= MAX_SIDE) return full
        val w = (picture.width * MAX_SIDE / longest).coerceAtLeast(1)
        val h = (picture.height * MAX_SIDE / longest).coerceAtLeast(1)
        return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { small ->
            small.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(full, 0, 0, w, h, null)
                dispose()
            }
        }
    }

    private fun load(): MutableMap<String, Entry> =
        runCatching { json.decodeFromString(serializer, file.readText()).toMutableMap() }.getOrDefault(HashMap())

    private fun save(entries: Map<String, Entry>) {
        runCatching {
            folder.mkdirs()
            val aside = File(folder, "$CACHE.part")
            aside.writeText(json.encodeToString(serializer, entries))
            aside.renameTo(file) || run { file.delete(); aside.renameTo(file) }
        }
    }

    companion object {
        /** The file the facts are kept in, in the app-data folder. */
        const val CACHE = "media-info.json"

        /** The longest side of a still, as the Android app's. */
        const val MAX_SIDE = 480

        // How many pictures and sounds a still may read past the point before giving up.
        private const val MAX_ITEMS = 2_000
    }
}
