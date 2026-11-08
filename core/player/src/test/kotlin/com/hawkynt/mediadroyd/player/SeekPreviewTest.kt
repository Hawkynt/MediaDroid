package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekPreviewTest {
    private val minute = 60_000L
    private val film = 120 * minute

    @Test
    fun aLongFilmIsCutIntoAtMostThreeHundredStepsOfWholeSeconds() {
        assertEquals(24_000L, SeekPreview.stepMs(film))
        assertTrue(film / SeekPreview.stepMs(film) <= SeekPreview.MOST_PICTURES)
        assertEquals(0L, SeekPreview.stepMs(91 * minute) % 1_000L)
        assertTrue(91 * minute / SeekPreview.stepMs(91 * minute) <= SeekPreview.MOST_PICTURES)
    }

    @Test
    fun shortClipsStillStepAtLeastTwoSecondsAndEndlessOnesAtMostAMinute() {
        assertEquals(2_000L, SeekPreview.stepMs(30_000L))
        assertEquals(60_000L, SeekPreview.stepMs(100 * film))
    }

    @Test
    fun anUnknownLengthStepsTenSeconds() {
        assertEquals(10_000L, SeekPreview.stepMs(0))
        assertEquals(10_000L, SeekPreview.stepMs(-1))
    }

    @Test
    fun nearbyMomentsShareOnePictureAndTheNextStepGetsItsOwn() {
        val step = SeekPreview.stepMs(film)
        assertEquals(SeekPreview.bucketOf(step, film), SeekPreview.bucketOf(2 * step - 1, film))
        assertNotEquals(SeekPreview.bucketOf(step - 1, film), SeekPreview.bucketOf(step, film))
    }

    @Test
    fun positionsOutsideTheFilmFallOnItsFirstAndLastStep() {
        assertEquals(0, SeekPreview.bucketOf(-5_000, film))
        assertEquals(SeekPreview.bucketOf(film - 1, film), SeekPreview.bucketOf(film + 60_000, film))
    }

    @Test
    fun thePictureIsTakenInTheMiddleOfItsStep() {
        assertEquals(12_000L, SeekPreview.frameTimeMs(0, film))
        assertEquals(36_000L, SeekPreview.frameTimeMs(1, film))
    }

    @Test
    fun theLastStepsPictureStaysInsideTheFilm() {
        val clip = 25_000L
        val last = SeekPreview.bucketOf(clip, clip)
        val at = SeekPreview.frameTimeMs(last, clip)
        assertTrue(at in 1 until clip)
    }

    @Test
    fun whileThePictureLoadsTheNearestOneAlreadyKeptStandsIn() {
        assertEquals(9, SeekPreview.standIn(10, listOf(2, 9, 13)))
        assertEquals(10, SeekPreview.standIn(10, listOf(10, 11)))
        // Equally near: the earlier picture, the one already passed.
        assertEquals(8, SeekPreview.standIn(10, listOf(12, 8)))
    }

    @Test
    fun aPictureFarAwayDoesNotStandIn() {
        assertNull(SeekPreview.standIn(10, listOf(2, 30)))
        assertNull(SeekPreview.standIn(10, emptyList()))
    }

    @Test
    fun theFileKeyChangesWithTheFileAndNotOtherwise() {
        val key = SeekPreview.fileKey("smb://nas/Films/Heat (1995).mkv", 1_700_000_000_000L)
        assertEquals(key, SeekPreview.fileKey("smb://nas/Films/Heat (1995).mkv", 1_700_000_000_000L))
        assertNotEquals(key, SeekPreview.fileKey("smb://nas/Films/Heat (1995).mkv", 1_700_000_000_001L))
        assertNotEquals(key, SeekPreview.fileKey("smb://nas/Films/Ronin (1998).mkv", 1_700_000_000_000L))
        // Safe as a folder name on any file system.
        assertTrue(key.matches(Regex("[0-9a-f]{40}")))
    }

    @Test
    fun aPicturesFileIsNamedByTheMomentItShows() {
        assertEquals("36000.jpg", SeekPreview.pictureName(36_000L))
    }

    @Test
    fun theDiskCacheLetsGoOfTheLongestUnusedPicturesUntilItFitsItsBudget() {
        val kept = listOf(
            SeekPreview.Kept("a", bytes = 40, usedAt = 3),
            SeekPreview.Kept("b", bytes = 40, usedAt = 1),
            SeekPreview.Kept("c", bytes = 40, usedAt = 2),
            SeekPreview.Kept("d", bytes = 40, usedAt = 4),
        )
        assertEquals(listOf("b", "c"), SeekPreview.toLetGo(kept, budgetBytes = 100))
        assertEquals(emptyList<String>(), SeekPreview.toLetGo(kept, budgetBytes = 160))
    }
}
