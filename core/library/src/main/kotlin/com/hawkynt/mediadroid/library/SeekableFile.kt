package com.hawkynt.mediadroid.library

import java.io.EOFException
import java.io.InputStream

/**
 * Random reads from any [FsFile] — the few a container header needs (seek, read fully, a 16-bit
 * length) — so the chapter and title readers work on network sources as on the device. A stream is
 * kept while reads follow on and opened again where a seek jumps.
 */
class SeekableFile(private val file: FsFile) : AutoCloseable {
    private var stream: InputStream? = null
    private var position = 0L
    private var streamAt = -1L

    fun length(): Long = file.length()

    fun seek(position: Long) {
        this.position = position
    }

    fun readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        if (stream == null || streamAt != position) {
            stream?.close()
            stream = file.open(position)
            streamAt = position
        }
        var done = 0
        while (done < length) {
            val n = stream!!.read(buffer, offset + done, length - done)
            if (n < 0) throw EOFException("${file.path} ends at ${position + done}")
            done += n
        }
        position += length
        streamAt = position
    }

    fun readUnsignedShort(): Int {
        val two = ByteArray(2)
        readFully(two)
        return ((two[0].toInt() and 0xff) shl 8) or (two[1].toInt() and 0xff)
    }

    override fun close() {
        stream?.close()
        stream = null
    }
}
