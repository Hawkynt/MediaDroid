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
    private val episodeToken = Regex("(?i)\\b(?:s\\d{1,2}[ ._-]?e\\d{1,3}|\\d{1,2}x(?!26[456]\\b)\\d{1,3})\\b")
    // Release junk that rip groups append after the episode: resolution, source,
    // codec, audio layout, flags. The episode title ends at the first of these.
    private val junkToken = Regex(
        "(?i)\\b(?:\\d{3,4}p|\\d{3,4}x\\d{3,4}|4k|x26[456]|h26[45]|hevc|avc|av1|divx|xvid|" +
            "web-?dl|webrip|hdtv|dvdr?ip|bdrip|brrip|blu-?ray|uhd|telesync|proper|repack|internal|read-?nfo|sample|" +
            "atmos|truehd|dts-?hd|ddp?5\\.1|[57]\\.1|ac3|aac|10-?bit|8-?bit|hdr10?|ntsc|pal)\\b",
    )
    private val trailingYear = Regex("\\s((?:19|20)\\d{2})$")

    fun scan(root: File): Library {
        val movies = ArrayList<Movie>()
        val shows = ArrayList<Show>()
        walk(root, movies, shows, isRoot = true)
        return Library(
            movies = movies.sortedBy { it.title.lowercase() },
            // Loose episodes found in a dump folder connect to the show folder
            // living elsewhere under the same name — one show, all its seasons.
            shows = shows
                .groupBy { it.title.lowercase() }
                .map { (_, group) -> if (group.size == 1) group[0] else mergeShows(group) }
                .sortedBy { it.title.lowercase() },
        )
    }

    private fun mergeShows(group: List<Show>): Show {
        val seasons = group.flatMap { it.seasons }
            .groupBy { it.number }
            .map { (number, list) ->
                Season(number, list.flatMap { it.episodes }.distinctBy { it.videoPath }.sortedBy { it.number })
            }
            .sortedBy { it.number }
        val primary = group.maxByOrNull {
            (if (it.year != null) 1 else 0) + (if (it.genres.isNotEmpty()) 1 else 0) + (if (it.plot != null) 1 else 0)
        } ?: group[0]
        return Show(
            title = primary.title,
            year = group.firstNotNullOfOrNull { it.year },
            genres = group.firstNotNullOfOrNull { it.genres.ifEmpty { null } } ?: emptyList(),
            path = primary.path,
            posterPath = group.firstNotNullOfOrNull { it.posterPath },
            fanartPath = group.firstNotNullOfOrNull { it.fanartPath },
            plot = group.firstNotNullOfOrNull { it.plot },
            seasons = seasons,
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
            if (isVideo(file) && episodeRefFromName(file.name) == null) parseLooseMovie(file, files)?.let { movies.add(it) }
        }
        addLooseEpisodeShows(dir, files, shows)
        for (sub in subDirs) walk(sub, movies, shows, isRoot = false)
    }

    /**
     * In a mixed dump folder (`Incoming` and friends), episode-named videos group
     * into shows by the name they share before the `SxxExx` token — release junk
     * after the token is ignored, a trailing year becomes the show year.
     */
    private fun addLooseEpisodeShows(dir: File, files: List<File>, shows: MutableList<Show>) {
        val videos = files.filter { isVideo(it) && episodeRefFromName(it.name) != null }
        if (videos.isEmpty()) return
        val folderMatch = titleYear.find(dir.name)
        val names = files.map { it.name }
        val art = findArt(names)
        videos.groupBy { showKeyFromName(it.nameWithoutExtension) ?: "" }.forEach { (key, group) ->
            val title = key.ifEmpty { folderMatch?.groupValues?.get(1)?.trim() ?: dir.name }
            val seasons = group.mapNotNull { video ->
                parseEpisode(video, names, title)?.let { ep -> episodeRefFromName(video.name)!!.season to ep }
            }.groupBy({ it.first }, { it.second })
                .map { (number, episodes) -> Season(number, episodes.sortedBy { it.number }) }
                .sortedBy { it.number }
            if (seasons.isEmpty()) return@forEach
            shows.add(
                Show(
                    title = title,
                    year = folderMatch?.groupValues?.get(2)?.toIntOrNull(),
                    genres = emptyList(),
                    path = dir.absolutePath,
                    posterPath = art[ArtKind.Poster]?.let { File(dir, it).absolutePath },
                    fanartPath = art[ArtKind.Fanart]?.let { File(dir, it).absolutePath },
                    plot = null,
                    seasons = seasons,
                ),
            )
        }
    }

    /** The show name a scene-style file carries before its episode token. */
    private fun showKeyFromName(stem: String): String? {
        val match = episodeToken.find(stem) ?: return null
        var prefix = cleanSeparators(stem.substring(0, match.range.first))
        val year = trailingYear.find(prefix)
        if (year != null) prefix = cleanSeparators(prefix.substring(0, year.range.first))
        return prefix.ifEmpty { null }
    }

    private fun cleanSeparators(text: String): String =
        text.replace(Regex("[._\\-]+"), " ").replace(Regex("\\s{2,}"), " ").trim()

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

        // When the loose files all name the same show, that name beats the
        // folder's — a dump folder called `Incoming` full of `Firefly.SxxExx`
        // files is Firefly, not a show named Incoming.
        val looseKey = files.filter { isVideo(it) }
            .mapNotNull { showKeyFromName(it.nameWithoutExtension) }
            .distinct()
            .singleOrNull()

        val folderMatch = titleYear.find(dir.name)
        val title = info?.title ?: looseKey ?: folderMatch?.groupValues?.get(1)?.trim() ?: dir.name
        val year = info?.firstAiredYear ?: folderMatch?.groupValues?.get(2)?.toIntOrNull()

        // Seasons from `Season NN` subfolders…
        val fromDirs = subDirs.mapNotNull { sub ->
            val number = seasonDirName.find(sub.name)?.groupValues?.get(1)?.toIntOrNull()
                ?: sub.name.filter { it.isDigit() }.toIntOrNull()
                ?: return@mapNotNull null
            val siblings = sub.listFiles()?.map { it.name } ?: return@mapNotNull null
            val episodes = sub.listFiles()!!.filter { isVideo(it) }
                .mapNotNull { video -> parseEpisode(video, siblings, title) }
                .sortedBy { it.number }
            if (episodes.isEmpty()) null else Season(number, episodes)
        }
        // …and virtual seasons from episode-named files sitting loose in the show
        // folder itself: `s02e01.mkv` belongs to season 2 whether or not a folder says so.
        val fromLoose = files.filter { isVideo(it) }
            .mapNotNull { video -> parseEpisode(video, files.map { f -> f.name }, title)?.let { refSeason(video) to it } }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (number, episodes) -> if (number == null) null else Season(number, episodes.sortedBy { it.number }) }

        val seasons = (fromDirs + fromLoose)
            .groupBy { it.number }
            .map { (number, list) -> Season(number, list.flatMap { it.episodes }.sortedBy { it.number }) }
            .sortedBy { it.number }

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

    private fun parseEpisode(video: File, siblingNames: List<String>, showTitle: String? = null): Episode? {
        val nfo = sidecarNfo(video, siblingNames)?.readTextSafe()
            ?.let { runCatching { parseEpisodeNfo(it) }.getOrNull() }
        val ref: EpisodeRef? = episodeRefFromName(video.name)
        if (nfo?.episode == null && ref == null) return null

        val stem = video.nameWithoutExtension
        val thumb = siblingNames.firstOrNull { it.equals("$stem-thumb.jpg", ignoreCase = true) }
            ?: siblingNames.firstOrNull { it.equals("$stem-thumb.png", ignoreCase = true) }

        return Episode(
            number = nfo?.episode ?: ref!!.episode,
            title = nfo?.title ?: titleFromName(stem, showTitle),
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

    private fun refSeason(video: File): Int? = episodeRefFromName(video.name)?.season

    /**
     * `Some.Show.S01E01.Some.Title.1080p.WEB-DL.x264-GRP.mkv` without an NFO:
     * the episode title is what sits between the episode token and the first
     * piece of release junk; the show's own name is stripped from the front.
     */
    private fun titleFromName(stem: String, showTitle: String? = null): String? {
        val match = episodeToken.find(stem)
        var rest = if (match != null) stem.substring(match.range.last + 1) else stem
        junkToken.find(rest)?.let { rest = rest.substring(0, it.range.first) }
        var cleaned = cleanSeparators(rest)
        if (showTitle != null && cleaned.startsWith(showTitle, ignoreCase = true)) {
            cleaned = cleanSeparators(cleaned.substring(showTitle.length))
        }
        return cleaned.ifEmpty { null }
    }

    private fun File.readTextSafe(): String? = runCatching { readText() }.getOrNull()
}
