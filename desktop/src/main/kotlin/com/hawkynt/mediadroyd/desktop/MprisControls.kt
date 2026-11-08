package com.hawkynt.mediadroyd.desktop

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

/** MPRIS's root interface, org.mpris.MediaPlayer2. */
@DBusInterfaceName(Mpris.ROOT)
@Suppress("FunctionName")
interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

/** MPRIS's player interface, org.mpris.MediaPlayer2.Player. */
@DBusInterfaceName(Mpris.PLAYER)
@Suppress("FunctionName")
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
}

/**
 * The player on the session [bus] as MPRIS describes one (specifications.freedesktop.org/mpris-spec): GNOME's and KDE's
 * media keys and now-playing panels, playerctl and the rest send play, pause, next and previous, and show the title.
 * Seeking is left to the window ([MprisPlayer.Seek] is offered as CanSeek false). [closeBus]: the bus is this one's to
 * close.
 */
class MprisControls(
    private val bus: DBusConnection,
    private val onCommand: (MediaCommand) -> Unit,
    private val closeBus: Boolean = false,
) : MediaControls {

    @Volatile private var now: NowPlaying? = null

    @Volatile private var track = 0

    private val busName: String

    private val exported = object : MprisRoot, MprisPlayer, Properties {
        override fun getObjectPath() = Mpris.PATH
        override fun Raise() = Unit
        override fun Quit() = Unit
        override fun Next() = command("Next")
        override fun Previous() = command("Previous")
        override fun Pause() = command("Pause")
        override fun PlayPause() = command("PlayPause")
        override fun Stop() = command("Stop")
        override fun Play() = command("Play")
        override fun Seek(offset: Long) = Unit
        override fun SetPosition(trackId: DBusPath, position: Long) = Unit
        override fun OpenUri(uri: String) = Unit

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            properties(interfaceName)[propertyName] as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) = Unit

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = properties(interfaceName)
    }

    init {
        bus.exportObject(Mpris.PATH, exported)
        busName = runCatching { bus.requestBusName(Mpris.BUS_NAME); Mpris.BUS_NAME }.getOrElse {
            "${Mpris.BUS_NAME}.instance${ProcessHandle.current().pid()}".also { bus.requestBusName(it) }
        }
    }

    private fun command(method: String) {
        Mpris.command(method)?.let(onCommand)
    }

    private fun properties(interfaceName: String): Map<String, Variant<*>> {
        val now = now
        return when (interfaceName) {
            Mpris.ROOT -> mapOf(
                "CanQuit" to Variant(false),
                "CanRaise" to Variant(false),
                "HasTrackList" to Variant(false),
                "Identity" to Variant("MediaDroyd"),
                "DesktopEntry" to Variant("mediadroyd"),
                "SupportedUriSchemes" to Variant(arrayOf<String>(), "as"),
                "SupportedMimeTypes" to Variant(arrayOf<String>(), "as"),
            )
            Mpris.PLAYER -> mapOf(
                "PlaybackStatus" to Variant(Mpris.status(now)),
                "Rate" to Variant(1.0),
                "MinimumRate" to Variant(1.0),
                "MaximumRate" to Variant(1.0),
                "Metadata" to Variant(Mpris.metadata(now, track), "a{sv}"),
                "Volume" to Variant(1.0),
                "Position" to Variant(0L, "x"),
                "CanGoNext" to Variant(now?.hasNext == true),
                "CanGoPrevious" to Variant(now?.hasPrevious == true),
                "CanPlay" to Variant(now != null),
                "CanPause" to Variant(now != null),
                "CanSeek" to Variant(false),
                "CanControl" to Variant(true),
            )
            else -> emptyMap()
        }
    }

    override fun show(now: NowPlaying?) {
        val before = this.now
        if (now == before) return
        if (now?.title != before?.title) track++
        this.now = now
        val changed = properties(Mpris.PLAYER).filterKeys {
            it in setOf("PlaybackStatus", "Metadata", "CanGoNext", "CanGoPrevious", "CanPlay", "CanPause")
        }
        runCatching { bus.sendMessage(Properties.PropertiesChanged(Mpris.PATH, Mpris.PLAYER, changed, emptyList())) }
    }

    override fun close() {
        runCatching { bus.releaseBusName(busName) }
        runCatching { bus.unExportObject(Mpris.PATH) }
        if (closeBus) runCatching { bus.close() }
    }
}
