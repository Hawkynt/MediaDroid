package com.hawkynt.mediadroid.nfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The MPlayer EDL format as Kodi documents it (kodi.wiki "Edit decision list"); the examples are the wiki's. */
class EditDecisionListTest {

    private fun edit(startMs: Long, endMs: Long, action: Int) = EdlEdit(startMs, endMs, action)

    @Test
    fun secondsAsTheWikiWritesThem() {
        val edits = EditDecisionList.parse(
            """
            5.3   7.1    0
            15    16.7   1
            420   822    3
            1     255.3  2
            720.1        2
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                edit(5300, 7100, 0),
                edit(15000, 16700, 1),
                edit(420000, 822000, 3),
                edit(255300, 255300, 2),
                edit(720100, 720100, 2),
            ),
            edits,
        )
    }

    @Test
    fun clockTimes() {
        val edits = EditDecisionList.parse(
            "5.3     7.1     0\n7:00    13:42   3\n1:02:03.5 1:02:10 3\n12:00.1         2",
        )
        assertEquals(
            listOf(
                edit(5300, 7100, 0),
                edit(420000, 822000, 3),
                edit(3723500, 3730000, 3),
                edit(720100, 720100, 2),
            ),
            edits,
        )
    }

    @Test
    fun frameNumbersNeedAFrameRateAndAreLeftOutWithoutOne() {
        assertEquals(emptyList<EdlEdit>(), EditDecisionList.parse("#127   #170    0\n#10080 #19728  3"))
    }

    @Test
    fun commentsBlankLinesAndGarbageAreSkipped() {
        val edits = EditDecisionList.parse("## written by Intro Skipper\n\n0 x 3\nnonsense\n 30.5\t95.25\t3 \r\n")
        assertEquals(listOf(edit(30500, 95250, 3)), edits)
    }

    @Test
    fun introSkipperOutputWithTwoDecimals() {
        assertEquals(listOf(edit(1120, 5650, 3)), EditDecisionList.parse("1.12 5.65 3"))
    }

    @Test
    fun theFirstSkipNearTheStartIsTheIntroTheLastAfterItTheCredits() {
        val skips = EditDecisionList.skips(
            listOf(edit(15000, 16700, 1), edit(62000, 152000, 3), edit(600000, 610000, 2), edit(1250000, 1320000, 3)),
        )
        assertEquals(TimeRange(62000, 152000), skips.intro)
        assertEquals(TimeRange(1250000, 1320000), skips.credits)
    }

    @Test
    fun cutsAndIntroSkipperIntroEditsSkipToo() {
        val skips = EditDecisionList.skips(listOf(edit(30000, 90000, 4), edit(1300000, 1400000, 0)))
        assertEquals(TimeRange(30000, 90000), skips.intro)
        assertEquals(TimeRange(1300000, 1400000), skips.credits)
    }

    @Test
    fun aLoneSkipLateInTheVideoIsTheCredits() {
        val skips = EditDecisionList.skips(listOf(edit(6060000, 6300000, 3)))
        assertNull(skips.intro)
        assertEquals(TimeRange(6060000, 6300000), skips.credits)
    }

    @Test
    fun mutesAndSceneMarkersSkipNothing() {
        val skips = EditDecisionList.skips(listOf(edit(15000, 16700, 1), edit(255300, 255300, 2)))
        assertNull(skips.intro)
        assertNull(skips.credits)
    }

    @Test
    fun theEdlBesideAVideoIsNamedAfterIt() {
        assertEquals("Death Note - S01E12 - Love.edl", EditDecisionList.fileNameFor("Death Note - S01E12 - Love.mkv"))
        assertEquals("file.edl", EditDecisionList.fileNameFor("file.rm"))
    }
}
