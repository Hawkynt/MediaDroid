package com.hawkynt.mediadroyd.desktop

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Windows' System Media Transport Controls: the media keys and the now-playing panel by the volume flyout. A desktop
 * program gets them from a Windows.Media.Playback.MediaPlayer of its own whose command manager is switched off, so
 * the buttons come to [onCommand] instead; the WinRT calls go through JNA to their places in each interface's table,
 * as the Windows SDK's windows.media.h and windows.media.playback.h lay them out. Throws where WinRT is missing.
 */
class SmtcControls(private val onCommand: (MediaCommand) -> Unit) : MediaControls {

    private val player: Pointer
    private val smtc: Pointer
    private val updater: Pointer
    private val token: Long

    init {
        apartment()
        player = WinRt.activate("Windows.Media.Playback.MediaPlayer")
        val player3 = Com.query(player, IID_MEDIA_PLAYER3)
        val commands = Com.get(player3, MEDIA_PLAYER3_COMMAND_MANAGER)
        Com.call(commands, COMMAND_MANAGER_SET_IS_ENABLED, 0).ok("CommandManager.IsEnabled")
        Com.release(commands)
        Com.release(player3)
        val player2 = Com.query(player, IID_MEDIA_PLAYER2)
        smtc = Com.get(player2, MEDIA_PLAYER2_SMTC)
        Com.release(player2)
        listOf(SET_IS_ENABLED, SET_IS_PLAY_ENABLED, SET_IS_PAUSE_ENABLED, SET_IS_STOP_ENABLED).forEach {
            Com.call(smtc, it, 1).ok("SystemMediaTransportControls #$it")
        }
        updater = Com.get(smtc, DISPLAY_UPDATER)
        val handler = ComButtonHandler(Smtc.BUTTON_HANDLER_IID) { button -> Smtc.command(button)?.let(onCommand) }
        val tokenOut = LongByReference()
        Com.call(smtc, ADD_BUTTON_PRESSED, handler.pointer, tokenOut.pointer).ok("ButtonPressed")
        token = tokenOut.value
        show(null)
    }

    override fun show(now: NowPlaying?) {
        apartment()
        Com.call(smtc, SET_PLAYBACK_STATUS, Smtc.status(now)).ok("PlaybackStatus")
        Com.call(smtc, SET_IS_NEXT_ENABLED, if (now?.hasNext == true) 1 else 0)
        Com.call(smtc, SET_IS_PREVIOUS_ENABLED, if (now?.hasPrevious == true) 1 else 0)
        if (now == null) {
            Com.call(updater, UPDATER_CLEAR_ALL)
        } else {
            // The video properties are there only once the type says video (ERROR_NOT_SUPPORTED before), and
            // ClearAll takes the type away again: so set, then asked for afresh each time.
            Com.call(updater, UPDATER_SET_TYPE, MEDIA_PLAYBACK_TYPE_VIDEO).ok("DisplayUpdater.Type")
            val video = Com.get(updater, UPDATER_VIDEO_PROPERTIES)
            try {
                WinRt.string(now.title) { Com.call(video, VIDEO_SET_TITLE, it) }.ok("VideoProperties.Title")
            } finally {
                Com.release(video)
            }
        }
        Com.call(updater, UPDATER_UPDATE).ok("DisplayUpdater.Update")
    }

    override fun close() {
        apartment()
        Com.call(smtc, REMOVE_BUTTON_PRESSED, token)
        Com.call(smtc, SET_PLAYBACK_STATUS, Smtc.status(null))
        listOf(updater, smtc, player).forEach(Com::release)
    }

    /** WinRT on this thread, in the multithreaded apartment (a thread already in another keeps that one). */
    private fun apartment() {
        if (initialized.get() == true) return
        WinRt.combase.getFunction("RoInitialize").invokeInt(arrayOf(RO_INIT_MULTITHREADED))
        initialized.set(true)
    }

    private companion object {
        val initialized = ThreadLocal<Boolean>()

        const val RO_INIT_MULTITHREADED = 1

        // windows.media.playback.h: IMediaPlayer2, IMediaPlayer3, IMediaPlaybackCommandManager (IInspectable's six
        // functions first).
        const val IID_MEDIA_PLAYER2 = "3c841218-2123-4fc5-9082-2f883f77bdf5"
        const val IID_MEDIA_PLAYER3 = "ee0660da-031b-4feb-bd9b-92e0a0a8d299"
        const val MEDIA_PLAYER2_SMTC = 6
        const val MEDIA_PLAYER3_COMMAND_MANAGER = 17
        const val COMMAND_MANAGER_SET_IS_ENABLED = 7

        // windows.media.h: ISystemMediaTransportControls
        const val SET_PLAYBACK_STATUS = 7
        const val DISPLAY_UPDATER = 8
        const val SET_IS_ENABLED = 11
        const val SET_IS_PLAY_ENABLED = 13
        const val SET_IS_STOP_ENABLED = 15
        const val SET_IS_PAUSE_ENABLED = 17
        const val SET_IS_PREVIOUS_ENABLED = 25
        const val SET_IS_NEXT_ENABLED = 27
        const val ADD_BUTTON_PRESSED = 32
        const val REMOVE_BUTTON_PRESSED = 33

        // ISystemMediaTransportControlsDisplayUpdater, IVideoDisplayProperties
        const val UPDATER_SET_TYPE = 7
        const val UPDATER_VIDEO_PROPERTIES = 13
        const val UPDATER_CLEAR_ALL = 16
        const val UPDATER_UPDATE = 17
        const val VIDEO_SET_TITLE = 7

        // MediaPlaybackType.Video
        const val MEDIA_PLAYBACK_TYPE_VIDEO = 2
    }
}

private fun Int.ok(what: String) = check(this >= 0) { "$what failed: 0x%08x".format(this) }

/** WinRT's own functions, in combase.dll: activation and strings. */
private object WinRt {
    val combase: NativeLibrary = NativeLibrary.getInstance("combase")

    /** A new instance of the runtime class [name], as its IInspectable. */
    fun activate(name: String): Pointer = string(name) { id ->
        val out = PointerByReference()
        combase.getFunction("RoActivateInstance").invokeInt(arrayOf(id, out.pointer)).ok("RoActivateInstance $name")
        out.value
    }

    /** [text] as an HSTRING for [use], deleted after. */
    fun <T> string(text: String, use: (Pointer?) -> T): T {
        val out = PointerByReference()
        combase.getFunction("WindowsCreateString").invokeInt(arrayOf(WString(text), text.length, out.pointer))
            .ok("WindowsCreateString")
        try {
            return use(out.value)
        } finally {
            combase.getFunction("WindowsDeleteString").invokeInt(arrayOf(out.value))
        }
    }
}

/** COM's calling: a function by its place in an interface's table, the interface pointer first. */
internal object Com {
    const val S_OK = 0
    const val E_NOINTERFACE = 0x80004002.toInt()

    // IUnknown
    const val QUERY_INTERFACE = 0
    const val RELEASE = 2

    /** Calls the [index]th function of [self]'s interface table with [args] after [self]: its HRESULT. */
    fun call(self: Pointer, index: Int, vararg args: Any?): Int {
        val table = self.getPointer(0)
        val function = Function.getFunction(table.getPointer(index.toLong() * Native.POINTER_SIZE))
        return function.invokeInt(arrayOf(self, *args))
    }

    /** A getter that hands out an interface: the [index]th function's result. */
    fun get(self: Pointer, index: Int): Pointer {
        val out = PointerByReference()
        call(self, index, out.pointer).ok("getter #$index")
        return checkNotNull(out.value) { "getter #$index gave nothing" }
    }

    /** [self] as the interface [iid]. */
    fun query(self: Pointer, iid: String): Pointer {
        val out = PointerByReference()
        val id = Memory(16).apply { write(0, Smtc.guidBytes(iid), 0, 16) }
        call(self, QUERY_INTERFACE, id, out.pointer).ok("QueryInterface $iid")
        return checkNotNull(out.value) { "QueryInterface $iid gave nothing" }
    }

    fun release(self: Pointer) {
        call(self, RELEASE)
    }
}

/** IUnknown's QueryInterface, as a function a COM caller can call. */
internal interface ComQueryInterface : Callback {
    fun invoke(self: Pointer, riid: Pointer, out: Pointer): Int
}

/** IUnknown's AddRef and Release. */
internal interface ComCount : Callback {
    fun invoke(self: Pointer): Int
}

/** A TypedEventHandler's Invoke. */
internal interface ComInvoke : Callback {
    fun invoke(self: Pointer, sender: Pointer?, args: Pointer?): Int
}

/**
 * A COM object of our own for ButtonPressed — the delegate [iid], TypedEventHandler<SystemMediaTransportControls,
 * SystemMediaTransportControlsButtonPressedEventArgs> — calling [pressed] with the button's number, which it asks the
 * event's arguments for (their first function after IInspectable's six). It lives as long as the program: Windows
 * may hold it a while after it is removed.
 */
internal class ComButtonHandler(iid: String, pressed: (Int) -> Unit) {
    private val known = listOf(
        iid,
        "00000000-0000-0000-c000-000000000046", // IUnknown
        "94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90", // IAgileObject: callable from any thread
    ).map(Smtc::guidBytes)

    private val queryInterface = object : ComQueryInterface {
        override fun invoke(self: Pointer, riid: Pointer, out: Pointer): Int {
            val asked = riid.getByteArray(0, 16)
            return if (known.any { it.contentEquals(asked) }) {
                out.setPointer(0, self)
                Com.S_OK
            } else {
                out.setPointer(0, null)
                Com.E_NOINTERFACE
            }
        }
    }

    // Kept for the program's life (see above), so the count only has to look right.
    private val addRef = object : ComCount {
        override fun invoke(self: Pointer) = 2
    }
    private val release = object : ComCount {
        override fun invoke(self: Pointer) = 1
    }

    private val invoke = object : ComInvoke {
        override fun invoke(self: Pointer, sender: Pointer?, args: Pointer?): Int {
            if (args != null) {
                val button = IntByReference()
                if (Com.call(args, ARGS_BUTTON, button.pointer) >= 0) runCatching { pressed(button.value) }
            }
            return Com.S_OK
        }
    }

    private val table = Memory(4L * Native.POINTER_SIZE).apply {
        listOf(queryInterface, addRef, release, invoke).forEachIndexed { i, callback ->
            setPointer(i.toLong() * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(callback))
        }
    }

    /** The object, as COM hands it around: a pointer to its table. */
    val pointer: Pointer = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, table) }

    init {
        synchronized(kept) { kept += this }
    }

    private companion object {
        val kept = mutableListOf<ComButtonHandler>()

        // windows.media.h: ISystemMediaTransportControlsButtonPressedEventArgs.get_Button
        const val ARGS_BUTTON = 6
    }
}
