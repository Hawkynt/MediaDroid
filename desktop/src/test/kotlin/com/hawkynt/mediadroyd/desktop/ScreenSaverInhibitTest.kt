package com.hawkynt.mediadroyd.desktop

import org.freedesktop.dbus.types.UInt32
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ScreenSaverInhibitTest {

    /** A desktop's screensaver as the spec describes it: hands out cookies, takes them back. */
    private class FakeScreenSaver : FreedesktopScreenSaver {
        val calls = CopyOnWriteArrayList<String>()
        private var next = 41L

        override fun getObjectPath() = "/org/freedesktop/ScreenSaver"

        override fun Inhibit(application: String, reason: String): UInt32 {
            calls += "Inhibit $application: $reason"
            return UInt32(++next)
        }

        override fun UnInhibit(cookie: UInt32) {
            calls += "UnInhibit $cookie"
        }
    }

    @Test
    fun `while something plays the desktop's screensaver is held off, once, and let go with its cookie`() {
        val bus = PrivateBus.start()
        assumeTrue("dbus-daemon is not installed", bus != null)
        bus!!.use {
            val service = SessionBus.connect(bus.address)!!
            val screensaver = FakeScreenSaver()
            service.exportObject(screensaver.objectPath, screensaver)
            service.requestBusName("org.freedesktop.ScreenSaver")
            val inhibit = ScreenSaverInhibit { SessionBus.connect(bus.address) }
            inhibit.hold(true)
            inhibit.hold(true)
            inhibit.hold(false)
            inhibit.hold(false)
            inhibit.hold(true)
            inhibit.close()
            assertEquals(
                listOf(
                    "Inhibit MediaDroyd: Playing a video", "UnInhibit 42",
                    "Inhibit MediaDroyd: Playing a video", "UnInhibit 43",
                ),
                screensaver.calls.toList(),
            )
            service.close()
        }
    }

    @Test
    fun `without a screensaver on the bus, or without a bus, holding does nothing and fails nothing`() {
        ScreenSaverInhibit { null }.hold(true)
        val bus = PrivateBus.start()
        assumeTrue("dbus-daemon is not installed", bus != null)
        bus!!.use {
            val inhibit = ScreenSaverInhibit { SessionBus.connect(bus.address) }
            inhibit.hold(true)
            inhibit.hold(false)
        }
    }
}
