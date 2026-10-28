package com.hawkynt.mediadroid.sources

import com.hawkynt.mediadroid.library.FsFile
import com.hawkynt.mediadroid.library.Source
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * A Windows or Samba share (SMB 2/3) through smbj. [Source.path] is `/share/folder`; locations are
 * `smb://host[:port]/share/folder/…`. One connection per source, opened on first use and opened again
 * when the server dropped it.
 */
class SmbFs(private val source: Source, private val secrets: Secrets) : RemoteFs {

    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .withSoTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build(),
    )
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    private val shareName: String = source.path.trim('/').substringBefore('/')
    private val base: String = source.path.trim('/').substringAfter('/', "")

    override val root: FsFile get() = SmbFile(this, base, isDirectory = true, size = 0, modified = 0)

    @Synchronized
    internal fun share(): DiskShare {
        share?.takeIf { it.isConnected }?.let { return it }
        close()
        val opened = client.connect(source.host, if (source.port > 0) source.port else DEFAULT_PORT)
        val auth = if (source.user.isEmpty()) {
            AuthenticationContext.guest()
        } else {
            val (domain, user) = if ('\\' in source.user) {
                source.user.substringBefore('\\') to source.user.substringAfter('\\')
            } else {
                "" to source.user
            }
            AuthenticationContext(user, (secrets.secretFor(source.id) ?: "").toCharArray(), domain)
        }
        val newSession = opened.authenticate(auth)
        val newShare = newSession.connectShare(shareName) as? DiskShare
            ?: throw IOException("$shareName on ${source.host} is no file share.")
        connection = opened
        session = newSession
        share = newShare
        return newShare
    }

    /** The location of [inner] (a path within the share, `/`-separated). */
    internal fun locationOf(inner: String): String = buildString {
        append("smb://").append(source.host)
        if (source.port > 0) append(':').append(source.port)
        append('/').append(shareName)
        if (inner.isNotEmpty()) append('/').append(inner)
    }

    /** [inner] as smbj wants it: backslashes, no leading separator. */
    internal fun smbPath(inner: String): String = inner.replace('/', '\\')

    override fun resolve(location: String): FsFile? {
        val prefix = locationOf("")
        if (location != prefix && !location.startsWith("$prefix/")) return null
        val inner = location.removePrefix(prefix).trim('/')
        return runCatching {
            val info = share().getFileInformation(smbPath(inner))
            SmbFile(
                this,
                inner,
                isDirectory = info.standardInformation.isDirectory,
                size = info.standardInformation.endOfFile,
                modified = info.basicInformation.lastWriteTime.toEpochMillis(),
            )
        }.getOrNull()
    }

    @Synchronized
    override fun close() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        share = null
        session = null
        connection = null
    }

    private companion object {
        const val DEFAULT_PORT = 445
        const val TIMEOUT_S = 20L
    }
}

/** A file or folder on an SMB share; [inner] is its path within the share. */
internal class SmbFile(
    private val fs: SmbFs,
    private val inner: String,
    override val isDirectory: Boolean,
    private val size: Long,
    private val modified: Long,
) : FsFile {
    override val name: String get() = inner.substringAfterLast('/')
    override val path: String get() = fs.locationOf(inner)
    override val parentFile: FsFile?
        get() = if (inner.isEmpty()) null else SmbFile(fs, inner.substringBeforeLast('/', ""), true, 0, 0)

    override fun length(): Long = size

    override fun lastModified(): Long = modified

    override fun listFiles(): List<FsFile>? = runCatching {
        fs.share().list(fs.smbPath(inner))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                SmbFile(
                    fs,
                    if (inner.isEmpty()) it.fileName else "$inner/${it.fileName}",
                    isDirectory = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
                    size = it.endOfFile,
                    modified = it.lastWriteTime.toEpochMillis(),
                )
            }
    }.getOrNull()

    override fun child(name: String): FsFile =
        SmbFile(fs, if (inner.isEmpty()) name else "$inner/$name", isDirectory = false, size = 0, modified = 0)

    override fun open(offset: Long): InputStream {
        val file = fs.share().openFile(
            fs.smbPath(inner),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        return object : InputStream() {
            private var position = offset
            private val one = ByteArray(1)

            override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff

            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val n = file.read(buffer, position, off, len)
                if (n <= 0) return -1
                position += n
                return n
            }

            override fun skip(n: Long): Long {
                position += n
                return n
            }

            override fun close() = file.close()
        }
    }

    override fun equals(other: Any?): Boolean = other is SmbFile && other.path == path

    override fun hashCode(): Int = path.hashCode()

    override fun toString(): String = path
}
