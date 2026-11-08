package com.hawkynt.mediadroyd.player

/**
 * MPEG program streams — ISO/IEC 11172-1 system streams (MPEG-1) and 13818-1 program streams (MPEG-2) — as
 * they are told from their first bytes: both open with a pack header, the start code 00 00 01 BA. Their
 * MPEG sound carries frame syncs an MP3 reader would take for a file of its own.
 */
object ProgramStream {
    /** The pack start code, the four bytes every program stream opens with. */
    private val PACK_START = byteArrayOf(0x00, 0x00, 0x01, 0xBA.toByte())

    /** How many bytes [opensWithPack] looks at. */
    const val HEADER_BYTES = 4

    /** Whether [header], a file's first bytes, opens with a pack header. */
    fun opensWithPack(header: ByteArray): Boolean =
        header.size >= HEADER_BYTES && (0 until HEADER_BYTES).all { header[it] == PACK_START[it] }
}
