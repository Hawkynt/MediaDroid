package com.hawkynt.mediadroid.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.hawkynt.mediadroid.library.FsFile
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/** The URI a stored location plays from: a file URI for a path on the device, the location itself otherwise. */
fun playableUri(location: String): Uri =
    if (location.startsWith("/")) Uri.fromFile(File(location)) else Uri.parse(location)

/** Whether a location lives on a network source, read through [SourceDataSource]. */
fun isNetworkLocation(uri: Uri): Boolean = uri.scheme in NETWORK_SCHEMES

private val NETWORK_SCHEMES = setOf("smb", "nfs", "ftp", "ftps", "sftp")

/**
 * Media3's [DataSource] for videos on a network source (`smb://…`): the bytes come through the
 * source's [FsFile], opened at the position Media3 asks for, so seeking re-opens further in.
 */
@OptIn(UnstableApi::class)
class SourceDataSource(private val files: (String) -> FsFile?) : BaseDataSource(true) {

    private var uri: Uri? = null
    private var stream: InputStream? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val file = files(dataSpec.uri.toString()) ?: throw DataSourceException(
            "Not reachable right now: ${dataSpec.uri}",
            null,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        )
        stream = try {
            BufferedInputStream(file.open(dataSpec.position), READ_BUFFER)
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        val length = file.length()
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            length > 0 -> length - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val wanted = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        val read = try {
            stream!!.read(buffer, offset, wanted)
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (read < 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        runCatching { stream?.close() }
        stream = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /**
     * Network locations through a [SourceDataSource], everything else — files, content URIs — through
     * Media3's [DefaultDataSource], decided per opened URI.
     */
    class Factory(context: Context, private val files: (String) -> FsFile?) : DataSource.Factory {
        private val local = DefaultDataSource.Factory(context)

        override fun createDataSource(): DataSource = Routing(local.createDataSource(), SourceDataSource(files))
    }

    private class Routing(private val local: DataSource, private val network: DataSource) : DataSource {
        private var current: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            local.addTransferListener(transferListener)
            network.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val source = if (isNetworkLocation(dataSpec.uri)) network else local
            current = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)

        override fun getUri(): Uri? = current?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

        override fun close() {
            current?.close()
            current = null
        }
    }

    private companion object {
        const val READ_BUFFER = 256 * 1024
    }
}
