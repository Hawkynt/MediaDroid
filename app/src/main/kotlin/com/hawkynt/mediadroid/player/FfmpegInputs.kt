package com.hawkynt.mediadroid.player

import com.hawkynt.mediadroid.ffmpeg.FfmpegDemuxer
import com.hawkynt.mediadroid.library.FsFile
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Files as FFmpeg's demuxers read them: a local file at random, a network file as a stream that is
 * opened again further in when FFmpeg seeks.
 */
object FfmpegInputs {

    fun local(file: RandomAccessFile): FfmpegDemuxer.Input = object : FfmpegDemuxer.Input {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = file.read(buffer, offset, length)

        override fun seek(position: Long): Long {
            file.seek(position)
            return position
        }

        override fun length(): Long = file.length()
    }

    fun remote(file: FsFile): FfmpegDemuxer.Input = object : FfmpegDemuxer.Input {
        private var stream: InputStream? = null
        private var position = 0L

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val current = stream ?: BufferedInputStream(file.open(position), REMOTE_BUFFER).also { stream = it }
            val read = current.read(buffer, offset, length)
            if (read > 0) position += read
            return read
        }

        override fun seek(position: Long): Long {
            if (position != this.position) {
                stream?.close()
                stream = null
                this.position = position
            }
            return position
        }

        override fun length(): Long = file.length().takeIf { it > 0 } ?: -1
    }

    private const val REMOTE_BUFFER = 256 * 1024
}
