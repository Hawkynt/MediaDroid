package com.hawkynt.mediadroid.sources

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/** XDR encoding (RFC 4506): big-endian, every item padded to four bytes. */
internal class XdrWriter {
    private val bytes = ByteArrayOutputStream()
    private val out = DataOutputStream(bytes)

    fun int(value: Int) = apply { out.writeInt(value) }

    fun long(value: Long) = apply { out.writeLong(value) }

    fun bool(value: Boolean) = int(if (value) 1 else 0)

    /** Variable-length opaque data: its length, the bytes, padding to four. */
    fun opaque(value: ByteArray) = apply {
        int(value.size)
        fixed(value)
    }

    /** Fixed-length opaque data: the bytes and padding to four. */
    fun fixed(value: ByteArray) = apply {
        out.write(value)
        repeat((4 - value.size % 4) % 4) { out.write(0) }
    }

    fun string(value: String) = opaque(value.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = bytes.toByteArray()
}

/** XDR decoding (RFC 4506). */
internal class XdrReader(private val buffer: ByteBuffer) {
    fun int(): Int = buffer.int

    fun long(): Long = buffer.long

    fun bool(): Boolean = int() != 0

    fun fixed(length: Int): ByteArray {
        val value = ByteArray(length)
        buffer.get(value)
        buffer.position(buffer.position() + (4 - length % 4) % 4)
        return value
    }

    fun opaque(): ByteArray = fixed(int())

    fun string(): String = String(opaque(), Charsets.UTF_8)

    fun skip(bytes: Int) {
        buffer.position(buffer.position() + bytes)
    }
}

/**
 * An ONC RPC client (RFC 5531) over one TCP connection, with record marking: calls go out with
 * AUTH_SYS credentials (the [uid] and [gid] the server sees) and wait for their reply.
 */
internal class RpcClient(host: String, port: Int, private val uid: Int, private val gid: Int) : AutoCloseable {

    private val socket = Socket().apply {
        connect(InetSocketAddress(host, port), TIMEOUT_MS)
        soTimeout = TIMEOUT_MS
        tcpNoDelay = true
    }
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private var xid = (System.nanoTime() and 0x7fffffff).toInt()

    /** Calls [procedure] of [program] [version] with [arguments]; returns the reader at its results. */
    @Synchronized
    fun call(program: Int, version: Int, procedure: Int, arguments: ByteArray): XdrReader {
        val id = ++xid
        val auth = XdrWriter().int(0).string(MACHINE).int(uid).int(gid).int(0).toByteArray()
        val message = XdrWriter()
            .int(id).int(CALL).int(RPC_VERSION)
            .int(program).int(version).int(procedure)
            .int(AUTH_SYS).opaque(auth)
            .int(AUTH_NONE).opaque(ByteArray(0))
            .toByteArray() + arguments
        output.writeInt(LAST_FRAGMENT or message.size)
        output.write(message)
        output.flush()
        while (true) {
            val reply = XdrReader(ByteBuffer.wrap(readRecord()))
            if (reply.int() != id) continue // an answer to an earlier, abandoned call
            if (reply.int() != REPLY) throw IOException("RPC: not a reply")
            if (reply.int() != MSG_ACCEPTED) throw IOException("RPC: call rejected (authentication or version)")
            reply.int() // verifier flavour
            reply.opaque() // verifier body
            val accepted = reply.int()
            if (accepted != SUCCESS) {
                throw IOException("RPC: program $program answered ${ACCEPT_STATES.getOrElse(accepted) { "$accepted" }}")
            }
            return reply
        }
    }

    private fun readRecord(): ByteArray {
        val record = ByteArrayOutputStream()
        do {
            val header = input.readInt()
            val chunk = ByteArray(header and LAST_FRAGMENT.inv())
            input.readFully(chunk)
            record.write(chunk)
        } while (header and LAST_FRAGMENT == 0)
        return record.toByteArray()
    }

    override fun close() = socket.close()

    companion object {
        private const val TIMEOUT_MS = 20_000
        private const val LAST_FRAGMENT = 0x80000000.toInt()
        private const val RPC_VERSION = 2
        private const val CALL = 0
        private const val REPLY = 1
        private const val MSG_ACCEPTED = 0
        private const val SUCCESS = 0
        private const val AUTH_NONE = 0
        private const val AUTH_SYS = 1
        private const val MACHINE = "mediadroid"
        private val ACCEPT_STATES =
            listOf("SUCCESS", "PROG_UNAVAIL", "PROG_MISMATCH", "PROC_UNAVAIL", "GARBAGE_ARGS", "SYSTEM_ERR")

        /** Portmap (RFC 1833, program 100000 version 2, GETPORT): the TCP port [program] [version] listens on. */
        fun portOf(host: String, program: Int, version: Int): Int =
            RpcClient(host, PORTMAP_PORT, 0, 0).use { portmap ->
                val arguments = XdrWriter().int(program).int(version).int(IPPROTO_TCP).int(0).toByteArray()
                portmap.call(PORTMAP, 2, PMAPPROC_GETPORT, arguments).int()
                    .takeIf { it > 0 } ?: throw IOException("$host does not offer program $program over TCP")
            }

        private const val PORTMAP = 100000
        private const val PORTMAP_PORT = 111
        private const val PMAPPROC_GETPORT = 3
        private const val IPPROTO_TCP = 6
    }
}
