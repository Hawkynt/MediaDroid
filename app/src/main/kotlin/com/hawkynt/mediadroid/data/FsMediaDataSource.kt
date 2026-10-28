package com.hawkynt.mediadroid.data

import android.media.MediaDataSource
import com.hawkynt.mediadroid.library.FsFile
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * A file on any source for Android's MediaMetadataRetriever, which reads at random positions: a
 * stream is kept open while the reads follow on, and opened again where they jump.
 */
class FsMediaDataSource(private val file: FsFile) : MediaDataSource() {
    private var stream: InputStream? = null
    private var position = -1L

    @Synchronized
    override fun readAt(at: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (at != position || stream == null) {
            stream?.close()
            stream = BufferedInputStream(file.open(at), BUFFER)
            position = at
        }
        val read = stream!!.read(buffer, offset, size)
        if (read > 0) position += read
        return read
    }

    override fun getSize(): Long = file.length().takeIf { it > 0 } ?: -1

    @Synchronized
    override fun close() {
        stream?.close()
        stream = null
    }

    private companion object {
        const val BUFFER = 128 * 1024
    }
}
