package com.hawkynt.mediadroyd.desktop

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.Variant
import java.awt.EventQueue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** What the system's media keys and now-playing panel ask the player to do. */
enum class MediaCommand { PLAY, PAUSE, PLAY_PAUSE, STOP, NEXT, PREVIOUS }

/** What plays now, as the system's now-playing panel shows it. */
data class NowPlaying(
    val title: String,
    val playing: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    /** The video's length, 0 while unknown. */
    val durationMs: Long,
)

/**
 * The system's media keys and now-playing panel: MPRIS over the session bus on Linux, the System Media Transport
 * Controls on Windows. Where the system offers neither, it does nothing.
 */
interface MediaControls : AutoCloseable {

    /** Shows [now] in the system's panel; null: nothing plays. */
    fun show(now: NowPlaying?)

    companion object {
        /**
         * This system's controls, sending what the keys and the panel ask for to [onCommand] (on a thread of the
         * system's, not the window's).
         */
        fun forThisSystem(onCommand: (MediaCommand) -> Unit): MediaControls {
            val os = System.getProperty("os.name")
            return when {
                os.startsWith("Windows") -> runCatching { SmtcControls(onCommand) }.getOrNull()
                os.startsWith("Linux") -> SessionBus.connect()?.let { bus ->
                    runCatching { MprisControls(bus, onCommand, closeBus = true) }
                        .onFailure { runCatching { bus.close() } }.getOrNull()
                }
                else -> null
            } ?: Nothing
        }
    }

    private object Nothing : MediaControls {
        override fun show(now: NowPlaying?) = Unit
        override fun close() = Unit
    }
}

/**
 * The app's one set of media controls ([make]: this system's), for whichever player screen is open: its [onCommand]
 * is called on the window's thread. Starting the controls, showing and closing run in order on a thread of their own,
 * so a slow system never holds up the window.
 */
class MediaKeys(
    make: ((MediaCommand) -> Unit) -> MediaControls = MediaControls::forThisSystem,
) : AutoCloseable {

    /** What the open player does with a command; nothing while no player is open. */
    @Volatile var onCommand: (MediaCommand) -> Unit = {}

    private val worker = Executors.newSingleThreadExecutor {
        Thread(it, "mediadroyd-media-keys").apply { isDaemon = true }
    }
    private val controls = worker.submit<MediaControls> {
        make { command -> EventQueue.invokeLater { onCommand(command) } }
    }

    /** Shows [now] in the system's panel; null: nothing plays. */
    fun show(now: NowPlaying?) {
        worker.execute { runCatching { controls.get().show(now) } }
    }

    override fun close() {
        worker.execute { runCatching { controls.get().close() } }
        worker.shutdown()
        worker.awaitTermination(2, TimeUnit.SECONDS)
    }
}

/** The MPRIS D-Bus interface's terms (specifications.freedesktop.org/mpris-spec), pure for the tests. */
object Mpris {
    const val PATH = "/org/mpris/MediaPlayer2"
    const val ROOT = "org.mpris.MediaPlayer2"
    const val PLAYER = "org.mpris.MediaPlayer2.Player"

    /** The bus name, `org.mpris.MediaPlayer2.<name>`; a second one running adds `.instance<pid>`, as the spec says. */
    const val BUS_NAME = "org.mpris.MediaPlayer2.mediadroyd"

    /** The spec's track id for no track. */
    const val NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack"

    /** The command a Player method asks for; null for anything else. */
    fun command(method: String): MediaCommand? = when (method) {
        "Play" -> MediaCommand.PLAY
        "Pause" -> MediaCommand.PAUSE
        "PlayPause" -> MediaCommand.PLAY_PAUSE
        "Stop" -> MediaCommand.STOP
        "Next" -> MediaCommand.NEXT
        "Previous" -> MediaCommand.PREVIOUS
        else -> null
    }

    /** PlaybackStatus: `Playing`, `Paused`, or `Stopped` while nothing plays. */
    fun status(now: NowPlaying?): String = when {
        now == null -> "Stopped"
        now.playing -> "Playing"
        else -> "Paused"
    }

    /** Metadata (`a{sv}`): the track id ([track], the queue's position), the title, the length in microseconds. */
    fun metadata(now: NowPlaying?, track: Int): Map<String, Variant<*>> {
        if (now == null) return mapOf("mpris:trackid" to Variant(DBusPath(NO_TRACK), "o"))
        return buildMap {
            put("mpris:trackid", Variant(DBusPath("/com/hawkynt/mediadroyd/track/$track"), "o"))
            put("xesam:title", Variant(now.title))
            if (now.durationMs > 0) put("mpris:length", Variant(now.durationMs * 1000, "x"))
        }
    }
}

/** The user's D-Bus session bus. */
object SessionBus {

    /**
     * The bus's address: `DBUS_SESSION_BUS_ADDRESS` from [env], else the socket `bus` in `XDG_RUNTIME_DIR` or in
     * `/run/user/<uid>` where it [exists]. Null without one.
     */
    fun address(env: Map<String, String>, uid: Int?, exists: (String) -> Boolean): String? {
        env["DBUS_SESSION_BUS_ADDRESS"]?.takeIf { it.isNotBlank() }?.let { return it }
        val candidates = listOfNotNull(env["XDG_RUNTIME_DIR"]?.let { "$it/bus" }, uid?.let { "/run/user/$it/bus" })
        return candidates.firstOrNull(exists)?.let { "unix:path=$it" }
    }

    /** A connection of its own to this user's session bus; null where there is none. */
    fun connect(): DBusConnection? {
        val uid = runCatching {
            Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid") as Int
        }.getOrNull()
        val address = address(System.getenv(), uid) { File(it).exists() } ?: return null
        return connect(address)
    }

    /** A connection of its own to the bus at [address]; null when it cannot be reached. */
    fun connect(address: String): DBusConnection? =
        runCatching { DBusConnectionBuilder.forAddress(address).withShared(false).build() }.getOrNull()
}

/** The System Media Transport Controls' terms (Windows SDK, windows.media.h), pure for the tests. */
object Smtc {

    /** The command a SystemMediaTransportControlsButton asks for; null for those the player does not offer. */
    fun command(button: Int): MediaCommand? = when (button) {
        0 -> MediaCommand.PLAY
        1 -> MediaCommand.PAUSE
        2 -> MediaCommand.STOP
        6 -> MediaCommand.NEXT
        7 -> MediaCommand.PREVIOUS
        else -> null
    }

    /** MediaPlaybackStatus: Closed (0) while nothing plays, Playing (3), Paused (4). */
    fun status(now: NowPlaying?): Int = when {
        now == null -> 0
        now.playing -> 3
        else -> 4
    }

    /**
     * TypedEventHandler<SystemMediaTransportControls, SystemMediaTransportControlsButtonPressedEventArgs>: the generic
     * delegate's id, then each runtime class with its default interface's id, as WinRT writes a type's signature.
     */
    private const val BUTTON_HANDLER_SIGNATURE = "pinterface({9de1c534-6ae1-11e0-84e1-18a905bcc53f};" +
        "rc(Windows.Media.SystemMediaTransportControls;{99fa3ff4-1742-42a6-902e-087d41f965ec});" +
        "rc(Windows.Media.SystemMediaTransportControlsButtonPressedEventArgs;{b7f47116-a56f-4dc8-9e11-92031f4a87c2}))"

    /** The interface id of the button handler, worked out as WinRT does for a parameterized interface. */
    val BUTTON_HANDLER_IID: String = parameterizedIid(BUTTON_HANDLER_SIGNATURE)

    /**
     * A parameterized interface's id: a name-based (version 5, SHA-1) UUID of [signature] in WinRT's namespace
     * {11f47ad5-7b73-42c0-abae-878b1e16adee}, as RFC 4122 makes one.
     */
    fun parameterizedIid(signature: String): String {
        val namespace = UUID.fromString("11f47ad5-7b73-42c0-abae-878b1e16adee")
        val bytes = java.nio.ByteBuffer.allocate(16)
            .putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array()
        val hash = MessageDigest.getInstance("SHA-1").apply {
            update(bytes)
            update(signature.toByteArray(Charsets.UTF_8))
        }.digest().copyOf(16)
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = java.nio.ByteBuffer.wrap(hash)
        return UUID(buffer.long, buffer.long).toString()
    }

    /** [guid] as Windows' GUID structure lays it out: Data1, Data2 and Data3 little-endian, Data4 as it is. */
    fun guidBytes(guid: String): ByteArray {
        val uuid = UUID.fromString(guid)
        val big = java.nio.ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        return byteArrayOf(big[3], big[2], big[1], big[0], big[5], big[4], big[7], big[6]) + big.copyOfRange(8, 16)
    }
}
