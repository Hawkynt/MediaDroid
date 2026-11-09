package com.hawkynt.mediadroyd.player

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What two Chromaprint fingerprints (NativeFingerprint) share, and where it lies in each: converted from
 * MediaUploadService's port (src/media_organizer/contentdup/audio.py and align.py, LGPL-3.0, the same author) of
 * Chromaprint's FingerprintMatcher::Match (src/fingerprint_matcher.cpp, utils/gaussian_filter.h, utils/gradient.h;
 * MIT, Lukas Lalinsky; app/THIRD-PARTY-NOTICE.Chromaprint.txt) and checked against Chromaprint's own output.
 *
 * The items' top [ALIGN_BITS] bits vote on the offset between the prints; at an offset the bit errors of the
 * overlapping items are smoothed (three box filters approximating a gaussian of sigma 8), cut where the smoothed
 * curve's gradient peaks above 0.15, and each cut whose mean bit error is below [MATCH_THRESHOLD] of 32 bits is a
 * matching segment, neighbours within 0.7 of each other merged. As in the port, unlike Chromaprint: the [OFFSETS] best offsets
 * are walked and their segments taken longest first, each keeping what no longer one took (an intro at another
 * position in each is followed past what lies around it); the tie-breaking noise is a fixed sequence; a bucket of more
 * than [BUCKET] items (silence, a held tone) casts no votes.
 */
object PrintMatcher {
    const val ALIGN_BITS = 12
    const val MATCH_THRESHOLD = 10.0
    const val OFFSETS = 6
    const val BUCKET = 400

    /** The items a shared piece needs, and at least three seconds' worth. */
    const val RUN = 6
    private val MIN_ITEMS = max(RUN, (3.0 / NativeFingerprint.ITEM_SECONDS).roundToInt())

    /** A matching segment at one offset: from item [first1] of the first print and [first2] of the second. */
    data class Segment(val first1: Int, val first2: Int, val length: Int, val score: Double)

    /** A stretch both prints hold: [length] items from item [first1] of the first and [first2] of the second. */
    data class Shared(val first1: Int, val first2: Int, val length: Int)

    /** Everything [a] and [b] share, as pieces that overlap on neither side, in the order they lie in [a]. */
    fun shared(a: IntArray, b: IntArray): List<Shared> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val pieces = ArrayList<Piece>()
        for ((votes, index) in offsets(a, b).take(OFFSETS)) {
            for (segment in segmentsAt(a, b, index)) {
                if (segment.length < MIN_ITEMS) continue
                pieces += Piece(
                    segment.first1,
                    segment.first1 + segment.length - 1,
                    segment.first2 - segment.first1,
                    // The best voted offset first, as Chromaprint.
                    votes.toLong() * 1_000_000 + segment.length,
                )
            }
        }
        return take(pieces, a.size, b.size)
    }

    // A run along one offset (j = i + offset) and how strongly it is backed.
    private class Piece(val first: Int, val last: Int, val offset: Int, val weight: Long)

    // The pieces, the strongest first, each keeping only what no stronger piece took on either side.
    private fun take(pieces: List<Piece>, n: Int, m: Int): List<Shared> {
        val usedA = BooleanArray(n)
        val usedB = BooleanArray(m)
        val taken = ArrayList<Shared>()
        fun jOf(i: Int, offset: Int) = (i + offset).coerceIn(0, m - 1)
        val ordered = pieces.sortedWith(compareBy<Piece>({ -it.weight }, { it.first }, { it.offset }))
        for (piece in ordered) {
            var runStart = -1
            fun close(end: Int) {
                if (runStart >= 0 && end - runStart + 1 >= RUN) {
                    for (i in runStart..end) usedA[i] = true
                    for (j in jOf(runStart, piece.offset)..jOf(end, piece.offset)) usedB[j] = true
                    taken += Shared(runStart, jOf(runStart, piece.offset), end - runStart + 1)
                }
                runStart = -1
            }
            for (i in piece.first..piece.last) {
                val free = !usedA[i] && !usedB[jOf(i, piece.offset)]
                if (free && runStart < 0) runStart = i
                if (!free) close(i - 1)
            }
            close(piece.last)
        }
        return taken.sortedBy { it.first1 }
    }

    /**
     * The peaks of the offset histogram as (votes, offset index), strongest first; the offset index is
     * `i - j + b.size`, as Chromaprint counts it.
     */
    fun offsets(a: IntArray, b: IntArray): List<Pair<Int, Int>> {
        val shift = 32 - ALIGN_BITS
        val byKey = HashMap<Int, MutableList<Int>>()
        b.forEachIndexed { j, item -> byKey.getOrPut(item ushr shift) { ArrayList() } += j }
        val histogram = IntArray(a.size + b.size)
        a.forEachIndexed { i, item ->
            val bucket = byKey[item ushr shift] ?: return@forEachIndexed
            if (bucket.size > BUCKET) return@forEachIndexed
            for (j in bucket) histogram[i + b.size - j]++
        }
        val peaks = ArrayList<Pair<Int, Int>>()
        for (i in histogram.indices) {
            val count = histogram[i]
            if (count <= 1) continue
            val left = i == 0 || histogram[i - 1] <= count
            val right = i == histogram.size - 1 || histogram[i + 1] <= count
            if (left && right) peaks += count to i
        }
        return peaks.sortedWith(compareByDescending<Pair<Int, Int>> { it.first }.thenByDescending { it.second })
    }

    /** Chromaprint's segmentation at the offset index [offsetIndex] (see [offsets]). */
    fun segmentsAt(a: IntArray, b: IntArray, offsetIndex: Int): List<Segment> {
        val diff = offsetIndex - b.size
        val offset1 = max(diff, 0)
        val offset2 = max(-diff, 0)
        val size = min(a.size - offset1, b.size - offset2)
        if (size <= 0) return emptyList()
        // Chromaprint breaks ties with rand() * 0.001 / RAND_MAX; a fixed sequence of the same size here.
        val counts = DoubleArray(size) { i ->
            Integer.bitCount(a[offset1 + i] xor b[offset2 + i]) + (i.toLong() * 7919 % 1000) * 1e-6
        }
        val slope = gradient(gaussian(counts)).map { abs(it) }
        val cuts = ArrayList<Int>()
        for (i in 1 until size - 1) {
            val g = slope[i]
            val peak = g > 0.15 && g >= slope[i - 1] && g >= slope[i + 1]
            if (peak && (cuts.isEmpty() || cuts.last() + 1 < i)) cuts += i
        }
        cuts += size
        val found = ArrayList<Segment>()
        var begin = 0
        for (end in cuts) {
            val length = end - begin
            if (length <= 0) {
                begin = end
                continue
            }
            var sum = 0.0
            for (i in begin until end) sum += counts[i]
            val score = sum / length
            if (score < MATCH_THRESHOLD) {
                val last = found.lastOrNull()
                if (last != null && abs(last.score - score) < 0.7 && last.first1 + last.length == offset1 + begin) {
                    val total = last.length + length
                    found[found.size - 1] =
                        Segment(last.first1, last.first2, total, (last.score * last.length + score * length) / total)
                } else {
                    found += Segment(offset1 + begin, offset2 + begin, length, score)
                }
            }
            begin = end
        }
        return found
    }

    /** Chromaprint's GaussianFilter: [passes] box filters whose widths approximate a gaussian of [sigma]. */
    fun gaussian(values: DoubleArray, sigma: Double = 8.0, passes: Int = 3): DoubleArray {
        val w = floor(sqrt(12 * sigma * sigma / passes + 1)).toInt()
        val lower = w - if (w % 2 == 0) 1 else 0
        val upper = lower + 2
        val m = Math.round(
            (12 * sigma * sigma - passes * lower * lower - 4 * passes * lower - 3 * passes) / (-4.0 * lower - 4),
        ).toInt()
        var out = values.copyOf()
        for (i in 0 until passes) out = box(out, if (i < m) lower else upper)
        return out
    }

    /** Chromaprint's Gradient: central differences, one-sided at the ends. */
    fun gradient(values: DoubleArray): DoubleArray {
        val size = values.size
        if (size == 0) return DoubleArray(0)
        if (size == 1) return DoubleArray(1)
        val out = DoubleArray(size)
        out[0] = values[1] - values[0]
        for (i in 1 until size - 1) out[i] = (values[i + 1] - values[i - 1]) / 2
        out[size - 1] = values[size - 1] - values[size - 2]
        return out
    }

    // Chromaprint's BoxFilter: a moving mean of [width], the edges reflected.
    private fun box(values: DoubleArray, width: Int): DoubleArray {
        val size = values.size
        if (width == 0 || size == 0) return values.copyOf()
        val left = width / 2
        val right = width - left
        val tail = Reflect(size)
        val head = Reflect(size)
        repeat(left) {
            tail.back()
            head.back()
        }
        var total = 0.0
        repeat(width) {
            total += values[head.pos]
            head.ahead()
        }
        val out = DoubleArray(size)
        var k = 0
        fun step(reflected: Boolean) {
            out[k++] = total / width
            total += values[head.pos] - values[tail.pos]
            if (reflected) {
                tail.ahead()
                head.ahead()
            } else {
                head.pos++
                tail.pos++
            }
        }
        if (size > width) {
            repeat(left) { step(true) }
            repeat(size - width - 1) { step(false) }
            repeat(right + 1) { step(true) }
        } else {
            repeat(size) { step(true) }
        }
        return out
    }

    // A position walking a series of [size] and turning back at its ends, as Chromaprint's ReflectIterator.
    private class Reflect(private val size: Int) {
        var pos = 0
        private var forward = true

        fun ahead() {
            if (forward) {
                if (pos + 1 == size) forward = false else pos++
            } else if (pos == 0) {
                forward = true
            } else {
                pos--
            }
        }

        fun back() {
            if (forward) {
                if (pos == 0) forward = false else pos--
            } else if (pos + 1 == size) {
                forward = true
            } else {
                pos++
            }
        }
    }
}
