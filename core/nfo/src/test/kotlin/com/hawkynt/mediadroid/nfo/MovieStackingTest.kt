package com.hawkynt.mediadroid.nfo

import org.junit.Assert.assertEquals
import org.junit.Test

class MovieStackingTest {

    @Test
    fun cdAndPartNumbersStackIntoOneMovieInOrder() {
        val stacks = MovieStacking.stack(listOf("Heat.CD2.avi", "Heat.CD1.avi", "Up (2009).mkv"))
        assertEquals(listOf(Stack("Heat", listOf("Heat.CD1.avi", "Heat.CD2.avi"))), stacks)
    }

    @Test
    fun everyKodiVolumeWordCounts() {
        listOf("cd", "dvd", "part", "pt", "disk", "disc").forEach { word ->
            val names = listOf("Movie - ${word}1.mkv", "Movie - ${word}2.mkv")
            assertEquals(word, listOf(Stack("Movie", names)), MovieStacking.stack(names))
        }
    }

    @Test
    fun letteredVolumesStackToo() {
        val names = listOf("Movie part b.mkv", "Movie part a.mkv")
        assertEquals(listOf(Stack("Movie", names.reversed())), MovieStacking.stack(names))
    }

    @Test
    fun aSingleVolumeOrMismatchedTitlesAreNoStack() {
        assertEquals(emptyList<Stack>(), MovieStacking.stack(listOf("Heat.CD1.avi")))
        assertEquals(emptyList<Stack>(), MovieStacking.stack(listOf("Heat.CD1.avi", "Ronin.CD2.avi")))
        assertEquals(emptyList<Stack>(), MovieStacking.stack(listOf("Heat.CD1.avi", "Heat.CD1.mkv")))
    }
}
