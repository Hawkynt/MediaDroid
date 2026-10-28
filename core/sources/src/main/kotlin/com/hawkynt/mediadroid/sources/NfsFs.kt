package com.hawkynt.mediadroid.sources

import com.hawkynt.mediadroid.library.FsFile
import com.hawkynt.mediadroid.library.Source
import java.io.IOException
import java.io.InputStream

/**
 * An NFS export, over NFSv3 (RFC 1813) and its MOUNT protocol on our own ONC RPC client. [Source.path]
 * is the export (or a folder in it); locations are `nfs://host[:port]/export/…`. The ports come from
 * the server's portmapper, or both from [Source.port] when it is set (as a userspace server serves
 * MOUNT and NFS on one). The server sees AUTH_SYS credentials: [Source.user] as `uid` or `uid:gid`,
 * else nobody (65534). The connection comes from an unprivileged port, which the export must allow
 * (`insecure` on Linux, "allow connections from non-privileged ports" on a NAS).
 */
class NfsFs(private val source: Source) : RemoteFs {

    private val uid: Int
    private val gid: Int

    init {
        val ids = source.user.split(':').mapNotNull { it.trim().toIntOrNull() }
        uid = ids.getOrElse(0) { NOBODY }
        gid = ids.getOrElse(1) { uid }
    }

    private val exportPath: String = "/" + source.path.trim('/')
    private var nfs: RpcClient? = null
    private var rootHandle: ByteArray? = null

    override val root: FsFile get() = NfsFile(this, "", handle = null, attributes = null)

    @Synchronized
    private fun connection(): Pair<RpcClient, ByteArray> {
        val open = nfs
        val handle = rootHandle
        if (open != null && handle != null) return open to handle
        close()
        val mountPort = if (source.port > 0) source.port else RpcClient.portOf(source.host, MOUNT, VERSION)
        val mounted = RpcClient(source.host, mountPort, uid, gid).use { mount ->
            val reply = mount.call(MOUNT, VERSION, MOUNTPROC3_MNT, XdrWriter().string(exportPath).toByteArray())
            val status = reply.int()
            if (status != 0) throw IOException("${source.host} refused to mount $exportPath (MOUNT status $status)")
            reply.opaque()
        }
        val nfsPort = if (source.port > 0) source.port else RpcClient.portOf(source.host, NFS, VERSION)
        val client = RpcClient(source.host, nfsPort, uid, gid)
        nfs = client
        rootHandle = mounted
        return client to mounted
    }

    /** Calls NFSv3 [procedure]; returns the reader after a good status, throws on a bad one. */
    private fun call(procedure: Int, arguments: ByteArray): XdrReader {
        val (client, _) = connection()
        val reply = try {
            client.call(NFS, VERSION, procedure, arguments)
        } catch (e: IOException) {
            close()
            throw e
        }
        val status = reply.int()
        if (status != 0) throw IOException("NFS procedure $procedure failed with status $status")
        return reply
    }

    internal fun rootHandle(): ByteArray = connection().second

    /** The handle and attributes of [name] in the folder [directory]. */
    internal fun lookup(directory: ByteArray, name: String): Pair<ByteArray, Attributes?> {
        val reply = call(NFSPROC3_LOOKUP, XdrWriter().opaque(directory).string(name).toByteArray())
        val handle = reply.opaque()
        return handle to postOpAttributes(reply)
    }

    internal fun getattr(handle: ByteArray): Attributes =
        attributes(call(NFSPROC3_GETATTR, XdrWriter().opaque(handle).toByteArray()))

    /** Every entry of the folder [directory], with its handle and attributes where the server sent them. */
    internal fun readdirplus(directory: ByteArray): List<Triple<String, ByteArray?, Attributes?>> {
        val entries = ArrayList<Triple<String, ByteArray?, Attributes?>>()
        var cookie = 0L
        var verifier = ByteArray(8)
        while (true) {
            val reply = call(
                NFSPROC3_READDIRPLUS,
                XdrWriter().opaque(directory).long(cookie).fixed(verifier).int(DIR_COUNT).int(MAX_COUNT).toByteArray(),
            )
            postOpAttributes(reply)
            verifier = reply.fixed(8)
            while (reply.bool()) {
                reply.long() // fileid
                val name = reply.string()
                cookie = reply.long()
                val attributes = postOpAttributes(reply)
                val handle = if (reply.bool()) reply.opaque() else null
                if (name != "." && name != "..") entries += Triple(name, handle, attributes)
            }
            if (reply.bool()) return entries
        }
    }

    /** Up to [count] bytes of [handle] from [offset]; empty at the end. */
    internal fun read(handle: ByteArray, offset: Long, count: Int): ByteArray {
        val reply = call(NFSPROC3_READ, XdrWriter().opaque(handle).long(offset).int(count).toByteArray())
        postOpAttributes(reply)
        reply.int() // count
        reply.bool() // eof
        return reply.opaque()
    }

    /** The location of [relative] (a path below the export). */
    internal fun locationOf(relative: String): String = buildString {
        append("nfs://").append(source.host)
        if (source.port > 0) append(':').append(source.port)
        append(exportPath.trimEnd('/'))
        if (relative.isNotEmpty()) append('/').append(relative)
    }

    override fun resolve(location: String): FsFile? {
        val prefix = locationOf("")
        if (location != prefix && !location.startsWith("$prefix/")) return null
        val relative = location.removePrefix(prefix).trim('/')
        return runCatching {
            var handle = rootHandle()
            var attributes: Attributes? = null
            for (name in relative.split('/').filter { it.isNotEmpty() }) {
                val (next, found) = lookup(handle, name)
                handle = next
                attributes = found
            }
            NfsFile(this, relative, handle, attributes ?: getattr(handle))
        }.getOrNull()
    }

    @Synchronized
    override fun close() {
        runCatching { nfs?.close() }
        nfs = null
        rootHandle = null
    }

    /** The parts of `fattr3` a file needs. */
    internal data class Attributes(val isDirectory: Boolean, val size: Long, val modifiedMs: Long)

    private fun attributes(reply: XdrReader): Attributes {
        val type = reply.int()
        reply.skip(4 * 4) // mode, nlink, uid, gid
        val size = reply.long()
        reply.skip(8 + 8 + 8 + 8) // used, rdev (specdata3), fsid, fileid
        reply.skip(8) // atime
        val seconds = reply.int().toLong() and 0xffffffffL
        val nanos = reply.int().toLong() and 0xffffffffL
        reply.skip(8) // ctime
        return Attributes(type == NF3DIR, size, seconds * 1000 + nanos / 1_000_000)
    }

    private fun postOpAttributes(reply: XdrReader): Attributes? = if (reply.bool()) attributes(reply) else null

    private companion object {
        const val NFS = 100003
        const val MOUNT = 100005
        const val VERSION = 3
        const val MOUNTPROC3_MNT = 1
        const val NFSPROC3_GETATTR = 1
        const val NFSPROC3_LOOKUP = 3
        const val NFSPROC3_READ = 6
        const val NFSPROC3_READDIRPLUS = 17
        const val NF3DIR = 2
        const val NOBODY = 65534
        const val DIR_COUNT = 8192
        const val MAX_COUNT = 65536
    }
}

/** A file or folder on an NFS export; [relative] is its path below the export. */
internal class NfsFile(
    private val fs: NfsFs,
    private val relative: String,
    private var handle: ByteArray?,
    private var attributes: NfsFs.Attributes?,
) : FsFile {
    override val name: String get() = relative.substringAfterLast('/')
    override val path: String get() = fs.locationOf(relative)
    override val isDirectory: Boolean get() = attributes()?.isDirectory ?: relative.isEmpty()
    override val parentFile: FsFile?
        get() = if (relative.isEmpty()) null else NfsFile(fs, relative.substringBeforeLast('/', ""), null, null)

    private fun handle(): ByteArray = handle ?: run {
        var current = fs.rootHandle()
        for (part in relative.split('/').filter { it.isNotEmpty() }) current = fs.lookup(current, part).first
        current.also { handle = it }
    }

    private fun attributes(): NfsFs.Attributes? =
        attributes ?: runCatching { fs.getattr(handle()) }.getOrNull()?.also { attributes = it }

    override fun length(): Long = attributes()?.size ?: 0

    override fun lastModified(): Long = attributes()?.modifiedMs ?: 0

    override fun listFiles(): List<FsFile>? = runCatching {
        fs.readdirplus(handle()).map { (name, childHandle, childAttributes) ->
            NfsFile(fs, if (relative.isEmpty()) name else "$relative/$name", childHandle, childAttributes)
        }
    }.getOrNull()

    override fun child(name: String): FsFile =
        NfsFile(fs, if (relative.isEmpty()) name else "$relative/$name", null, null)

    override fun open(offset: Long): InputStream {
        val file = handle()
        return object : InputStream() {
            private var position = offset
            private var chunk = ByteArray(0)
            private var index = 0
            private var ended = false

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (index >= chunk.size) {
                    if (ended) return -1
                    chunk = fs.read(file, position, READ_SIZE)
                    index = 0
                    position += chunk.size
                    if (chunk.isEmpty()) {
                        ended = true
                        return -1
                    }
                }
                val n = minOf(len, chunk.size - index)
                System.arraycopy(chunk, index, buffer, off, n)
                index += n
                return n
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is NfsFile && other.path == path

    override fun hashCode(): Int = path.hashCode()

    override fun toString(): String = path

    private companion object {
        const val READ_SIZE = 65536
    }
}
