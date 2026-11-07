package com.hawkynt.mediadroid.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayStartTest {

    private val queue = listOf("e1", "e2", "e3", "e4")

    @Test
    fun `the video asked for is where it starts`() {
        assertEquals(2, PlayStart.indexOf(queue, "e3", watched = { true }, resumed = { false }))
    }

    @Test
    fun `a show resumes at its first episode not marked watched, not at a watched one with a resume point`() {
        val watched = setOf("e1", "e2")
        assertEquals(2, PlayStart.indexOf(queue, null, watched = { it in watched }, resumed = { it == "e1" }))
    }

    @Test
    fun `everything watched, it picks up where a resume point is, else at the start`() {
        assertEquals(1, PlayStart.indexOf(queue, null, watched = { true }, resumed = { it == "e2" }))
        assertEquals(0, PlayStart.indexOf(queue, null, watched = { true }, resumed = { false }))
        assertEquals(0, PlayStart.indexOf(emptyList(), null, watched = { true }, resumed = { false }))
    }

    @Test
    fun `a video asked for that is not in the queue is no reason to start elsewhere than the rules say`() {
        assertEquals(0, PlayStart.indexOf(queue, "other", watched = { false }, resumed = { false }))
    }
}
