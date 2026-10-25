package com.hawkynt.mediadroid.library

import com.hawkynt.mediadroid.nfo.ArtKind
import com.hawkynt.mediadroid.nfo.EpisodeRef
import com.hawkynt.mediadroid.nfo.episodeRefFromName
import com.hawkynt.mediadroid.nfo.findArt
import com.hawkynt.mediadroid.nfo.parseEpisodeNfo
import com.hawkynt.mediadroid.nfo.parseMovieNfo
import com.hawkynt.mediadroid.nfo.parseShowNfo
import java.io.File

/**
 * Turns a library root folder into a [Library] by walking it the way a collector
 * organizes disks: a folder with a `tvshow.nfo` or `Season NN` subfolders is a
 * show, a folder with a `movie.nfo` or a `Title (Year).mkv` is a movie, and a
 * loose video file with a same-stem `.nfo` beside it is a movie too.
 *
 * Everything is [java.io.File] so the whole scan runs — and is tested — as plain
 * JVM code, no Android, no storage framework.
 */
object LibraryScanner {

    private val videoExtensions = setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "ts")
    private val seasonDirName = Regex("(?i)^season[ ._-]*(\\d+)$")
    private val titleYear = Regex("^(.*)\\s*\\((\\d{4})\\)$")

    fun scan(root: File): Library {
        val movies = ArrayList<Movie>()
        val shows = ArrayList<Show>()
        walk(root, movies, shows, isRoot = true)
        return Library(
            movies = movies.sortedBy { it.title.lowercase() },
            shows = shows.sortedBy { it.title.lowercase() },
        )
    }

    private fun walk(dir: File, movies: MutableList<Movie>, shows: MutableList<Show>, isRoot: Boolean) {
        val children = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: return
        val files = children.filter { it.isFile }
        val subDirs = children.filter { it.isDirectory }

        if (isShowDirectory(files, subDirs)) {
            parseShow(dir, files, subDirs)?.let { shows.add(it) }
            return
        }
        if (!isRoot && isMovieDirectory(files)) {
            parseMovieFolder(dir, files)?.let { movies.add(it) }
            return
        }
        for (file in files) {
            if (isVideo(file)) parseLooseMovie(file, files)?.let { movies.add(it) }
        }
        for (sub in subDirs) walk(sub, movies, shows, isRoot = false)
    }

    private fun isVideo(file: File): Boolean = file.extension.lowercase() in videoExtensions

    /**
     * A folder is a show when it says so (`tvshow.nfo`), keeps `Season NN`
     * subfolders, or every one of its videos is episode-named (`SxxExx`). The
     * last rule is deliberately all-or-nothing: an `Incoming` dump that mixes
     * episodes with `Title (Year)` movies is a pile of loose files, not a show.
     */
    private fun isShowDirectory(files: List<File>, subDirs: List<File>): Boolean {
        if (files.any { it.name.equals("tvshow.nfo", ignoreCase = true) }) return true
        if (subDirs.any { seasonDirName.matches(it.name) }) return true
        val videos = files.filter { isVideo(it) }
        return videos.isNotEmpty() && videos.all { episodeRefFromName(it.name) != null }
    }

    /**
     * A folder is ONE movie when it carries a `movie.nfo` or holds a single video
     * that looks like a movie. A folder with several candidate videos (an
     * `Incoming` dump) is not one movie — each video is handled on its own.
     */
    private fun isMovieDirectory(files: List<File>): Boolean {
        if (files.any { it.name.equals("movie.nfo", ignoreCase = true) }) return true
        val videos = files.filter { isVideo(it) }
        if (videos.size != 1) return false
        val video = videos[0]
        return sidecarNfo(video, files.map { it.name }) != null || titleYear.matches(video.nameWithoutExtension)
    }

    private fun sidecarNfo(video: File, names: List<String>): File? {
        val nfo = video.nameWithoutExtension + ".nfo"
        val hit = names.firstOrNull { it.equals(nfo, ignoreCase = true) } ?: return null
        return File(video.parentFile, hit)
    }

    // ----- shows ---------------------------------------------------------------------------------

    private fun parseShow(dir: File, files: List<File>, subDirs: List<File>): Show? {
        val nfoFile = files.firstOrNull { it.name.equals("tvshow.nfo", ignoreCase = true) }
        val info = nfoFile?.readTextSafe()?.let { runCatching { parseShowNfo(it) }.getOrNull() }

        val folderMatch = titleYear.find(dir.name)
        val title = info?.title ?: folderMatch?.groupValues?.get(1)?.trim() ?: dir.name
        val year = info?.firstAiredYear ?: folderMatch?.groupValues?.get(2)?.toIntOrNull()

        val seasons = subDirs.mapNotNull { sub ->
            val number = seasonDirName.find(sub.name)?.groupValues?.get(1)?.toIntOrNull()
                ?: sub.name.filter { it.isDigit() }.toIntOrNull()
                ?: return@mapNotNull null
            val siblings = sub.listFiles()?.map { it.name } ?: return@mapNotNull null
            val episodes = sub.listFiles()!!.filter { isVideo(it) }
                .mapNotNull { video -> parseEpisode(video, siblings) }
                .sortedBy { it.number }
            if (episodes.isEmpty()) null else Season(number, episodes)
        }.sortedBy { it.number }

        val art = findArt(files.map { it.name })
        return Show(
            title = title,
            year = year,
            genres = info?.genres ?: emptyList(),
            path = dir.absolutePath,
            posterPath = art[ArtKind.Poster]?.let { File(dir, it).absolutePath },
            fanartPath = art[ArtKind.Fanart]?.let { File(dir, it).absolutePath },
            plot = info?.plot,
            seasons = seasons,
        )
    }

    private fun parseEpisode(video: File, siblingNames: List<String>): Episode? {
        val nfo = sidecarNfo(video, siblingNames)?.readTextSafe()
            ?.let { runCatching { parseEpisodeNfo(it) }.getOrNull() }
        val ref: EpisodeRef? = episodeRefFromName(video.name)
        if (nfo?.episode == null && ref == null) return null

        val stem = video.nameWithoutExtension
        val thumb = siblingNames.firstOrNull { it.equals("$stem-thumb.jpg", ignoreCase = true) }
            ?: siblingNames.firstOrNull { it.equals("$stem-thumb.png", ignoreCase = true) }

        return Episode(
            number = nfo?.episode ?: ref!!.episode,
            title = nfo?.title,
            firstAired = nfo?.firstAired,
            watched = (nfo?.playcount ?: 0) > 0,
            videoPath = video.absolutePath,
            thumbPath = thumb?.let { File(video.parentFile, it).absolutePath },
        )
    }

    // ----- movies --------------------------------------------------------------------------------

    private fun parseMovieFolder(dir: File, files: List<File>): Movie? {
        val videos = files.filter { isVideo(it) }
        val video = videos.maxByOrNull { it.length() } ?: return null

        val nfoFile = files.firstOrNull { it.name.equals("movie.nfo", ignoreCase = true) }
            ?: sidecarNfo(video, files.map { it.name })
        val info = nfoFile?.readTextSafe()?.let { runCatching { parseMovieNfo(it) }.getOrNull() }

        val folderMatch = titleYear.find(dir.name)
        val videoMatch = titleYear.find(video.nameWithoutExtension)
        val title = info?.title
            ?: folderMatch?.groupValues?.get(1)?.trim()
            ?: videoMatch?.groupValues?.get(1)?.trim()
            ?: video.nameWithoutExtension
        val year = info?.year
            ?: folderMatch?.groupValues?.get(2)?.toIntOrNull()
            ?: videoMatch?.groupValues?.get(2)?.toIntOrNull()

        // Prefer art named after the video (`Coco (2017)-poster.jpg`), fall back to folder art.
        val stem = video.nameWithoutExtension
        val names = files.map { it.name }
        val art = findArt(names)
        val poster = names.firstOrNull { it.lowercase().startsWith("${stem.lowercase()}-poster.") } ?: art[ArtKind.Poster]
        val fanart = names.firstOrNull { it.lowercase().startsWith("${stem.lowercase()}-fanart.") } ?: art[ArtKind.Fanart]
        return Movie(
            title = title,
            year = year,
            genres = info?.genres ?: emptyList(),
            path = dir.absolutePath,
            posterPath = poster?.let { File(dir, it).absolutePath },
            fanartPath = fanart?.let { File(dir, it).absolutePath },
            videoPath = video.absolutePath,
            plot = info?.plot,
            runtimeMinutes = info?.runtimeMinutes,
            cast = info?.cast?.map { it.name } ?: emptyList(),
            playcount = info?.playcount ?: 0,
        )
    }

    private fun parseLooseMovie(video: File, siblings: List<File>): Movie? {
        val names = siblings.map { it.name }
        val info = sidecarNfo(video, names)?.readTextSafe()
            ?.let { runCatching { parseMovieNfo(it) }.getOrNull() }
        val nameMatch = titleYear.find(video.nameWithoutExtension)
        if (info == null && nameMatch == null) return null

        // Per-movie art beside a loose file: `Coco (2017)-poster.jpg`.
        val stem = video.nameWithoutExtension
        val poster = names.firstOrNull { it.lowercase().startsWith("${stem.lowercase()}-poster.") }
        val fanart = names.firstOrNull { it.lowercase().startsWith("${stem.lowercase()}-fanart.") }

        return Movie(
            title = info?.title ?: nameMatch!!.groupValues[1].trim(),
            year = info?.year ?: nameMatch?.groupValues?.get(2)?.toIntOrNull(),
            genres = info?.genres ?: emptyList(),
            path = video.parentFile.absolutePath,
            posterPath = poster?.let { File(video.parentFile, it).absolutePath },
            fanartPath = fanart?.let { File(video.parentFile, it).absolutePath },
            videoPath = video.absolutePath,
            plot = info?.plot,
            runtimeMinutes = info?.runtimeMinutes,
            cast = info?.cast?.map { it.name } ?: emptyList(),
            playcount = info?.playcount ?: 0,
        )
    }

    private fun File.readTextSafe(): String? = runCatching { readText() }.getOrNull()
}
