package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.library.FsFile
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.avformat.Write_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import java.io.InputStream

/**
 * FFmpeg's reading of a file from a network source ([FsFile], core:sources): an I/O context whose reads come from
 * [FsFile.open] streams — one kept while reading on, a new one from the offset after a seek further than a skip is
 * worth. Used by one reader on one thread.
 */
internal class FsInput(private val file: FsFile) : AutoCloseable {

    private var position = 0L
    private var stream: InputStream? = null
    private var streamAt = 0L
    private val length = file.length()
    private var chunk = ByteArray(BUFFER)

    // Held here: FFmpeg calls them for as long as the context lives, and the JVM must not collect them first.
    private val reader = object : Read_packet_Pointer_BytePointer_int() {
        override fun call(opaque: Pointer?, buffer: BytePointer, size: Int): Int = read(buffer, size)
    }
    private val seeker = object : Seek_Pointer_long_int() {
        override fun call(opaque: Pointer?, offset: Long, whence: Int): Long = seek(offset, whence)
    }

    /** The context to hand to the format context (its `pb`). */
    val context: AVIOContext = avformat.avio_alloc_context(
        BytePointer(avutil.av_malloc(BUFFER.toLong())), BUFFER, 0, null, reader,
        null as Write_packet_Pointer_BytePointer_int?, seeker,
    )

    private fun read(buffer: BytePointer, size: Int): Int = try {
        val input = stream?.takeIf { streamAt == position } ?: reopen()
        if (chunk.size < size) chunk = ByteArray(size)
        val n = input.read(chunk, 0, size)
        if (n <= 0) {
            avutil.AVERROR_EOF
        } else {
            buffer.capacity(size.toLong()).put(chunk, 0, n)
            position += n
            streamAt = position
            n
        }
    } catch (_: java.io.IOException) {
        avutil.AVERROR_EOF
    }

    private fun reopen(): InputStream {
        val ahead = position - streamAt
        val current = stream
        // A short way on is read past rather than asked for anew: a network round trip costs more.
        if (current != null && ahead in 1..SKIP_LIMIT) {
            var left = ahead
            while (left > 0) {
                val skipped = current.skip(left)
                if (skipped <= 0) break
                left -= skipped
            }
            if (left == 0L) {
                streamAt = position
                return current
            }
        }
        current?.close()
        return file.open(position).also {
            stream = it
            streamAt = position
        }
    }

    private fun seek(offset: Long, whence: Int): Long {
        if (whence and avformat.AVSEEK_SIZE != 0) return if (length > 0) length else -1
        val target = when (whence and 0xff) {
            SEEK_SET -> offset
            SEEK_CUR -> position + offset
            SEEK_END -> if (length > 0) length + offset else return -1
            else -> return -1
        }
        if (target < 0) return -1
        position = target
        return position
    }

    override fun close() {
        avutil.av_free(context.buffer())
        avformat.avio_context_free(context)
        stream?.close()
        stream = null
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val SKIP_LIMIT = 256 * 1024L
        const val SEEK_SET = 0
        const val SEEK_CUR = 1
        const val SEEK_END = 2
    }
}
