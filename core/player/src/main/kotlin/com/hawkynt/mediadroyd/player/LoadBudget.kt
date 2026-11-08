package com.hawkynt.mediadroyd.player

/**
 * How much undecoded video the player may hold in the app's own memory. Media3's default for a video track is
 * about 131 MB, which a 256 MB heap cannot spare once a file's samples are large: a raw 4K picture is 12 MB. A
 * quarter of the heap is the target here, between [MIN_BYTES] — a few seconds of high-bitrate 4K — and Media3's
 * own default [MAX_BYTES].
 */
object LoadBudget {
    const val MIN_BYTES = 32L * 1024 * 1024

    /** Media3's DefaultLoadControl default for a video track: 2000 segments of 64 KiB. */
    const val MAX_BYTES = 2000L * 64 * 1024

    /** The byte target for an app whose heap may grow to [heapBytes]. */
    fun targetBytes(heapBytes: Long): Long = (heapBytes / 4).coerceIn(MIN_BYTES, MAX_BYTES)
}
