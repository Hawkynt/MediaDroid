package com.hawkynt.mediadroid.player

/**
 * One ASS/SSA subtitle track drawn by libass ([LibAss]): fed the file's fonts, its header and its events (or a whole
 * script file), drawn at a moment for the size it is shown at. Safe from any thread: the events are fed on the
 * player's, the pictures drawn on the screen's. Only where [LibAss.available].
 */
class AssTrack : AutoCloseable {
    private var handle = LibAss.open()

    /** Whether libass came up and the track is still open. */
    val open: Boolean get() = handle != 0L

    /** A font the script may use: a file's attachment, by its file [name]. */
    fun addFont(name: String, data: ByteArray) = ifOpen { LibAss.addFont(it, name, data) }

    /** The font for what no other font draws (a file's [path] and [family]), and whether the system's are asked. */
    fun defaultFont(path: String = "", family: String = "", system: Boolean = false) =
        ifOpen { LibAss.defaultFont(it, path, family, system) }

    /**
     * The face [index] of the font file at [path] — a system's Chinese, Japanese or Korean font ([ScriptFallback]) —
     * as what libass falls back to for the scripts the other fonts lack. Its family name; empty where it does not
     * read or the track is closed.
     */
    @Synchronized
    fun fallbackFont(path: String, index: Int = 0): String =
        handle.takeIf { it != 0L }?.let { LibAss.fallbackFont(it, path, index) }.orEmpty()

    /** A stream's header (its codec private). */
    fun header(data: ByteArray) = ifOpen { LibAss.header(it, data) }

    /** Script text as a file holds it. */
    fun script(data: ByteArray) = ifOpen { LibAss.script(it, data) }

    /** One event as Matroska carries it, shown from [startMs] for [durationMs]; one fed before is not doubled. */
    fun event(data: ByteArray, startMs: Long, durationMs: Long) =
        ifOpen { LibAss.event(it, data, startMs, durationMs) }

    /** Every event dropped; the header stays. */
    fun flush() = ifOpen { LibAss.flush(it) }

    /**
     * The track at [timeMs] drawn for a frame [width] × [height] — the size it is shown at, for sharp text — over a
     * video of [videoWidth] × [videoHeight] pixels (0 for the frame's); null where the picture is the one drawn last.
     */
    @Synchronized
    fun render(timeMs: Long, width: Int, height: Int, videoWidth: Int = 0, videoHeight: Int = 0): AssPicture? {
        val h = handle
        if (h == 0L) return null
        return LibAss.render(h, timeMs, width, height, videoWidth, videoHeight)?.let(AssPicture::of)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) LibAss.close(handle)
        handle = 0L
    }

    @Synchronized
    private fun ifOpen(action: (Long) -> Unit) {
        if (handle != 0L) action(handle)
    }
}

/**
 * What a subtitle track shows at a moment: [pixels] (0xAARRGGBB, not premultiplied, rows from the top) in a box of
 * [width] × [height] at [x], [y] of the frame it was drawn for. [EMPTY] where nothing shows.
 */
class AssPicture(val x: Int, val y: Int, val width: Int, val height: Int, val pixels: IntArray) {
    /** Whether anything shows. */
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /**
     * Laid over [rgba] — a frame of [frameWidth] × [frameHeight], 4 bytes a pixel (R, G, B, A), rows from the top, the
     * size this was drawn for — in place: each colour by its alpha over what is there, rounded.
     */
    fun blendInto(rgba: ByteArray, frameWidth: Int, frameHeight: Int) {
        if (isEmpty) return
        for (row in 0 until height) {
            val fy = y + row
            if (fy !in 0 until frameHeight) continue
            for (column in 0 until width) {
                val fx = x + column
                if (fx !in 0 until frameWidth) continue
                val p = pixels[row * width + column]
                val a = p ushr 24
                if (a == 0) continue
                val at = (fy * frameWidth + fx) * 4
                for (c in 0 until 3) {
                    val over = (p shr (16 - 8 * c)) and 0xFF
                    val under = rgba[at + c].toInt() and 0xFF
                    rgba[at + c] = ((over * a + under * (255 - a) + 127) / 255).toByte()
                }
                val under = rgba[at + 3].toInt() and 0xFF
                rgba[at + 3] = (a + (under * (255 - a) + 127) / 255).toByte()
            }
        }
    }

    companion object {
        /** Nothing shows. */
        val EMPTY = AssPicture(0, 0, 0, 0, IntArray(0))

        /** The native render's answer: x, y, width, height, then the pixels. */
        fun of(drawn: IntArray): AssPicture {
            if (drawn.size < 4 || drawn[2] <= 0 || drawn[3] <= 0) return EMPTY
            return AssPicture(drawn[0], drawn[1], drawn[2], drawn[3], drawn.copyOfRange(4, 4 + drawn[2] * drawn[3]))
        }
    }
}
