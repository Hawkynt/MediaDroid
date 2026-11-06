package com.hawkynt.mediadroid.player

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * A network's files in a folder of their own (see NeuralModels): a file is kept only once its size and digest are
 * right, written aside first, so a broken or interrupted copy never counts. Fetched from the model repository
 * ([fetch]) or unpacked from what the app carries ([unpack]); after that, the files' sizes are enough ([ready]).
 */
object ModelFolder {
    /** Whether all of [download]'s files are in [dir] at their sizes (checked in full when they were put there). */
    fun ready(dir: File, download: NeuralModels.Download): Boolean =
        download.files.all { File(dir, it.name).length() == it.bytes }

    /**
     * Fetches whatever of [download] is missing into [dir], telling [progress] how far (0..1); false where a file
     * could not be fetched or did not match. [open] opens a file's URL (HTTP by default). Blocks.
     */
    fun fetch(
        dir: File,
        download: NeuralModels.Download,
        progress: (Float) -> Unit,
        open: (String) -> InputStream = ::http,
    ): Boolean {
        download.archive?.let { return fromArchive(dir, download, it, progress, open) }
        var done = 0L
        return put(dir, download, { file -> open(download.url(file)) }) { n ->
            done += n
            progress((done.toFloat() / download.bytes).coerceAtMost(1f))
        }.also { if (it) progress(1f) }
    }

    /**
     * Unpacks whatever of [download] is missing or not as it should be in [dir] from what the app carries: [open]
     * gives a carried file by name (null where it is not carried). False where a file is not there or does not match.
     */
    fun unpack(dir: File, download: NeuralModels.Download, open: (String) -> InputStream?): Boolean =
        put(dir, download, { file -> open(file.name) ?: error("${file.name} is not carried") }) {}

    private fun put(
        dir: File,
        download: NeuralModels.Download,
        open: (NeuralModels.File) -> InputStream,
        copied: (Int) -> Unit,
    ): Boolean {
        dir.mkdirs()
        for (file in download.files) {
            val target = File(dir, file.name)
            if (target.length() == file.bytes && file.matches(target.inputStream())) {
                copied(file.bytes.toInt())
                continue
            }
            val part = File(dir, "${file.name}.part")
            val ok = runCatching {
                open(file).use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            copied(n)
                        }
                    }
                }
                target.delete()
                file.matches(part.inputStream()) && part.renameTo(target)
            }.getOrDefault(false)
            part.delete()
            if (!ok) return false
        }
        return true
    }

    // [download]'s files taken out of the release zip at its source, read as it streams in: each written aside and kept
    // only with its own size and digest; nothing fetched where all are there already.
    private fun fromArchive(
        dir: File,
        download: NeuralModels.Download,
        archive: NeuralModels.Archive,
        progress: (Float) -> Unit,
        open: (String) -> InputStream,
    ): Boolean {
        if (ready(dir, download) && download.files.all { it.matches(File(dir, it.name).inputStream()) }) return true
        dir.mkdirs()
        val wanted = download.files.associateBy { archive.prefix + it.name }
        val kept = HashSet<String>()
        runCatching {
            val counting = object : java.io.FilterInputStream(open(download.source)) {
                var read = 0L
                override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { count(it) }
                private fun count(n: Int) {
                    if (n <= 0) return
                    read += n
                    progress((read.toFloat() / archive.bytes).coerceAtMost(1f))
                }
            }
            java.util.zip.ZipInputStream(counting).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val file = wanted[entry.name] ?: continue
                    val part = File(dir, "${file.name}.part")
                    part.outputStream().use { zip.copyTo(it) }
                    val target = File(dir, file.name)
                    target.delete()
                    if (file.matches(part.inputStream()) && part.renameTo(target)) kept += file.name
                    part.delete()
                }
            }
        }
        val ok = download.files.all { it.name in kept }
        if (ok) progress(1f) else download.files.forEach { File(dir, it.name).delete() }
        return ok
    }

    private fun http(url: String): InputStream {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        return connection.inputStream
    }
}
