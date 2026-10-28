package com.hawkynt.mediadroid.sources

import com.hawkynt.mediadroid.library.FsFile
import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient

/**
 * An FTP server — or FTPS, FTP with explicit TLS — through Apache Commons Net, in passive mode.
 * Locations are `ftp://host[:port]/path` (`ftps://…`). Listing shares one control connection; every
 * opened file gets one of its own, since FTP cannot list while it transfers.
 */
class FtpFs(private val source: Source, private val secrets: Secrets) : RemoteFs {

    private var lister: FTPClient? = null
    private val base: String = "/" + source.path.trim('/')

    override val root: FsFile get() = FtpFile(this, base, isDirectory = true, size = 0, modified = 0)

    /** A new logged-in connection; the caller closes it. */
    internal fun connect(): FTPClient {
        val client = if (source.kind == SourceKind.FTPS) FTPSClient(false) else FTPClient()
        client.controlEncoding = "UTF-8"
        client.connectTimeout = TIMEOUT_MS
        client.setDefaultTimeout(TIMEOUT_MS)
        client.connect(source.host, if (source.port > 0) source.port else DEFAULT_PORT)
        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
            client.disconnect()
            throw IOException("${source.host} refused the connection: ${client.replyString.trim()}")
        }
        if (client is FTPSClient) {
            client.execPBSZ(0)
            client.execPROT("P")
        }
        val user = source.user.ifEmpty { "anonymous" }
        val password = secrets.secretFor(source.id) ?: if (source.user.isEmpty()) "mediadroid@" else ""
        if (!client.login(user, password)) {
            client.disconnect()
            throw IOException("${source.host} did not accept $user: ${client.replyString.trim()}")
        }
        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
        return client
    }

    /** Runs [block] on the shared listing connection, connecting again once when it was dropped. */
    @Synchronized
    internal fun <T> listing(block: (FTPClient) -> T): T {
        val client = lister?.takeIf { it.isConnected } ?: connect().also { lister = it }
        return try {
            block(client)
        } catch (e: IOException) {
            runCatching { client.disconnect() }
            lister = null
            block(connect().also { lister = it })
        }
    }

    /** The location of [path] (absolute on the server). */
    internal fun locationOf(path: String): String = buildString {
        append(if (source.kind == SourceKind.FTPS) "ftps" else "ftp").append("://").append(source.host)
        if (source.port > 0) append(':').append(source.port)
        append(path.ifEmpty { "/" })
    }.trimEnd('/')

    override fun resolve(location: String): FsFile? {
        val prefix = locationOf("")
        if (!location.startsWith("$prefix/")) return null
        val path = location.removePrefix(prefix)
        return runCatching {
            listing { client ->
                val parent = path.substringBeforeLast('/').ifEmpty { "/" }
                client.listFiles(parent).firstOrNull { it.name == path.substringAfterLast('/') }
                    ?.let { entryOf(path, it) }
            }
        }.getOrNull()
    }

    internal fun entryOf(path: String, file: FTPFile) = FtpFile(
        this,
        path,
        isDirectory = file.isDirectory,
        size = file.size.coerceAtLeast(0),
        modified = file.timestamp?.timeInMillis ?: 0,
    )

    @Synchronized
    override fun close() {
        lister?.let { runCatching { it.logout() }; runCatching { it.disconnect() } }
        lister = null
    }

    private companion object {
        const val DEFAULT_PORT = 21
        const val TIMEOUT_MS = 20_000
    }
}

/** A file or folder on an FTP server; [serverPath] is absolute on the server. */
internal class FtpFile(
    private val fs: FtpFs,
    private val serverPath: String,
    override val isDirectory: Boolean,
    private val size: Long,
    private val modified: Long,
) : FsFile {
    override val name: String get() = serverPath.substringAfterLast('/')
    override val path: String get() = fs.locationOf(serverPath)
    override val parentFile: FsFile?
        get() = if (serverPath.trim('/').isEmpty()) {
            null
        } else {
            FtpFile(fs, serverPath.substringBeforeLast('/'), true, 0, 0)
        }

    override fun length(): Long = size

    override fun lastModified(): Long = modified

    override fun listFiles(): List<FsFile>? = runCatching {
        fs.listing { client ->
            client.listFiles(serverPath.ifEmpty { "/" })
                .filter { it.name != "." && it.name != ".." }
                .map { fs.entryOf(serverPath.trimEnd('/') + "/" + it.name, it) }
        }
    }.getOrNull()

    override fun child(name: String): FsFile = FtpFile(fs, serverPath.trimEnd('/') + "/" + name, false, 0, 0)

    override fun open(offset: Long): InputStream {
        val client = fs.connect()
        if (offset > 0) client.restartOffset = offset
        val stream = client.retrieveFileStream(serverPath) ?: run {
            val reply = client.replyString.trim()
            client.disconnect()
            throw IOException("Cannot read $serverPath: $reply")
        }
        return object : FilterInputStream(stream) {
            override fun close() {
                runCatching { super.close() }
                runCatching { client.abort() }
                runCatching { client.disconnect() }
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is FtpFile && other.path == path

    override fun hashCode(): Int = path.hashCode()

    override fun toString(): String = path
}
