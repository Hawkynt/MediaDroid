package com.hawkynt.mediadroyd.desktop

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class SmtcControlsTest {

    /** ISystemMediaTransportControlsButtonPressedEventArgs.get_Button, for a stand-in of the event's arguments. */
    interface GetButton : Callback {
        fun invoke(self: Pointer, out: Pointer): Int
    }

    /** A COM object whose table has get_Button at its place (6) and nothing else callable: the event's arguments. */
    private class FakeArgs(button: Int) {
        private val getButton = object : GetButton {
            override fun invoke(self: Pointer, out: Pointer): Int {
                out.setInt(0, button)
                return Com.S_OK
            }
        }
        private val table = Memory(7L * Native.POINTER_SIZE).apply {
            clear()
            setPointer(6L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(getButton))
        }
        val pointer: Pointer = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, table) }
    }

    @Test
    fun `the button handler answers COM as the delegate it is and passes the button pressed on`() {
        val pressed = CopyOnWriteArrayList<Int>()
        val handler = ComButtonHandler(Smtc.BUTTON_HANDLER_IID) { pressed += it }

        assertEquals(handler.pointer, Com.query(handler.pointer, Smtc.BUTTON_HANDLER_IID))
        assertEquals(handler.pointer, Com.query(handler.pointer, "00000000-0000-0000-c000-000000000046"))
        val out = PointerByReference()
        val other = Memory(16).apply { write(0, Smtc.guidBytes("99fa3ff4-1742-42a6-902e-087d41f965ec"), 0, 16) }
        assertEquals(Com.E_NOINTERFACE, Com.call(handler.pointer, Com.QUERY_INTERFACE, other, out.pointer))

        // Invoke(sender, args): the fourth function, after IUnknown's three.
        assertEquals(Com.S_OK, Com.call(handler.pointer, 3, null, FakeArgs(6).pointer))
        assertEquals(Com.S_OK, Com.call(handler.pointer, 3, null, FakeArgs(1).pointer))
        assertEquals(listOf(6, 1), pressed.toList())
        assertEquals(listOf(MediaCommand.NEXT, MediaCommand.PAUSE), pressed.map { Smtc.command(it) })
    }

    @Test
    fun `on Windows the controls start, show what plays and close`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val controls = SmtcControls {}
        controls.show(NowPlaying("The Pilot", playing = true, hasPrevious = false, hasNext = true, durationMs = 2_000))
        controls.show(NowPlaying("The Pilot", playing = false, hasPrevious = false, hasNext = true, durationMs = 2_000))
        controls.show(null)
        controls.close()
    }
}
