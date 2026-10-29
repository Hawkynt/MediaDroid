package com.hawkynt.mediadroid.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.hawkynt.mediadroid.ffmpeg.FfmpegDemuxer
import com.hawkynt.mediadroid.player.FfmpegInputs
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Still frames pulled out of video files, for the places where no scraped art
 * exists — episode rows without a `-thumb.jpg`, posters for movies that have
 * none. Every frame is extracted once and kept as a small JPEG under the app's
 * cache, keyed by path and modification time, so scrolling a wall never decodes
 * the same video twice. Callers just `await` the frame; concurrent requests
 * for one video share a single extraction.
 */
class ThumbnailStore private constructor(private val context: Context) {

    private val cacheRoot = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val extracting = Mutex()

    /** Resolves to a cached JPEG for [videoPath], or null if the file yields no frame. */
    suspend fun thumbnailFor(videoPath: String): File? {
        val target = File(cacheRoot, cacheKey(videoPath) + ".jpg")
        if (target.isFile && target.length() > 0) return target
        return extracting.withLock {
            if (target.isFile && target.length() > 0) return@withLock target
            withContext(Dispatchers.IO) { extract(videoPath, target) }
        }
    }

    private fun cacheKey(videoPath: String): String {
        val modified = if (videoPath.startsWith("/")) {
            File(videoPath).lastModified()
        } else {
            SourceAccess.resolve(context, videoPath)?.lastModified() ?: 0
        }
        val stamp = "$videoPath@$modified"
        return MessageDigest.getInstance("SHA-1").digest(stamp.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun extract(videoPath: String, target: File): File? = runCatching {
        // Android's own retriever first; FFmpeg for what it cannot read (DV, RealMedia, …).
        val frame = runCatching { androidFrame(videoPath) }.getOrNull() ?: ffmpegFrame(videoPath) ?: return null
        val scaled = scaleDown(frame)
        target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        if (scaled !== frame) scaled.recycle()
        frame.recycle()
        target
    }.getOrElse {
        target.delete()
        null
    }

    private fun androidFrame(videoPath: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            if (videoPath.startsWith("/")) {
                retriever.setDataSource(videoPath)
            } else {
                // A video on a network source, read where the retriever asks.
                retriever.setDataSource(FsMediaDataSource(SourceAccess.resolve(context, videoPath) ?: return null))
            }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            return retriever.getFrameAtTime(pickUs(durationMs?.times(1000)), MediaMetadataRetriever.OPTION_CLOSEST)
        } finally {
            retriever.release()
        }
    }

    /** The picture by the bundled FFmpeg: its demuxer and decoder, the keyframe before the same point. */
    private fun ffmpegFrame(videoPath: String): Bitmap? {
        if (!FfmpegDemuxer.isAvailable()) return null
        val local = if (videoPath.startsWith("/")) RandomAccessFile(videoPath, "r") else null
        try {
            val input = if (local != null) {
                FfmpegInputs.local(local)
            } else {
                FfmpegInputs.remote(SourceAccess.resolve(context, videoPath) ?: return null)
            }
            val demuxer = FfmpegDemuxer.open(input) ?: return null
            try {
                val durationUs = demuxer.durationUs().takeIf { it != FfmpegDemuxer.TIME_UNSET }
                val size = IntArray(2)
                val pixels = demuxer.frame(pickUs(durationUs), MAX_SIDE, size) ?: return null
                return Bitmap.createBitmap(pixels, size[0], size[1], Bitmap.Config.ARGB_8888)
            } finally {
                demuxer.close()
            }
        } finally {
            local?.close()
        }
    }

    /**
     * Where to take the picture: a tenth of the way in skips black or logo intros without landing in
     * the closing credits of short clips; a second in when the length is unknown.
     */
    private fun pickUs(durationUs: Long?): Long =
        if (durationUs != null && durationUs > 0) durationUs / 10 else 1_000_000L

    private fun scaleDown(frame: Bitmap): Bitmap {
        val max = MAX_SIDE
        if (frame.width <= max && frame.height <= max) return frame
        val factor = max.toFloat() / maxOf(frame.width, frame.height)
        return Bitmap.createScaledBitmap(
            frame,
            (frame.width * factor).toInt().coerceAtLeast(1),
            (frame.height * factor).toInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        private const val MAX_SIDE = 480

        @Volatile
        private var instance: ThumbnailStore? = null

        fun of(context: Context): ThumbnailStore =
            instance ?: synchronized(this) {
                instance ?: ThumbnailStore(context.applicationContext).also { instance = it }
            }
    }
}
