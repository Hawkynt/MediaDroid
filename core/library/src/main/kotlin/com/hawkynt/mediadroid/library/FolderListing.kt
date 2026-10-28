package com.hawkynt.mediadroid.library

import com.hawkynt.mediadroid.imaging.ImageFormats
import com.hawkynt.mediadroid.nfo.ArtKind
import com.hawkynt.mediadroid.nfo.findArt
import com.hawkynt.mediadroid.nfo.minAgeOf
import com.hawkynt.mediadroid.nfo.parseEpisodeNfo
import com.hawkynt.mediadroid.nfo.parseMovieNfo
import java.io.File

/** How the Files browser orders a folder's videos; folders always come first, by name. */
enum class FileSort(val label: String) { NAME("Name"), DATE("Date"), SIZE("Size") }

/** A folder in the Files browser, with its `folder.jpg` / `poster.*` cover when it has one. */
data class FolderEntry(val name: String, val path: String, val coverPath: String?)

/**
 * A video in the Files browser: its real file name, a title only when an NFO beside it gives one, the
 * usual art, and what else the NFO says.
 */
data class FileEntry(
    val name: String,
    val path: String,
    val title: String,
    val picturePath: String?,
    val plot: String? = null,
    val minAge: Int? = null,
    val nfoWatched: Boolean = false,
    val sizeBytes: Long = 0,
    val modifiedMs: Long = 0,
    val audioLanguages: List<String> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
    val quality: Quality = Quality.NONE,
)

/** One folder's content as the Files browser shows it. */
data class Listing(val folders: List<FolderEntry>, val videos: List<FileEntry>)

/**
 * Storage as it really is, for the Files browser: no release-name clean-up and no grouping into movies,
 * shows or seasons. Only what sits beside a video is read — its NFO and its art.
 */
object FolderListing {

    /** [dir]'s folders (hidden ones left out) and videos, the videos in [sort] order. */
    fun list(dir: File, sort: FileSort = FileSort.NAME): Listing {
        val entries = dir.listFiles()?.filterNot { it.name.startsWith(".") } ?: emptyList()
        val names = entries.filter { it.isFile }.map { it.name }
        val folders = entries.filter { it.isDirectory }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .map { FolderEntry(it.name, it.absolutePath, cover(it)) }
        val videos = entries.filter { it.isFile && MediaFiles.isVideo(it.name) }
            .map { entry(it, names) }
            .sortedWith(
                when (sort) {
                    FileSort.NAME -> compareBy(NaturalOrder) { it.name }
                    FileSort.DATE -> compareByDescending<FileEntry> { it.modifiedMs }.thenBy(NaturalOrder) { it.name }
                    FileSort.SIZE -> compareByDescending<FileEntry> { it.sizeBytes }.thenBy(NaturalOrder) { it.name }
                },
            )
        return Listing(folders, videos)
    }

    private fun cover(dir: File): String? {
        val names = dir.list()?.toList() ?: return null
        return findArt(names)[ArtKind.Poster]?.let { File(dir, it).absolutePath }
    }

    private fun entry(video: File, names: List<String>): FileEntry {
        val stem = video.nameWithoutExtension
        val nfo = names.firstOrNull { it.equals("$stem.nfo", ignoreCase = true) }
            ?.let { runCatching { File(video.parentFile, it).readText() }.getOrNull() }
        val movie = nfo?.takeIf { "<movie" in it }?.let { runCatching { parseMovieNfo(it) }.getOrNull() }
        val episode = nfo?.takeIf { movie == null }?.let { runCatching { parseEpisodeNfo(it) }.getOrNull() }
        return FileEntry(
            name = video.name,
            path = video.absolutePath,
            title = movie?.title ?: episode?.title ?: stem,
            picturePath = picture(stem, names)?.let { File(video.parentFile, it).absolutePath },
            plot = movie?.plot ?: episode?.plot,
            minAge = minAgeOf(movie?.mpaa),
            nfoWatched = (movie?.playcount ?: episode?.playcount ?: 0) > 0,
            sizeBytes = video.length(),
            modifiedMs = video.lastModified(),
            audioLanguages = movie?.audioLanguages ?: episode?.audioLanguages ?: emptyList(),
            subtitleLanguages = movie?.subtitleLanguages ?: episode?.subtitleLanguages ?: emptyList(),
            quality = Quality.of(movie?.streamDetails ?: episode?.streamDetails),
        )
    }

    /** `<stem>.<image>`, then `<stem>-poster.*`, then `<stem>-thumb.*`. */
    private fun picture(stem: String, names: List<String>): String? {
        val images = names.filter { ImageFormats.isImage(it) }
        return listOf(stem, "$stem-poster", "$stem-thumb").firstNotNullOfOrNull { base ->
            images.firstOrNull { it.substringBeforeLast('.').equals(base, ignoreCase = true) }
        }
    }
}

/** Names compared the way people read them: case ignored, `2` before `10`. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val order = if (p[0].isDigit() && q[0].isDigit()) {
                p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 }
                    ?: p.trimStart('0').compareTo(q.trimStart('0'))
            } else {
                p.compareTo(q)
            }
            if (order != 0) return order
        }
        return x.size.compareTo(y.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
