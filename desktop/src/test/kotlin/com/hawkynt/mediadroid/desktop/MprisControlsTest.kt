package com.hawkynt.mediadroid.desktop

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** MPRIS on a private session bus: what a desktop's media keys and panels send and read. */
class MprisControlsTest {

    private var bus: PrivateBus? = null

    @Before
    fun start() {
        bus = PrivateBus.start()
        assumeTrue("dbus-daemon is not installed", bus != null)
    }

    @After
    fun stop() {
        bus?.close()
    }

    @Test
    fun `media keys arrive as the player's commands, and the panel reads what plays`() {
        val address = bus!!.address
        val commands = CopyOnWriteArrayList<MediaCommand>()
        val controls = MprisControls(SessionBus.connect(address)!!, { commands += it }, closeBus = true)
        val client = SessionBus.connect(address)!!
        try {
            val changes = LinkedBlockingQueue<Properties.PropertiesChanged>()
            client.addSigHandler(Properties.PropertiesChanged::class.java) { changes += it }

            val player = client.getRemoteObject(Mpris.BUS_NAME, Mpris.PATH, MprisPlayer::class.java, true)
            player.PlayPause()
            player.Next()
            player.Previous()
            player.Stop()
            player.Seek(5_000_000)
            assertEquals(
                listOf(MediaCommand.PLAY_PAUSE, MediaCommand.NEXT, MediaCommand.PREVIOUS, MediaCommand.STOP),
                commands.toList(),
            )

            val properties = client.getRemoteObject(Mpris.BUS_NAME, Mpris.PATH, Properties::class.java, true)
            assertEquals("Stopped", properties.Get<String>(Mpris.PLAYER, "PlaybackStatus"))
            assertEquals("MediaDroid", properties.Get<String>(Mpris.ROOT, "Identity"))

            controls.show(
                NowPlaying("The Pilot", playing = true, hasPrevious = false, hasNext = true, durationMs = 2_000),
            )
            assertEquals("Playing", properties.Get<String>(Mpris.PLAYER, "PlaybackStatus"))
            assertEquals(true, properties.Get<Boolean>(Mpris.PLAYER, "CanGoNext"))
            assertEquals(false, properties.Get<Boolean>(Mpris.PLAYER, "CanGoPrevious"))
            // dbus-java hands the a{sv} back with the variants opened or not, depending on the call.
            val metadata = properties.Get<Map<String, Any>>(Mpris.PLAYER, "Metadata").mapValues { (_, v) ->
                if (v is Variant<*>) v.value else v
            }
            assertEquals("The Pilot", metadata["xesam:title"])
            assertEquals(2_000_000L, metadata["mpris:length"])
            assertTrue(metadata["mpris:trackid"] is DBusPath)
            val all = properties.GetAll(Mpris.PLAYER)
            assertEquals("Playing", all["PlaybackStatus"]?.value)

            val changed = changes.poll(5, TimeUnit.SECONDS)
            assertNotNull("no PropertiesChanged signal", changed)
            assertEquals(Mpris.PLAYER, changed!!.interfaceName)
            assertEquals("Playing", changed.propertiesChanged["PlaybackStatus"]?.value)
        } finally {
            controls.close()
            client.close()
        }
    }

    @Test
    fun `a second player running takes an instance name of its own`() {
        val address = bus!!.address
        val first = MprisControls(SessionBus.connect(address)!!, {}, closeBus = true)
        val commands = CopyOnWriteArrayList<MediaCommand>()
        val second = MprisControls(SessionBus.connect(address)!!, { commands += it }, closeBus = true)
        val client = SessionBus.connect(address)!!
        try {
            val name = "${Mpris.BUS_NAME}.instance${ProcessHandle.current().pid()}"
            client.getRemoteObject(name, Mpris.PATH, MprisPlayer::class.java, true).Play()
            assertEquals(listOf(MediaCommand.PLAY), commands.toList())
        } finally {
            second.close()
            first.close()
            client.close()
        }
    }
}
