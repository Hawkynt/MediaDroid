package com.hawkynt.mediadroid.player

/**
 * Re-cuts a compressed audio stream that arrives in arbitrary chunks into whole frames. AVI stores
 * AC3 as a plain byte stream split into chunks that ignore frame boundaries; a decoder fed those
 * chunks as they are rejects them. The bytes are collected here and handed on frame by frame, each
 * stamped with its own time: a chunk that starts on a fresh frame sets the clock to its timestamp,
 * and every frame after it follows by the duration the format gives it.
 *
 * [probe] tells, at a position of the buffer, whether a frame starts there and how long it is.
 */
class FrameSplitter(private val probe: (bytes: ByteArray, at: Int, available: Int) -> Probe) {

    sealed interface Probe {
        /** No frame starts at this byte; it is skipped. */
        data object NotAFrame : Probe

        /** A frame may start here, but more bytes are needed to tell its size. */
        data object NeedMore : Probe

        /** A frame of [size] bytes playing for [durationUs] starts here. */
        data class Frame(val size: Int, val durationUs: Long) : Probe
    }

    private var buffer = ByteArray(64 * 1024)
    private var size = 0
    private var clockUs = UNSET
    private var chunkStartsFresh: Boolean? = null

    /** Adds [length] bytes of the current chunk. */
    fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (chunkStartsFresh == null) chunkStartsFresh = size == 0
        if (size + length > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + length))
        System.arraycopy(bytes, offset, buffer, size, length)
        size += length
    }

    /**
     * The chunk whose bytes were appended since the last call ended; its first byte plays at [timeUs].
     * Every frame now complete goes to [emit] as (bytes, offset, size, timeUs).
     */
    fun chunkEnded(timeUs: Long, chunkSize: Int, emit: (ByteArray, Int, Int, Long) -> Unit) {
        if (chunkSize > 0 && (clockUs == UNSET || chunkStartsFresh == true)) clockUs = timeUs
        chunkStartsFresh = null
        var pos = 0
        while (pos < size) {
            when (val found = probe(buffer, pos, size - pos)) {
                Probe.NotAFrame -> pos++
                Probe.NeedMore -> break
                is Probe.Frame -> {
                    if (found.size <= 0) {
                        pos++
                        continue
                    }
                    if (pos + found.size > size) break
                    emit(buffer, pos, found.size, clockUs)
                    clockUs += found.durationUs
                    pos += found.size
                }
            }
        }
        System.arraycopy(buffer, pos, buffer, 0, size - pos)
        size -= pos
    }

    /** Forgets everything buffered, as after a seek. */
    fun reset() {
        size = 0
        clockUs = UNSET
        chunkStartsFresh = null
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
    }
}
