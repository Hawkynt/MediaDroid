package com.hawkynt.mediadroyd.player

/**
 * libass (ISC) as the native library carries it (native/jni/subtitles.cpp): styled ASS/SSA subtitles drawn as authored
 * — fonts, positions, karaoke, signs. The library is the app's own (`libmediadroyd_neural.so` on Android, `mediadroyd`
 * on the desktop), loaded by the platform before anything here is called; [AssTrack] is the way to use it.
 */
object LibAss {
    /** Whether the native library with libass in it is loaded: false where it is missing or older than libass. */
    val available: Boolean
        get() = runCatching { version() > 0 }.getOrDefault(false)

    /** libass's version, as `ass_library_version` gives it (0x01705000 for 0.17.5). */
    external fun version(): Int

    /** A new, empty track: a handle until [close], or 0 where libass does not come up. */
    external fun open(): Long

    external fun close(handle: Long)

    /** A font the script may use (a file's attachment), by its file [name]. */
    external fun addFont(handle: Long, name: String, data: ByteArray)

    /**
     * The font for what no other font draws: a font file's [path] and its [family] (either empty for none); where
     * [system], the system's own fonts are asked too where the build has a way to (DirectWrite, Fontconfig).
     */
    external fun defaultFont(handle: Long, path: String, family: String, system: Boolean)

    /**
     * The face [index] of the font file at [path] made the family libass falls back to, for scripts the other fonts
     * lack; the file is read once however often it is named. Its family name, empty where the file does not read.
     */
    external fun fallbackFont(handle: Long, path: String, index: Int): String

    /** A stream's header, its codec private: `[Script Info]`, the styles, the events' format. */
    external fun header(handle: Long, data: ByteArray)

    /** Script text as a file holds it: the whole file, or any part of it in order. */
    external fun script(handle: Long, data: ByteArray)

    /** One event as Matroska carries it (`ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text`). */
    external fun event(handle: Long, data: ByteArray, startMs: Long, durationMs: Long)

    /** Every event dropped; the header stays. */
    external fun flush(handle: Long)

    /** Where lines without a place of their own stand: 0 where their style puts them, 100 at the top. */
    external fun linePosition(handle: Long, percent: Double)

    /**
     * The track at [timeMs] for a frame [width] × [height] — the whole video reaching the margins past it (0 or below,
     * [AssFrame]) where it shows part of it: null where it is the picture drawn last, else x, y, width and height of
     * the box that holds it, then its pixels (0xAARRGGBB, rows from the top) — width 0 for nothing.
     */
    external fun render(
        handle: Long,
        timeMs: Long,
        width: Int,
        height: Int,
        storageWidth: Int,
        storageHeight: Int,
        marginTop: Int,
        marginBottom: Int,
        marginLeft: Int,
        marginRight: Int,
    ): IntArray?
}
