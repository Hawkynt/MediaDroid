package com.hawkynt.mediadroid.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Chapters from MP4, QuickTime and Ogg files that FFmpeg wrote (`src/test/make-chapter-fixtures.py`); the
 * expected titles and starts are what FFmpeg reads back from the same files.
 */
class ChaptersTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val expected = listOf("Cold Open" to 0L, "Intro" to 2000L, "Episode" to 5000L, "End Credits" to 9000L)

    private fun fixture(name: String): File =
        File(folder.root, name).apply {
            writeBytes(ChaptersTest::class.java.getResourceAsStream("/chapters/$name")!!.readBytes())
        }

    private fun check(name: String) {
        val chapters = Chapters.read(fixture(name))
        assertEquals(name, expected, chapters.map { it.title to it.startMs })
        // A chapter without its own end runs to the start of the next one.
        assertEquals(name, listOf(2000L, 5000L, 9000L), chapters.dropLast(1).map { it.endMs })
    }

    @Test
    fun neroChaptersInAnMp4() = check("nero-only.mp4")

    @Test
    fun quickTimeChapterTrackWithTheMovieHeaderFirst() = check("quicktime-only.mov")

    @Test
    fun mp4WithBothFormsAndTheMovieHeaderAfterTheMedia() = check("both.mp4")

    @Test
    fun oggVorbisCommentChapters() = check("chapters.ogg")

    @Test
    fun skipRulesFindTheIntroAndCreditsInAnMp4() {
        val chapters = Chapters.read(fixture("both.mp4"))
        assertEquals(2000L..5000L, SkipRules.introSpanMs(chapters, null))
        assertEquals(9000L, SkipRules.creditsStartMs(chapters, null))
    }

    @Test
    fun filesWithoutChaptersOrUnknownContainersHaveNone() {
        assertEquals(emptyList<Chapter>(), Chapters.read(File(folder.root, "missing.mp4")))
        val ts = File(folder.root, "clip.ts").apply { writeBytes(ByteArray(188 * 4)) }
        val junk = File(folder.root, "junk.mp4").apply { writeText("not a movie") }
        assertEquals(emptyList<Chapter>(), Chapters.read(ts))
        assertEquals(emptyList<Chapter>(), Chapters.read(junk))
    }
}
