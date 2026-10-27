package com.hawkynt.mediadroid.imaging

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * The TIFF compressions a scanner or editor writes. LZW and PackBits are converted from Apache
 * Commons Imaging (`MyLzwDecompressor`, `PackBits`, Apache-2.0, see THIRD_PARTY_NOTICES.md); the
 * LZW string table is kept as prefix links instead of byte arrays, which is the same algorithm
 * without allocating per code. Deflate is `java.util.zip`, which Android ships as well.
 */
internal object TiffCompression {

    const val NONE = 1
    const val LZW = 5
    const val DEFLATE = 8
    const val DEFLATE_OLD = 32946
    const val PACKBITS = 32773

    val supported = setOf(NONE, LZW, DEFLATE, DEFLATE_OLD, PACKBITS)

    /** [data] decompressed to (at most) [expected] bytes; short output is zero-padded by the caller. */
    fun decompress(compression: Int, data: ByteArray, expected: Int): ByteArray = when (compression) {
        NONE -> data
        LZW -> lzw(data, expected)
        DEFLATE, DEFLATE_OLD -> inflate(data, expected)
        PACKBITS -> packBits(data, expected)
        else -> error("compression $compression")
    }

    /** PackBits: a count n of 0..127 copies n+1 literal bytes, -1..-127 repeats the next byte 1-n times. */
    fun packBits(data: ByteArray, expected: Int): ByteArray {
        val out = ByteArray(expected)
        var pos = 0
        var written = 0
        while (pos < data.size && written < expected) {
            val n = data[pos++].toInt()
            when {
                n >= 0 -> repeat(n + 1) { if (pos < data.size && written < expected) out[written++] = data[pos++] }
                n != -128 -> {
                    if (pos >= data.size) break
                    val value = data[pos++]
                    repeat(1 - n) { if (written < expected) out[written++] = value }
                }
            }
        }
        return out
    }

    /**
     * TIFF LZW: codes are read most significant bit first, 256 clears the table, 257 ends the strip,
     * and the code width grows one code early (at 511, 1023, 2047) — the "early change" TIFF writers use.
     */
    fun lzw(data: ByteArray, expected: Int): ByteArray {
        val clear = 256
        val end = 257
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val length = IntArray(4096)
        for (i in 0 until 256) {
            suffix[i] = i.toByte()
            length[i] = 1
        }
        val out = ByteArray(expected)
        var written = 0
        var bitPos = 0L
        val totalBits = data.size * 8L
        var codeSize = 9
        var next = 258
        var old = -1

        fun readCode(): Int {
            if (bitPos + codeSize > totalBits) return end
            var code = 0
            repeat(codeSize) {
                val byte = data[(bitPos ushr 3).toInt()].toInt()
                code = (code shl 1) or ((byte shr (7 - (bitPos and 7).toInt())) and 1)
                bitPos++
            }
            return code
        }

        // Writes the string of [code] and returns its first byte.
        fun emit(code: Int): Byte {
            val len = length[code]
            var c = code
            var i = len - 1
            while (i >= 0) {
                if (written + i < expected) out[written + i] = suffix[c]
                c = prefix[c]
                i--
            }
            written += len
            var first = code
            while (length[first] > 1) first = prefix[first]
            return suffix[first]
        }

        while (written < expected) {
            val code = readCode()
            if (code == end) break
            if (code == clear) {
                codeSize = 9
                next = 258
                old = -1
                continue
            }
            if (old == -1) {
                if (code >= 256) break
                emit(code)
                old = code
                continue
            }
            val first: Byte = if (code < next) {
                emit(code)
            } else {
                // The code being defined right now: the old string plus its own first byte.
                val f = emit(old)
                if (written < expected) out[written] = f
                written++
                f
            }
            if (next < 4096) {
                prefix[next] = old
                suffix[next] = first
                length[next] = length[old] + 1
                next++
            }
            if (next + 1 >= 1 shl codeSize && codeSize < 12) codeSize++
            old = code
        }
        return out
    }

    fun inflate(data: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(expected)
        val buffer = ByteArray(64 * 1024)
        try {
            while (!inflater.finished() && out.size() < expected) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }
}
