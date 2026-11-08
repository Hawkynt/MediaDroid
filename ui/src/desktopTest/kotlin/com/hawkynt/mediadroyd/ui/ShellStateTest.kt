package com.hawkynt.mediadroyd.ui

import com.hawkynt.mediadroyd.library.Episode
import com.hawkynt.mediadroyd.library.HomeRow
import com.hawkynt.mediadroyd.library.Library
import com.hawkynt.mediadroyd.library.LibrarySection
import com.hawkynt.mediadroyd.library.Movie
import com.hawkynt.mediadroyd.library.Season
import com.hawkynt.mediadroyd.library.Show
import com.hawkynt.mediadroyd.state.ResumeInfo
import com.hawkynt.mediadroyd.state.StateFile
import com.hawkynt.mediadroyd.state.StateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Where the one app shell goes, and what it plays, the same for both apps.
class ShellStateTest {

    private val store = StateStore(
        object : StateFile {
            override fun read(): String? = null

            override fun write(text: String) = Unit
        },
    )
    private val show = Show(
        title = "Starlight Bakery",
        path = "/tv/sb",
        seasons = listOf(
            Season(
                1,
                listOf(
                    Episode(1, "Grand Opening", videoPath = "/tv/sb/e1.mkv"),
                    Episode(2, "Flour Power", videoPath = "/tv/sb/e2.mkv"),
                ),
            ),
        ),
    )
    private val film = Movie(title = "Deep Blue Silence", year = 2014, path = "/m/dbs", videoPath = "/m/dbs/dbs.mkv")
    private val library = Library(listOf(film), listOf(show))

    private fun state() = ShellState(store)

    @Test
    fun `a wall browsed is where Back from elsewhere leads, and its section groups the places`() {
        val s = state()
        s.navigate(Screen.Browse(LibrarySection.SHOWS))
        s.navigate(Screen.Search)
        assertEquals(LibrarySection.SHOWS, s.placeSection)
        s.back()
        assertEquals(Screen.Browse(LibrarySection.SHOWS), s.screen)
        s.navigate(Screen.Browse(LibrarySection.SHOWS, HomeRow.entries.first()))
        s.back()
        assertEquals(Screen.Browse(LibrarySection.SHOWS), s.screen)
    }

    @Test
    fun `a title's details go back to where they were opened`() {
        val s = state()
        s.navigate(Screen.Search)
        s.openDetails(show)
        assertEquals(Screen.Details(show), s.screen)
        s.back()
        assertEquals(Screen.Search, s.screen)
    }

    @Test
    fun `a show plays on from the episode asked, episodes after it as binge-watching says`() {
        store.data.settings.bingeWatching = false
        val player = state().playerFor(show, "/tv/sb/e2.mkv")!!
        assertEquals(1, player.startIndex)
        assertEquals(false, player.autoAdvance)
        assertEquals(listOf("/tv/sb/e1.mkv", "/tv/sb/e2.mkv"), player.queue.map { it.videoPath })
    }

    @Test
    fun `a film's parts always run on`() {
        val player = state().playerFor(film, null)!!
        assertTrue(player.autoAdvance)
        assertEquals("/m/dbs/dbs.mkv", player.queue[player.startIndex].videoPath)
    }

    @Test
    fun `the video played last is offered to resume, with its episode named, until there is none`() {
        val s = state()
        assertNull(s.resumeSpot(library) {})
        store.data.lastPlayed = "/tv/sb/e2.mkv"
        store.data.resume["/tv/sb/e2.mkv"] = ResumeInfo(positionMs = 300_000, durationMs = 1_200_000)
        var resumed: Screen.Player? = null
        val spot = s.resumeSpot(library) { resumed = it }!!
        assertEquals("Starlight Bakery", spot.title)
        assertEquals("S01 · E02 · Flour Power", spot.subtitle)
        assertEquals(0.25f, spot.progress)
        spot.onResume()
        assertEquals(1, resumed!!.startIndex)
    }
}
