package com.hawkynt.mediadroid.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.EventQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MediaControlsTest {

    private val episode =
        NowPlaying("The Pilot", playing = true, hasPrevious = false, hasNext = true, durationMs = 1_500)

    @Test
    fun `the open player gets the commands on the window's thread, and what it shows reaches the system`() {
        val shown = LinkedBlockingQueue<Any>()
        var send: (MediaCommand) -> Unit = {}
        val keys = MediaKeys { onCommand ->
            send = onCommand
            object : MediaControls {
                override fun show(now: NowPlaying?) {
                    shown += now ?: "nothing"
                }

                override fun close() {
                    shown += "closed"
                }
            }
        }
        val got = LinkedBlockingQueue<Pair<MediaCommand, Boolean>>()
        keys.onCommand = { got += it to EventQueue.isDispatchThread() }
        keys.show(episode)
        assertEquals(episode, shown.poll(2, TimeUnit.SECONDS))
        send(MediaCommand.NEXT)
        assertEquals(MediaCommand.NEXT to true, got.poll(2, TimeUnit.SECONDS))
        keys.show(null)
        keys.close()
        assertEquals("nothing", shown.poll(2, TimeUnit.SECONDS))
        assertEquals("closed", shown.poll(2, TimeUnit.SECONDS))
    }

    @Test
    fun `MPRIS's methods are the player's commands`() {
        assertEquals(MediaCommand.PLAY, Mpris.command("Play"))
        assertEquals(MediaCommand.PAUSE, Mpris.command("Pause"))
        assertEquals(MediaCommand.PLAY_PAUSE, Mpris.command("PlayPause"))
        assertEquals(MediaCommand.STOP, Mpris.command("Stop"))
        assertEquals(MediaCommand.NEXT, Mpris.command("Next"))
        assertEquals(MediaCommand.PREVIOUS, Mpris.command("Previous"))
        assertNull(Mpris.command("Raise"))
    }

    @Test
    fun `MPRIS tells playing, paused and stopped apart`() {
        assertEquals("Playing", Mpris.status(episode))
        assertEquals("Paused", Mpris.status(episode.copy(playing = false)))
        assertEquals("Stopped", Mpris.status(null))
    }

    @Test
    fun `MPRIS's metadata carries a track id that is an object path, the title, and the length in microseconds`() {
        val metadata = Mpris.metadata(episode, track = 3)
        assertEquals("/com/hawkynt/mediadroid/track/3", metadata["mpris:trackid"]?.value.toString())
        assertEquals("o", metadata["mpris:trackid"]?.sig)
        assertEquals("The Pilot", metadata["xesam:title"]?.value)
        assertEquals(1_500_000L, metadata["mpris:length"]?.value)
        assertEquals("x", metadata["mpris:length"]?.sig)
        assertNull(Mpris.metadata(episode.copy(durationMs = 0), track = 3)["mpris:length"])
        assertEquals(Mpris.NO_TRACK, Mpris.metadata(null, 0)["mpris:trackid"]?.value.toString())
    }

    @Test
    fun `the session bus is the one named in the environment, else the runtime folder's`() {
        assertEquals(
            "unix:path=/run/user/1000/bus,guid=1",
            SessionBus.address(
                mapOf("DBUS_SESSION_BUS_ADDRESS" to "unix:path=/run/user/1000/bus,guid=1"),
                1000,
            ) { false },
        )
        assertEquals(
            "unix:path=/tmp/rt/bus",
            SessionBus.address(mapOf("XDG_RUNTIME_DIR" to "/tmp/rt"), 1000) { it == "/tmp/rt/bus" },
        )
        assertEquals(
            "unix:path=/run/user/1000/bus",
            SessionBus.address(emptyMap(), 1000) { it == "/run/user/1000/bus" },
        )
        assertNull(SessionBus.address(emptyMap(), 1000) { false })
        assertNull(SessionBus.address(emptyMap(), null) { true })
    }

    @Test
    fun `SMTC's buttons are the player's commands`() {
        assertEquals(MediaCommand.PLAY, Smtc.command(0))
        assertEquals(MediaCommand.PAUSE, Smtc.command(1))
        assertEquals(MediaCommand.STOP, Smtc.command(2))
        assertEquals(MediaCommand.NEXT, Smtc.command(6))
        assertEquals(MediaCommand.PREVIOUS, Smtc.command(7))
        assertNull(Smtc.command(3))
        assertEquals(3, Smtc.status(episode))
        assertEquals(4, Smtc.status(episode.copy(playing = false)))
        assertEquals(0, Smtc.status(null))
    }

    @Test
    fun `the button handler's interface id is WinRT's parameterized one, as the Windows SDK's headers give it`() {
        // windows.media.h's IID for TypedEventHandler<SystemMediaTransportControls,
        // SystemMediaTransportControlsButtonPressedEventArgs>
        assertEquals("0557e996-7b23-5bae-aa81-ea0d671143a4", Smtc.BUTTON_HANDLER_IID)
    }

    @Test
    fun `an interface id is laid out in memory as Windows' GUID structure`() {
        assertArrayEquals(
            byteArrayOf(
                0xf4.toByte(), 0x3f, 0xfa.toByte(), 0x99.toByte(), 0x42, 0x17, 0xa6.toByte(), 0x42,
                0x90.toByte(), 0x2e, 0x08, 0x7d, 0x41, 0xf9.toByte(), 0x65, 0xec.toByte(),
            ),
            Smtc.guidBytes("99fa3ff4-1742-42a6-902e-087d41f965ec"),
        )
        assertTrue(Smtc.guidBytes(Smtc.BUTTON_HANDLER_IID).size == 16)
    }
}
