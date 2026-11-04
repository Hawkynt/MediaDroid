package com.hawkynt.mediadroid.data

import android.content.Context
import com.hawkynt.mediadroid.player.NeuralModels
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * The downloaded neural networks (see NeuralModels), one folder each in the app's own files: a file is kept only once
 * its size and digest are right, so a broken or interrupted download never counts.
 */
object ModelStore {
    /** Where [download]'s files are kept. */
    fun folder(context: Context, download: NeuralModels.Download): File =
        File(context.filesDir, "models/${download.folder}")

    /** Whether all of [download]'s files are there at their sizes (checked in full when they were fetched). */
    fun ready(context: Context, download: NeuralModels.Download): Boolean {
        val dir = folder(context, download)
        return download.files.all { File(dir, it.name).length() == it.bytes }
    }

    /**
     * Fetches whatever of [download] is missing, telling [progress] how far (0..1); false where a file could not be
     * fetched or did not match. Blocks; not on the main thread.
     */
    fun fetch(context: Context, download: NeuralModels.Download, progress: (Float) -> Unit): Boolean {
        val dir = folder(context, download).apply { mkdirs() }
        var done = 0L
        for (file in download.files) {
            val target = File(dir, file.name)
            if (target.length() != file.bytes || !file.matches(target.inputStream())) {
                val part = File(dir, "${file.name}.part")
                val fetched = runCatching {
                    val connection = URI(download.url(file)).toURL().openConnection() as HttpURLConnection
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                progress((done.toFloat() / download.bytes).coerceAtMost(1f))
                            }
                        }
                    }
                    file.matches(part.inputStream()) && part.renameTo(target)
                }.getOrDefault(false)
                part.delete()
                if (!fetched) return false
            } else {
                done += file.bytes
            }
        }
        progress(1f)
        return true
    }
}
