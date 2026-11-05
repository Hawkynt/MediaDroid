package com.hawkynt.mediadroid.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.hawkynt.mediadroid.ffmpeg.FfmpegDemuxer
import com.hawkynt.mediadroid.player.FfmpegInputs
import com.hawkynt.mediadroid.player.RecentCache
import com.hawkynt.mediadroid.player.SeekPreview
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Seek-bar thumbnails: the picture at the moment under the finger while seeking (see [SeekPreview] for
 * the steps and the cache rules). Pictures come from the file the way [ThumbnailStore]'s do — Android's
 * retriever, else the bundled FFmpeg — through the same source access, so files on shares work too.
 *
 * All the work runs on one thread of its own at background priority, one picture at a time, so seeking
 * never competes with playback for more than a core; a request whose finger has moved on is dropped
 * before it starts. Each picture is kept in memory and as a small JPEG on disk.
 */
class SeekPreviews private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(context.cacheDir, "seek").apply { mkdirs() }
    private val memory = RecentCache<String, Bitmap>(MEMORY_PICTURES)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "seek-preview")
    }.asCoroutineDispatcher()

    /** Starts previewing [videoPath]; close the session when the video changes or the player leaves. */
    fun open(videoPath: String): Session = Session(videoPath)

    /** The pictures of one video, its file opened once for all of them. */
    inner class Session internal constructor(private val videoPath: String) : AutoCloseable {
        @Volatile
        private var folder: File? = null
        private var probed = Probe.UNKNOWN
        private var retriever: MediaMetadataRetriever? = null
        private var demuxer: FfmpegDemuxer? = null
        private var local: RandomAccessFile? = null

        @Volatile
        private var closed = false

        /** A picture already in memory for [bucket] or a step near it, to show at once; never touches the file. */
        fun keptNear(bucket: Int, durationMs: Long): Bitmap? {
            val prefix = folder?.name ?: return null
            val kept = synchronized(memory) {
                memory.keys().filter { it.startsWith("$prefix/") }
                    .mapNotNull { key -> key.substringAfter('/').toLongOrNull() }
                    .associateBy { SeekPreview.bucketOf(it, durationMs) }
            }
            val near = SeekPreview.standIn(bucket, kept.keys) ?: return null
            return synchronized(memory) { memory["$prefix/${kept[near]}"] }
        }

        /** The picture for [bucket]: from memory, from disk, or taken from the file. Null when it yields none. */
        suspend fun pictureAt(bucket: Int, durationMs: Long): Bitmap? = withContext(worker) {
            ensureActive()
            if (closed) return@withContext null
            val dir = folder ?: fileFolder()?.also { folder = it } ?: return@withContext null
            val atMs = SeekPreview.frameTimeMs(bucket, durationMs)
            val key = "${dir.name}/$atMs"
            synchronized(memory) { memory[key] }?.let { return@withContext it }
            val cached = File(dir, SeekPreview.pictureName(atMs))
            val picture = read(cached) ?: run {
                // The finger may have moved on while this waited for the thread: then the file is not read.
                ensureActive()
                take(atMs)?.also { write(it, cached) }
            } ?: return@withContext null
            synchronized(memory) { memory.put(key, picture) }
            picture
        }

        /** Lets go of the open file and trims the disk cache, on the worker so a running extraction finishes first. */
        override fun close() {
            closed = true
            worker.executor.execute {
                runCatching { retriever?.release() }
                runCatching { demuxer?.close() }
                runCatching { local?.close() }
                retriever = null
                demuxer = null
                local = null
                trim()
            }
        }

        private fun fileFolder(): File? {
            val modified = if (videoPath.startsWith("/")) {
                File(videoPath).lastModified()
            } else {
                SourceAccess.resolve(appContext, videoPath)?.lastModified() ?: return null
            }
            return File(root, SeekPreview.fileKey(videoPath, modified)).apply { mkdirs() }
        }

        private fun read(file: File): Bitmap? {
            if (!file.isFile) return null
            file.setLastModified(System.currentTimeMillis())
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            return BitmapFactory.decodeFile(file.path, options)
        }

        private fun write(picture: Bitmap, file: File) {
            runCatching { file.outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) } }
                .onFailure { file.delete() }
        }

        /**
         * The picture at [atMs] from the file. Which reader works is found out on the first picture and kept:
         * Android's retriever when it yields one, else FFmpeg, else none for the rest of the session.
         */
        private fun take(atMs: Long): Bitmap? {
            if (probed == Probe.UNKNOWN || probed == Probe.ANDROID) {
                val picture = runCatching { androidFrame(atMs) }.getOrNull()
                if (picture != null || probed == Probe.ANDROID) {
                    probed = Probe.ANDROID
                    return picture
                }
                runCatching { retriever?.release() }
                retriever = null
                probed = Probe.FFMPEG
            }
            if (probed == Probe.FFMPEG) {
                val picture = runCatching { ffmpegFrame(atMs) }.getOrNull()
                if (picture == null && demuxer == null) probed = Probe.NONE
                return picture
            }
            return null
        }

        private fun androidFrame(atMs: Long): Bitmap? {
            val opened = retriever ?: run {
                val remote = if (videoPath.startsWith("/")) null else SourceAccess.resolve(appContext, videoPath)
                if (!videoPath.startsWith("/") && remote == null) return null
                MediaMetadataRetriever().also { fresh ->
                    retriever = fresh
                    if (remote == null) fresh.setDataSource(videoPath) else fresh.setDataSource(FsMediaDataSource(remote))
                }
            }
            // The keyframe nearest the moment: no decoding up to the exact frame, which is what keeps it quick.
            val atUs = atMs * 1000
            val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            val frame = if (android.os.Build.VERSION.SDK_INT >= 27) {
                opened.getScaledFrameAtTime(atUs, option, MAX_SIDE, MAX_SIDE)
            } else {
                opened.getFrameAtTime(atUs, option)?.let(::scaleDown)
            }
            return frame?.let(::compact)
        }

        private fun ffmpegFrame(atMs: Long): Bitmap? {
            if (!FfmpegDemuxer.isAvailable()) return null
            val opened = demuxer ?: run {
                val input = if (videoPath.startsWith("/")) {
                    FfmpegInputs.local(RandomAccessFile(videoPath, "r").also { local = it })
                } else {
                    FfmpegInputs.remote(SourceAccess.resolve(appContext, videoPath) ?: return null)
                }
                FfmpegDemuxer.open(input)?.also { demuxer = it } ?: return null
            }
            val size = IntArray(2)
            val pixels = opened.frame(atMs * 1000, MAX_SIDE, size) ?: return null
            val frame = Bitmap.createBitmap(pixels, size[0], size[1], Bitmap.Config.ARGB_8888)
            return compact(scaleDown(frame))
        }

        private fun scaleDown(frame: Bitmap): Bitmap {
            if (frame.width <= MAX_SIDE && frame.height <= MAX_SIDE) return frame
            val factor = MAX_SIDE.toFloat() / maxOf(frame.width, frame.height)
            val scaled = Bitmap.createScaledBitmap(
                frame,
                (frame.width * factor).toInt().coerceAtLeast(1),
                (frame.height * factor).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== frame) frame.recycle()
            return scaled
        }

        /** Half the memory per picture: a seek preview needs no more colour than this. */
        private fun compact(frame: Bitmap): Bitmap {
            if (frame.config == Bitmap.Config.RGB_565) return frame
            val small = frame.copy(Bitmap.Config.RGB_565, false) ?: return frame
            frame.recycle()
            return small
        }
    }

    /** Keeps the disk cache within its budget, the longest unused pictures going first. */
    private fun trim() {
        val pictures = root.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().asList() }
        val kept = pictures.map { SeekPreview.Kept(it.path, it.length(), it.lastModified()) }
        SeekPreview.toLetGo(kept, DISK_BUDGET_BYTES).forEach { File(it).delete() }
        root.listFiles().orEmpty().filter { it.isDirectory && it.list().isNullOrEmpty() }.forEach { it.delete() }
    }

    private enum class Probe { UNKNOWN, ANDROID, FFMPEG, NONE }

    companion object {
        /** The longer side of a preview picture, in pixels. */
        private const val MAX_SIDE = 256
        private const val JPEG_QUALITY = 75
        private const val MEMORY_PICTURES = 64
        private const val DISK_BUDGET_BYTES = 32L * 1024 * 1024

        @Volatile
        private var instance: SeekPreviews? = null

        fun of(context: Context): SeekPreviews =
            instance ?: synchronized(this) {
                instance ?: SeekPreviews(context.applicationContext).also { instance = it }
            }
    }
}
