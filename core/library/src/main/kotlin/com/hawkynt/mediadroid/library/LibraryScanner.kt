package com.hawkynt.mediadroid.library

import com.hawkynt.mediadroid.imaging.ImageFormats
import com.hawkynt.mediadroid.nfo.ArtKind
import com.hawkynt.mediadroid.nfo.EditDecisionList
import com.hawkynt.mediadroid.nfo.EdlSkips
import com.hawkynt.mediadroid.nfo.EpisodeRef
import com.hawkynt.mediadroid.nfo.bareEpisodeFromName
import com.hawkynt.mediadroid.nfo.MovieStacking
import com.hawkynt.mediadroid.nfo.ReleaseName
import com.hawkynt.mediadroid.nfo.episodeRefFromName
import com.hawkynt.mediadroid.nfo.findArt
import com.hawkynt.mediadroid.nfo.minAgeOf
import com.hawkynt.mediadroid.nfo.parseEpisodeNfo
import com.hawkynt.mediadroid.nfo.parseMovieNfo
import com.hawkynt.mediadroid.nfo.parseShowNfo
import java.io.File

/**
 * Turns a library root folder into a [Library] by walking it the way a collector
 * organizes disks: a folder with a `tvshow.nfo` or `Season NN` subfolders is a
 * show, a folder with a `movie.nfo`, a single video or one multi-part movie is a movie, and
 * any other loose video that is not an episode is a movie too. Trailers and samples are skipped.
 *
 * Everything is [java.io.File] so the whole scan runs — and is tested — as plain
 * JVM code, no Android, no storage framework.
 */
object LibraryScanner {

    /**
     * Every container family Media3's extractors read (they sniff the content, so the extension
     * only has to say "video"): MP4/QuickTime, Matroska/WebM, AVI, MPEG-PS, MPEG-TS, Ogg and FLV.
     */
    private val videoExtensions = setOf(
        "mp4", "m4v", "mov", "qt", "3gp", "3g2", "f4v",
        "mkv", "mk3d", "webm",
        "avi", "divx", "xvid",
        "mpg", "mpeg", "mpe", "mp2", "m2p", "vob",
        "ts", "m2ts", "mts", "m2t", "tp", "trp",
        "ogv", "ogm",
        "flv",
    )
    private val seasonDirName = Regex("(?i)^season[ ._-]*(\\d+)$")
    private val episodeToken = Regex("(?i)\\b(?:s\\d{1,2}[ ._-]?e\\d{1,3}|\\d{1,2}x(?!26[456]\\b)\\d{1,3})\\b")
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
                Season(
                    number,
                    list.flatMap { it.episodes }.distinctBy { it.videoPath }.sortedBy { it.number },
                    list.firstNotNullOfOrNull { it.posterPath },
                )
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
            minAge = group.firstNotNullOfOrNull { it.minAge },
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
        // Multi-part movies lying loose (`Heat.CD1.avi`, `Heat.CD2.avi`) are one movie each.
        val looseVideos = files.filter { isVideo(it) && !isExtra(it) && episodeRefFromName(it.name) == null }
        val stacks = MovieStacking.stack(looseVideos.map { it.name })
        val stacked = stacks.flatMap { it.parts }.toSet()
        for (stack in stacks) {
            val parts = stack.parts.map { name -> looseVideos.first { it.name == name } }
            parseLooseMovie(parts.first(), files, parts, stack.title)?.let { movies.add(it) }
        }
        for (file in looseVideos) {
            if (file.name !in stacked) parseLooseMovie(file, files)?.let { movies.add(it) }
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
        val folderName = ReleaseName.parse(dir.name)
        val names = files.map { it.name }
        val art = findArt(names)
        videos.groupBy { showKeyFromName(it.nameWithoutExtension) ?: "" }.forEach { (key, group) ->
            val title = key.ifEmpty { folderName.title }
            val seasons = group.mapNotNull { video ->
                parseEpisode(video, names)?.let { ep -> episodeRefFromName(video.name)!!.season to ep }
            }.groupBy({ it.first }, { it.second })
                .map { (number, episodes) ->
                    Season(number, episodes.sortedBy { it.number }, seasonPoster(dir, names, number))
                }
                .sortedBy { it.number }
            if (seasons.isEmpty()) return@forEach
            shows.add(
                Show(
                    title = title,
                    year = folderName.year,
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

    /** Trailers (`<name>-trailer.mkv`, the Kodi convention) and release samples are no movies. */
    private fun isExtra(file: File): Boolean {
        val stem = file.nameWithoutExtension.lowercase()
        return stem.endsWith("-trailer") || stem == "sample" || stem.endsWith("-sample") || stem.endsWith(".sample")
    }

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
        return videos.isNotEmpty() &&
            videos.all { episodeRefFromName(it.name) != null || bareEpisodeFromName(it.name) != null }
    }

    /**
     * A folder is ONE movie when it carries a `movie.nfo`, holds a single video that
     * is no episode, or holds the parts of one multi-part movie. A folder with several
     * unrelated videos (an `Incoming` dump) is not one movie — each video is handled on its own.
     */
    private fun isMovieDirectory(files: List<File>): Boolean {
        if (files.any { it.name.equals("movie.nfo", ignoreCase = true) }) return true
        val videos = files.filter { isVideo(it) && !isExtra(it) }
        if (videos.size > 1) {
            // A multi-part movie: every video belongs to one stack.
            return MovieStacking.stack(videos.map { it.name }).singleOrNull()?.parts?.size == videos.size
        }
        // One video that is no episode: the folder is that movie.
        val video = videos.singleOrNull() ?: return false
        return episodeRefFromName(video.name) == null && bareEpisodeFromName(video.name) == null
    }

    /** The intro and credits the `<video>.edl` beside [video] marks; nothing when there is none. */
    private fun edlSkips(video: File, names: List<String>): EdlSkips {
        val edl = EditDecisionList.fileNameFor(video.name)
        val hit = names.firstOrNull { it.equals(edl, ignoreCase = true) } ?: return EdlSkips(null, null)
        val text = File(video.parentFile, hit).readTextSafe() ?: return EdlSkips(null, null)
        return EditDecisionList.skips(EditDecisionList.parse(text))
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

        val folderName = ReleaseName.parse(dir.name)
        val title = info?.title ?: looseKey ?: folderName.title
        val year = info?.firstAiredYear ?: folderName.year
        val showNames = files.map { it.name }

        // Seasons from `Season NN` subfolders…
        val fromDirs = subDirs.mapNotNull { sub ->
            val number = seasonDirName.find(sub.name)?.groupValues?.get(1)?.toIntOrNull()
                ?: sub.name.filter { it.isDigit() }.toIntOrNull()
                ?: return@mapNotNull null
            val siblings = sub.listFiles()?.map { it.name } ?: return@mapNotNull null
            val episodes = sub.listFiles()!!.filter { isVideo(it) }
                .mapNotNull { video -> parseEpisode(video, siblings, seasonFolder = number) }
                .sortedBy { it.number }
            val poster = findArt(siblings)[ArtKind.Poster]?.let { File(sub, it).absolutePath }
                ?: seasonPoster(dir, showNames, number)
            if (episodes.isEmpty()) null else Season(number, episodes, poster)
        }
        // …and virtual seasons from episode-named files sitting loose in the show
        // folder itself: `s02e01.mkv` belongs to season 2 whether or not a folder says so,
        // and absolute numbering (`EP002.mkv`, no seasons at all) is season 1.
        val fromLoose = files.filter { isVideo(it) }
            .mapNotNull { video ->
                parseEpisode(video, files.map { f -> f.name }, seasonFolder = 1)?.let { refSeason(video) to it }
            }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (number, episodes) ->
                number?.let { Season(it, episodes.sortedBy { e -> e.number }, seasonPoster(dir, showNames, it)) }
            }

        val seasons = (fromDirs + fromLoose)
            .groupBy { it.number }
            .map { (number, list) ->
                val episodes = list.flatMap { it.episodes }.sortedBy { it.number }
                Season(number, episodes, list.firstNotNullOfOrNull { it.posterPath })
            }
            .sortedBy { it.number }

        val art = findArt(files.map { it.name })
        return Show(
            title = title,
            year = year,
            genres = info?.genres ?: emptyList(),
            path = dir.absolutePath,
            posterPath = art[ArtKind.Poster]?.let { File(dir, it).absolutePath },
            minAge = minAgeOf(info?.mpaa),
            fanartPath = art[ArtKind.Fanart]?.let { File(dir, it).absolutePath },
            plot = info?.plot,
            seasons = seasons,
        )
    }

    /**
     * One episode file. [seasonFolder] is the season of the `Season NN` folder it sits in, which
     * lets a bare `E02 - Title.mkv` count as an episode of that season.
     */
    private fun parseEpisode(video: File, siblingNames: List<String>, seasonFolder: Int? = null): Episode? {
        val nfo = sidecarNfo(video, siblingNames)?.readTextSafe()
            ?.let { runCatching { parseEpisodeNfo(it) }.getOrNull() }
        val ref: EpisodeRef? = episodeRefFromName(video.name)
            ?: seasonFolder?.let { season -> bareEpisodeFromName(video.name)?.let { EpisodeRef(season, it) } }
        if (nfo?.episode == null && ref == null) return null

        val skips = edlSkips(video, siblingNames)
        val stem = video.nameWithoutExtension
        val thumb = siblingNames.firstOrNull {
            it.substringBeforeLast('.').equals("$stem-thumb", ignoreCase = true) && ImageFormats.isImage(it)
        }

        return Episode(
            number = nfo?.episode ?: ref!!.episode,
            title = nfo?.title ?: ReleaseName.episodeTitle(stem),
            firstAired = nfo?.firstAired,
            watched = (nfo?.playcount ?: 0) > 0,
            videoPath = video.absolutePath,
            thumbPath = thumb?.let { File(video.parentFile, it).absolutePath },
            intro = skips.intro,
            credits = skips.credits,
            audioLanguages = nfo?.audioLanguages ?: emptyList(),
            subtitleLanguages = nfo?.subtitleLanguages ?: emptyList(),
        )
    }

    /** `seasonNN-poster.jpg` beside the show (`season-specials-poster` for season 0), the Kodi way. */
    private fun seasonPoster(showDir: File, showNames: List<String>, season: Int): String? {
        val base = if (season == 0) "season-specials-poster" else "season%02d-poster".format(season)
        return showNames.firstOrNull { name ->
            name.substringBeforeLast('.').equals(base, ignoreCase = true) &&
                ImageFormats.isImage(name)
        }?.let { File(showDir, it).absolutePath }
    }

    /** A poster named after the video: `<stem>-poster.jpg` first, then a plain `<stem>.jpg`. */
    private fun stemPoster(stem: String, names: List<String>): String? {
        val lower = stem.lowercase()
        fun image(name: String) = ImageFormats.isImage(name)
        return names.firstOrNull { it.lowercase().startsWith("$lower-poster.") && image(it) }
            ?: names.firstOrNull { it.substringBeforeLast('.').lowercase() == lower && image(it) }
    }

    // ----- movies --------------------------------------------------------------------------------

    private fun parseMovieFolder(dir: File, files: List<File>): Movie? {
        val videos = files.filter { isVideo(it) && !isExtra(it) }
        val stack = MovieStacking.stack(videos.map { it.name }).firstOrNull()
        val parts = stack?.parts?.map { name -> videos.first { it.name == name } }
            ?: listOfNotNull(videos.maxByOrNull { it.length() })
        val video = parts.firstOrNull() ?: return null
        val names = files.map { it.name }

        val nfoFile = files.firstOrNull { it.name.equals("movie.nfo", ignoreCase = true) }
            ?: sidecarNfo(video, names)
        val info = nfoFile?.readTextSafe()?.let { runCatching { parseMovieNfo(it) }.getOrNull() }

        // The folder names the movie; the file only when the folder carries no year but the file does.
        val folderName = ReleaseName.parse(dir.name)
        val videoName = ReleaseName.parse(stack?.title ?: video.nameWithoutExtension)
        val name = if (folderName.year == null && videoName.year != null) videoName else folderName

        val stem = stack?.title ?: video.nameWithoutExtension
        val art = findArt(names)
        val poster = stemPoster(stem, names) ?: art[ArtKind.Poster]
        val fanart = names.firstOrNull { it.lowercase().startsWith("${stem.lowercase()}-fanart.") }
            ?: art[ArtKind.Fanart]
        return Movie(
            title = info?.title ?: name.title,
            year = info?.year ?: name.year ?: folderName.year ?: videoName.year,
            genres = info?.genres ?: emptyList(),
            path = dir.absolutePath,
            posterPath = poster?.let { File(dir, it).absolutePath },
            minAge = minAgeOf(info?.mpaa),
            fanartPath = fanart?.let { File(dir, it).absolutePath },
            videoPath = video.absolutePath,
            plot = info?.plot,
            runtimeMinutes = info?.runtimeMinutes,
            cast = info?.cast?.map { it.name } ?: emptyList(),
            playcount = info?.playcount ?: 0,
            intro = edlSkips(parts.first(), names).intro,
            credits = edlSkips(parts.last(), names).credits,
            parts = parts.map { it.absolutePath },
            audioLanguages = info?.audioLanguages ?: emptyList(),
            subtitleLanguages = info?.subtitleLanguages ?: emptyList(),
        )
    }

    private fun parseLooseMovie(
        video: File,
        siblings: List<File>,
        parts: List<File> = listOf(video),
        stackTitle: String? = null,
    ): Movie? {
        val names = siblings.map { it.name }
        val info = sidecarNfo(video, names)?.readTextSafe()
            ?.let { runCatching { parseMovieNfo(it) }.getOrNull() }
        val name = ReleaseName.parse(stackTitle ?: video.nameWithoutExtension)

        // Per-movie art beside a loose file: `Coco (2017)-poster.jpg` or `Coco (2017).jpg`.
        val stem = stackTitle ?: video.nameWithoutExtension
        val poster = stemPoster(stem, names)
        val fanart = names.firstOrNull { it.lowercase().startsWith("${stem.lowercase()}-fanart.") }

        return Movie(
            title = info?.title ?: name.title,
            year = info?.year ?: name.year,
            genres = info?.genres ?: emptyList(),
            path = video.parentFile.absolutePath,
            posterPath = poster?.let { File(video.parentFile, it).absolutePath },
            minAge = minAgeOf(info?.mpaa),
            fanartPath = fanart?.let { File(video.parentFile, it).absolutePath },
            videoPath = video.absolutePath,
            plot = info?.plot,
            runtimeMinutes = info?.runtimeMinutes,
            cast = info?.cast?.map { it.name } ?: emptyList(),
            playcount = info?.playcount ?: 0,
            intro = edlSkips(parts.first(), names).intro,
            credits = edlSkips(parts.last(), names).credits,
            parts = parts.map { it.absolutePath },
            audioLanguages = info?.audioLanguages ?: emptyList(),
            subtitleLanguages = info?.subtitleLanguages ?: emptyList(),
        )
    }

    /** The season a loose file in a show folder belongs to; a bare `EP002` (absolute numbering) is season 1. */
    private fun refSeason(video: File): Int? =
        episodeRefFromName(video.name)?.season ?: bareEpisodeFromName(video.name)?.let { 1 }

    private fun File.readTextSafe(): String? = runCatching { readText() }.getOrNull()
}
