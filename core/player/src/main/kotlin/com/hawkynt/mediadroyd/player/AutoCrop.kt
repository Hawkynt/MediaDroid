package com.hawkynt.mediadroyd.player

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Which black bars to crop while a video plays, decided from small samples of its luma (see [sampleSize]) taken now
 * and then — steady enough to watch. Each sample is scanned on its own ([BlackBars]); what it says counts only when it
 * is worth believing:
 *
 * - a frame with no bright line, or whose picture is dark on average (a night, a fade), says nothing: a dark scene's
 *   dim edges look like bars, so it is never cropped on;
 * - a picture less than half the frame either way is a logo or a light in the dark, not a picture.
 *
 * The bars are cropped once [FIRST_SAMPLES] samples in a row agree on them to within a line or two, and only when the
 * bars on opposite sides come out about as large — a film is centred in its frame, a dark sky over a lit street is
 * not. Later on, a narrower crop needs [NARROW_SAMPLES] agreeing samples. The picture widens much sooner: as soon as
 * [WIDEN_SAMPLES] samples in a row show picture beyond the crop — subtitles burnt into the bar, an IMAX sequence — it
 * grows to take them in, and from then on it narrows again only after [RENARROW_SAMPLES] agreeing samples, so lines
 * of subtitles coming and going never make the picture jump. Changes of a line or so are not worth a jump either.
 *
 * The idea of cropping in steps — few detections to widen, many to narrow — comes from DScaler's AutoAspect, taken as a
 * description only (GPL-2.0); the numbers are this player's, for a sample every half second.
 */
class AutoCrop {

    private var sampleWidth = 0
    private var sampleHeight = 0

    // The picture shown, in the samples' pixels; null for the whole frame.
    private var shown: BlackBars.Extent? = null
    private var decided = false
    private var widened = false
    private val beyond = ArrayList<BlackBars.Extent>()
    private val agreeing = ArrayDeque<BlackBars.Extent>()

    /** Forgets everything seen: the whole frame again, decided afresh. */
    fun restart() {
        shown = null
        decided = false
        widened = false
        beyond.clear()
        agreeing.clear()
    }

    /**
     * Takes one sample of the picture's luma, at the size [sampleSize] gives; returns whether the crop changed. A
     * sample of another size than the ones before starts afresh — another video, or another part of the frame.
     */
    fun add(luma: Deinterlacer.Plane): Boolean {
        if (luma.width != sampleWidth || luma.height != sampleHeight) {
            restart()
            sampleWidth = luma.width
            sampleHeight = luma.height
        }
        val seen = believable(luma) ?: return false
        val whole = BlackBars.Extent(0, 0, sampleWidth - 1, sampleHeight - 1)
        val current = shown ?: whole
        if (reachesBeyond(seen, current)) {
            beyond += seen
            if (beyond.size >= WIDEN_SAMPLES) {
                val wider = beyond.fold(current, ::union)
                beyond.clear()
                agreeing.clear()
                widened = true
                return show(wider)
            }
        } else {
            beyond.clear()
        }
        val needed = when {
            widened -> RENARROW_SAMPLES
            decided -> NARROW_SAMPLES
            else -> FIRST_SAMPLES
        }
        agreeing.addLast(seen)
        while (agreeing.size > needed) agreeing.removeFirst()
        if (agreeing.size < needed || !agree(agreeing)) return false
        val candidate = agreeing.reduce(::union)
        if (!centred(candidate) || !reachesBeyond(current, candidate)) return false
        agreeing.clear()
        return show(candidate)
    }

    /** The part of a [width] × [height] frame to show — the frame the samples were taken of — on even pixels. */
    fun crop(width: Int, height: Int): PictureCrop {
        val e = shown ?: return PictureCrop.whole(width, height)
        val (left, right) = span(e.left, e.right, sampleWidth, width)
        val (top, bottom) = span(e.top, e.bottom, sampleHeight, height)
        val x = (left + 1) and 1.inv()
        val y = (top + 1) and 1.inv()
        val w = (right - x) and 1.inv()
        val h = (bottom - y) and 1.inv()
        return if (w <= 0 || h <= 0) PictureCrop.whole(width, height) else PictureCrop(x, y, w, h)
    }

    /**
     * Sample lines [first]..[last] of [samples] as the frame's [size] pixels: start and end (exclusive), inwards. A
     * sample line looked at the frame where its centre falls, so where it is the outermost bright one the picture
     * starts at most half a line before that centre; the cut goes just past it, leaving no bar behind. The frame's own
     * edges stay where they are, as does everything when the sample is the frame itself.
     */
    private fun span(first: Int, last: Int, samples: Int, size: Int): Pair<Int, Int> {
        if (samples == size) return first to last + 1
        val scale = size.toDouble() / samples
        val start = if (first == 0) 0 else ceil((first + 0.5) * scale + 0.5).toInt()
        val end = if (last == samples - 1) size else floor((last + 0.5) * scale - 0.5).toInt()
        return start.coerceIn(0, size) to end.coerceIn(0, size)
    }

    private fun show(extent: BlackBars.Extent): Boolean {
        decided = true
        val whole = BlackBars.Extent(0, 0, sampleWidth - 1, sampleHeight - 1)
        val next = extent.takeIf { reachesBeyond(whole, it) }
        if (next == shown) return false
        shown = next
        return true
    }

    /** The sample's picture, or null where it says nothing worth believing (see the class). */
    private fun believable(luma: Deinterlacer.Plane): BlackBars.Extent? {
        val e = BlackBars(luma.width, luma.height).apply { add(luma) }.picture
        if (!e.found || e.width * 2 < luma.width || e.height * 2 < luma.height) return null
        var sum = 0L
        for (y in e.top..e.bottom) for (x in e.left..e.right) sum += luma.pixels[y * luma.width + x]
        return e.takeIf { sum >= DARK_MEAN.toLong() * e.width * e.height }
    }

    /** Whether [inner]'s edges reach out past [outer]'s anywhere by more than [SLACK] lines. */
    private fun reachesBeyond(inner: BlackBars.Extent, outer: BlackBars.Extent): Boolean =
        inner.left < outer.left - SLACK || inner.top < outer.top - SLACK ||
            inner.right > outer.right + SLACK || inner.bottom > outer.bottom + SLACK

    private fun agree(extents: Collection<BlackBars.Extent>): Boolean {
        fun close(edge: (BlackBars.Extent) -> Int) = extents.maxOf(edge) - extents.minOf(edge) <= AGREEMENT
        return close { it.left } && close { it.top } && close { it.right } && close { it.bottom }
    }

    /** Whether the bars on opposite sides are about as large: within 3 % of the frame, or [AGREEMENT] lines. */
    private fun centred(e: BlackBars.Extent): Boolean {
        val across = maxOf(AGREEMENT, sampleWidth * 3 / 100)
        val down = maxOf(AGREEMENT, sampleHeight * 3 / 100)
        return kotlin.math.abs(e.left - (sampleWidth - 1 - e.right)) <= across &&
            kotlin.math.abs(e.top - (sampleHeight - 1 - e.bottom)) <= down
    }

    private fun union(a: BlackBars.Extent, b: BlackBars.Extent) =
        BlackBars.Extent(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

    companion object {
        /** Samples in a row that agree before a video's bars are first cropped: two seconds. */
        const val FIRST_SAMPLES = 4

        /** Agreeing samples before the crop narrows later on: eight seconds. */
        const val NARROW_SAMPLES = 16

        /** Samples in a row showing picture beyond the crop before it widens: one second. */
        const val WIDEN_SAMPLES = 2

        /** Agreeing samples before a crop that had to widen narrows again: a minute. */
        const val RENARROW_SAMPLES = 120

        /** How often the player samples the picture. */
        const val SAMPLE_INTERVAL_MS = 500L

        /** Lines an edge may move without it counting. */
        private const val SLACK = 1

        /** Lines the samples' edges may spread over and still agree. */
        private const val AGREEMENT = 2

        /** The mean luma below which a picture is too dark to judge its edges by (of 255; video black is 16). */
        private const val DARK_MEAN = 36

        private const val MAX_SAMPLE_WIDTH = 512
        private const val MAX_SAMPLE_HEIGHT = 288

        /** The size a [width] × [height] picture is sampled at: at most 512 × 288, its shape kept, never enlarged. */
        fun sampleSize(width: Int, height: Int): PictureSize {
            val scale = min(1.0, min(MAX_SAMPLE_WIDTH.toDouble() / width, MAX_SAMPLE_HEIGHT.toDouble() / height))
            return PictureSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
        }

        /**
         * A sample of size [size] of a [width] × [height] RGBA picture, rows from the top, at [bytes] bytes a channel
         * (1, or 2 little-endian): each sample the pixel under its centre, its luma by BT.709's weights.
         */
        fun luma(rgba: ByteArray, width: Int, height: Int, bytes: Int, size: PictureSize): Deinterlacer.Plane {
            val pixels = IntArray(size.width * size.height)
            val high = bytes - 1
            for (sy in 0 until size.height) {
                val y = ((sy + 0.5) * height / size.height).toInt().coerceAtMost(height - 1)
                for (sx in 0 until size.width) {
                    val x = ((sx + 0.5) * width / size.width).toInt().coerceAtMost(width - 1)
                    val at = (y * width + x) * 4 * bytes + high
                    val r = rgba[at].toInt() and 0xFF
                    val g = rgba[at + bytes].toInt() and 0xFF
                    val b = rgba[at + 2 * bytes].toInt() and 0xFF
                    pixels[sy * size.width + sx] = (r * 2126 + g * 7152 + b * 722 + 5000) / 10000
                }
            }
            return Deinterlacer.Plane(size.width, size.height, pixels)
        }
    }
}
