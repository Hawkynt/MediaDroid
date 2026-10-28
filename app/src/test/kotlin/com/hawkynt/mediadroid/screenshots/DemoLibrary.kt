package com.hawkynt.mediadroid.screenshots

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * The demo library the README screenshots show: a media folder laid out the way a collector keeps one —
 * Kodi NFOs, `folder.jpg` posters, fanart, season covers and episode thumbs — with invented titles and
 * posters painted here from each title, so every run draws the same pictures and nothing is anybody's
 * artwork. The app reads it with its real scanner; the videos are empty placeholders.
 */
object DemoLibrary {

    data class DemoMovie(
        val title: String,
        val year: Int?,
        val genres: List<String>,
        val certification: String?,
        val plot: String,
        val audio: List<String> = listOf("eng"),
        val subtitles: List<String> = emptyList(),
        val watched: Boolean = false,
        val withNfo: Boolean = true,
    )

    data class DemoShow(
        val title: String,
        val year: Int,
        val genres: List<String>,
        val certification: String,
        val plot: String,
        val seasons: List<List<String>>,
        val watchedEpisodes: Int = 0,
    )

    val movies = listOf(
        DemoMovie(
            "Robots in the Garden", 2023, listOf("Animation", "Family"), "FSK 0",
            "Two garden robots discover that the strangest weed in the flower bed can talk — and it wants to see the sea.",
            audio = listOf("eng", "ger"), subtitles = listOf("eng", "ger"),
        ),
        DemoMovie(
            "The Clockmaker's Daughter", 2016, listOf("Drama", "Mystery"), "FSK 12",
            "In a town where every clock stopped at the same minute, a girl inherits the workshop that can start them again.",
            audio = listOf("eng", "fre"), subtitles = listOf("eng"), watched = true,
        ),
        DemoMovie(
            "Paper Planes over Paris", 2021, listOf("Comedy", "Romance"), "FSK 6",
            "A courier and a florist trade notes folded into paper planes across the rooftops of the city.",
            audio = listOf("fre", "eng"),
        ),
        DemoMovie(
            "Last Train to Juniper", 2009, listOf("Western"), "FSK 16",
            "The final run of the desert line carries a sheriff, a card player and a crate nobody admits to loading.",
        ),
        DemoMovie(
            "Northern Lights", 2019, listOf("Documentary", "Nature"), "FSK 0",
            "A winter above the Arctic circle, filmed from the first snow to the last aurora.",
            audio = listOf("eng", "nor"),
        ),
        DemoMovie(
            "Deep Blue Silence", 2014, listOf("Thriller"), "FSK 16",
            "A research submarine loses contact with the surface — and then something answers its sonar.",
            audio = listOf("eng", "ger", "spa"), subtitles = listOf("eng", "ger", "spa"),
        ),
        DemoMovie(
            "The Great Pancake Race", 2018, listOf("Animation", "Comedy"), "FSK 0",
            "The village's yearly pancake race goes wrong when the winning recipe goes missing the night before.",
            watched = true,
        ),
        DemoMovie(
            "Midnight at the Observatory", 1998, listOf("Science Fiction"), "FSK 12",
            "An astronomer on the night shift receives a message that was sent forty years from now.",
        ),
        DemoMovie(
            "Harbour Lights", null, emptyList(), null, "", withNfo = false,
        ),
    )

    val shows = listOf(
        DemoShow(
            "Starlight Bakery", 2020, listOf("Comedy", "Family"), "TV-G",
            "A small-town bakery opens at midnight, and its customers are not always from this town.",
            seasons = listOf(
                listOf("Grand Opening", "The Midnight Rush", "Sourdough Secrets", "Flour Power", "Rise and Shine",
                    "Icing on the Cake"),
                listOf("Back in Business", "The Royal Order", "Crumbs", "A Recipe for Trouble", "The Big Bake-Off",
                    "Closing Time"),
            ),
            watchedEpisodes = 3,
        ),
        DemoShow(
            "The Explorers' Club", 2017, listOf("Adventure", "Documentary"), "TV-PG",
            "Four friends with one rule: every weekend, somewhere none of them has been.",
            seasons = listOf(
                listOf("The Map", "Into the Caves", "River Crossing", "Summit Fever"),
                listOf("North", "Lost and Found", "The Island", "Home Again"),
            ),
        ),
        DemoShow(
            "Tiny Dragons", 2022, listOf("Animation", "Fantasy"), "TV-Y7",
            "A dragon the size of a teacup tries to prove she belongs among the big ones.",
            seasons = listOf(listOf("Hatchling", "First Flight", "The Ember Festival", "Sky Race", "A Dragon's Promise")),
        ),
    )

    /** Writes the library under [root]: `Movies/` and `TV Shows/`, as the app expects them. */
    fun write(root: File) {
        val moviesDir = File(root, "Movies").apply { mkdirs() }
        for (movie in movies) {
            val name = movie.year?.let { "${movie.title} ($it)" } ?: movie.title
            val dir = File(moviesDir, name).apply { mkdirs() }
            File(dir, "$name.mkv").writeBytes(ByteArray(0))
            poster(File(dir, "folder.jpg"), movie.title, movie.year)
            backdrop(File(dir, "fanart.jpg"), movie.title)
            if (movie.withNfo) File(dir, "movie.nfo").writeText(movieNfo(movie))
        }

        val showsDir = File(root, "TV Shows").apply { mkdirs() }
        for (show in shows) {
            val dir = File(showsDir, "${show.title} (${show.year})").apply { mkdirs() }
            poster(File(dir, "folder.jpg"), show.title, show.year)
            backdrop(File(dir, "fanart.jpg"), show.title)
            File(dir, "tvshow.nfo").writeText(showNfo(show))
            var episodeIndex = 0
            show.seasons.forEachIndexed { s, titles ->
                val season = s + 1
                val seasonDir = File(dir, "Season %02d".format(season)).apply { mkdirs() }
                poster(File(seasonDir, "folder.jpg"), show.title, null, caption = "Season $season")
                File(seasonDir, "season.nfo").writeText(
                    "<season><plot>Season $season of ${xml(show.title)}: new faces, old secrets, " +
                        "and one more night than anyone planned.</plot></season>",
                )
                titles.forEachIndexed { e, title ->
                    val stem = "S%02dE%02d - %s".format(season, e + 1, title)
                    File(seasonDir, "$stem.mkv").writeBytes(ByteArray(0))
                    thumb(File(seasonDir, "$stem-thumb.jpg"), show.title, "S%02d · E%02d".format(season, e + 1))
                    val watched = episodeIndex++ < show.watchedEpisodes
                    File(seasonDir, "$stem.nfo").writeText(episodeNfo(title, season, e + 1, show.year, watched))
                }
            }
        }
    }

    private fun movieNfo(m: DemoMovie): String = buildString {
        append("<movie><title>${xml(m.title)}</title>")
        m.year?.let { append("<year>$it</year>") }
        m.genres.forEach { append("<genre>${xml(it)}</genre>") }
        m.certification?.let { append("<mpaa>$it</mpaa>") }
        append("<plot>${xml(m.plot)}</plot><runtime>${90 + m.title.length}</runtime>")
        if (m.watched) append("<playcount>1</playcount>")
        append(streamDetails(m.audio, m.subtitles))
        append("</movie>")
    }

    private fun showNfo(s: DemoShow): String = buildString {
        append("<tvshow><title>${xml(s.title)}</title><premiered>${s.year}-09-01</premiered>")
        s.genres.forEach { append("<genre>${xml(it)}</genre>") }
        append("<mpaa>${s.certification}</mpaa><plot>${xml(s.plot)}</plot></tvshow>")
    }

    private fun episodeNfo(title: String, season: Int, episode: Int, year: Int, watched: Boolean): String =
        "<episodedetails><title>${xml(title)}</title><season>$season</season><episode>$episode</episode>" +
            "<plot>${xml(title)}: a quiet start turns into a long night, and nobody leaves the way they came in.</plot>" +
            "<aired>${year + season - 1}-%02d-%02d</aired>".format(1 + episode % 12, 1 + episode * 2) +
            (if (watched) "<playcount>1</playcount>" else "") +
            streamDetails(listOf("eng", "ger"), listOf("eng")) +
            "</episodedetails>"

    private fun streamDetails(audio: List<String>, subtitles: List<String>): String =
        "<fileinfo><streamdetails>" +
            audio.joinToString("") { "<audio><language>$it</language></audio>" } +
            subtitles.joinToString("") { "<subtitle><language>$it</language></subtitle>" } +
            "</streamdetails></fileinfo>"

    private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace("'", "&apos;")

    // ----- painted art ------------------------------------------------------------------------------

    /** Two colours and a scattering of shapes, all derived from [seed], so a title always looks the same. */
    private fun paint(width: Int, height: Int, seed: String, draw: (java.awt.Graphics2D, Random) -> Unit): BufferedImage {
        val random = Random(seed.hashCode())
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val hue = random.nextFloat()
        val top = Color.getHSBColor(hue, 0.55f, 0.35f)
        val bottom = Color.getHSBColor((hue + 0.12f) % 1f, 0.75f, 0.85f)
        g.paint = GradientPaint(0f, 0f, top, 0f, height.toFloat(), bottom)
        g.fillRect(0, 0, width, height)
        repeat(7) {
            val size = (width * (0.15f + random.nextFloat() * 0.5f)).toInt()
            g.color = Color(255, 255, 255, 18 + random.nextInt(40))
            if (random.nextBoolean()) {
                g.fillOval(random.nextInt(width) - size / 2, random.nextInt(height) - size / 2, size, size)
            } else {
                g.stroke = BasicStroke(width / 60f)
                g.drawOval(random.nextInt(width) - size / 2, random.nextInt(height) - size / 2, size, size)
            }
        }
        draw(g, random)
        g.dispose()
        return image
    }

    private fun poster(file: File, title: String, year: Int?, caption: String? = null) {
        val image = paint(400, 600, title + (caption ?: "")) { g, _ ->
            drawCentred(g, title.uppercase(), 400, 360, Font.BOLD, 40, maxWidth = 340)
            val sub = caption ?: year?.toString()
            if (sub != null) drawCentred(g, sub, 400, 540, Font.PLAIN, 28, maxWidth = 340)
        }
        ImageIO.write(image, "jpg", file)
    }

    private fun backdrop(file: File, title: String) {
        ImageIO.write(paint(1280, 720, "$title backdrop") { _, _ -> }, "jpg", file)
    }

    private fun thumb(file: File, title: String, caption: String) {
        val image = paint(1280, 720, "$title $caption") { g, _ -> drawCentred(g, caption, 1280, 560, Font.BOLD, 72, 1160) }
        ImageIO.write(image, "jpg", file)
    }

    /** [text] centred at [baseline], wrapped onto several lines when it is wider than [maxWidth]. */
    private fun drawCentred(
        g: java.awt.Graphics2D,
        text: String,
        width: Int,
        baseline: Int,
        style: Int,
        size: Int,
        maxWidth: Int,
    ) {
        g.font = Font(Font.SANS_SERIF, style, size)
        val metrics = g.fontMetrics
        val lines = ArrayList<String>()
        var line = ""
        for (word in text.split(" ")) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (metrics.stringWidth(candidate) > maxWidth && line.isNotEmpty()) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        lines += line
        var y = baseline - (lines.size - 1) * metrics.height / 2
        for (l in lines) {
            val x = (width - metrics.stringWidth(l)) / 2
            g.color = Color(0, 0, 0, 120)
            g.drawString(l, x + 3, y + 3)
            g.color = Color.WHITE
            g.drawString(l, x, y)
            y += metrics.height
        }
    }
}
