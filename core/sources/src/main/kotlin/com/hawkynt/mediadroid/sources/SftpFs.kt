package com.hawkynt.mediadroid.sources

import com.hawkynt.mediadroid.library.FsFile
import com.hawkynt.mediadroid.library.Source
import java.io.IOException
import java.io.InputStream
import java.security.PublicKey
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier

/**
 * A folder on an SSH server, over SFTP through sshj. Locations are `sftp://host[:port]/path`. The
 * server's host key is trusted on first use: its fingerprint is kept in [hostKey] — saved with the
 * [Source] — and every later connection must show the same key. Signs in with the password, or with
 * the private key when the secret is one (`-----BEGIN …`).
 */
class SftpFs(private val source: Source, private val secrets: Secrets) : RemoteFs {

    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null
    private val base: String = "/" + source.path.trim('/')

    /** The host key fingerprint the server showed; to be saved with the source after the first connection. */
    @Volatile
    var hostKey: String = source.hostKey
        private set

    override val root: FsFile get() = SftpFile(this, base, isDirectory = true, size = 0, modified = 0)

    @Synchronized
    internal fun sftp(): SFTPClient {
        sftp?.takeIf { ssh?.isConnected == true }?.let { return it }
        close()
        val client = SSHClient()
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val fingerprint = SecurityUtils.getFingerprint(key)
                if (source.hostKey.isNotEmpty() && source.hostKey != fingerprint) return false
                hostKey = fingerprint
                return true
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        client.connectTimeout = TIMEOUT_MS
        client.timeout = TIMEOUT_MS
        try {
            client.connect(source.host, if (source.port > 0) source.port else DEFAULT_PORT)
            val secret = secrets.secretFor(source.id).orEmpty()
            if (secret.trimStart().startsWith("-----BEGIN")) {
                client.authPublickey(source.user, client.loadKeys(secret, null, null))
            } else {
                client.authPassword(source.user, secret)
            }
            val opened = client.newSFTPClient()
            ssh = client
            sftp = opened
            return opened
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    /** The location of [path] (absolute on the server). */
    internal fun locationOf(path: String): String = buildString {
        append("sftp://").append(source.host)
        if (source.port > 0) append(':').append(source.port)
        append(path.ifEmpty { "/" })
    }.trimEnd('/')

    override fun resolve(location: String): FsFile? {
        val prefix = locationOf("")
        if (location.trimEnd('/') == locationOf(base).trimEnd('/')) return root
        if (!location.startsWith("$prefix/")) return null
        val path = location.removePrefix(prefix)
        return runCatching {
            val attributes = sftp().stat(path)
            SftpFile(
                this,
                path,
                isDirectory = attributes.type == FileMode.Type.DIRECTORY,
                size = attributes.size,
                modified = attributes.mtime * 1000,
            )
        }.getOrNull()
    }

    @Synchronized
    override fun close() {
        runCatching { sftp?.close() }
        runCatching { ssh?.disconnect() }
        sftp = null
        ssh = null
    }

    private companion object {
        const val DEFAULT_PORT = 22
        const val TIMEOUT_MS = 20_000
    }
}

/** A file or folder on an SFTP server; [serverPath] is absolute on the server. */
internal class SftpFile(
    private val fs: SftpFs,
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
            SftpFile(fs, serverPath.substringBeforeLast('/'), true, 0, 0)
        }

    override fun length(): Long = size

    override fun lastModified(): Long = modified

    override fun listFiles(): List<FsFile>? = runCatching {
        fs.sftp().ls(serverPath.ifEmpty { "/" }).map {
            SftpFile(
                fs,
                serverPath.trimEnd('/') + "/" + it.name,
                isDirectory = it.isDirectory,
                size = it.attributes.size,
                modified = it.attributes.mtime * 1000,
            )
        }
    }.getOrNull()

    override fun child(name: String): FsFile = SftpFile(fs, serverPath.trimEnd('/') + "/" + name, false, 0, 0)

    override fun open(offset: Long): InputStream {
        val file: RemoteFile = fs.sftp().open(serverPath)
        val stream = file.RemoteFileInputStream(offset)
        return object : InputStream() {
            override fun read(): Int = stream.read()

            override fun read(buffer: ByteArray, off: Int, len: Int): Int = stream.read(buffer, off, len)

            override fun skip(n: Long): Long = stream.skip(n)

            override fun close() {
                runCatching { stream.close() }
                runCatching { file.close() }
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is SftpFile && other.path == path

    override fun hashCode(): Int = path.hashCode()

    override fun toString(): String = path
}
