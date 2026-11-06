package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.player.Deinterlacer
import com.hawkynt.mediadroid.player.MotionInterpolation
import com.hawkynt.mediadroid.player.PictureCrop
import com.hawkynt.mediadroid.player.SearchScale
import com.hawkynt.mediadroid.player.YuvMatrix
import java.util.concurrent.Executors

/**
 * Motion's search on the desktop (MotionInterpolation, FFmpeg's minterpolate): the vectors between two pictures,
 * found on a thread of its own from their luma at the scale SearchScale picks as it plays. The desktop decodes ahead,
 * so a pair's search starts as soon as both pictures are decoded — the pair after the one on screen — and its vectors
 * are in by the time the pair shows; a pair shown before its vectors are in is counted [late] and steps the scale
 * coarser, as on Android.
 */
class MotionSearch : AutoCloseable {

    /** One pair's vectors: [grid] blocks across and down, as bytes (x + 128, y + 128) per block; [cut] a scene cut. */
    class Vectors(
        val gridWidth: Int,
        val gridHeight: Int,
        val scale: Int,
        val bytes: ByteArray,
        val cut: Boolean,
    )

    private class Pair(val earlier: MediaReader.Picture, val later: MediaReader.Picture) {
        @Volatile var vectors: Vectors? = null

        @Volatile var done = false

        @Volatile var late = false
    }

    private val thread = Executors.newSingleThreadExecutor { Thread(it, "mediadroid-motion").apply { isDaemon = true } }
    private val pairs = ArrayList<Pair>()
    private var scale: SearchScale? = null
    private var estimator: MotionInterpolation.Estimator? = null
    private val sceneChange = MotionInterpolation.SceneChange()

    /** Pairs shown before their vectors were in. */
    @Volatile var late = 0
        private set

    /**
     * Starts the search between [earlier] and [later] — the part [region] of each the stage shows (inside the black
     * bars, the 3D eye) — unless it ran or runs already.
     */
    @Synchronized
    fun request(earlier: MediaReader.Picture, later: MediaReader.Picture, region: PictureCrop) {
        if (pairs.any { it.earlier === earlier && it.later === later }) return
        val pair = Pair(earlier, later)
        pairs += pair
        while (pairs.size > KEPT_PAIRS) pairs.removeAt(0)
        val intervalNanos = (later.ptsMs - earlier.ptsMs).coerceAtLeast(1) * 1_000_000L
        thread.execute { search(pair, region, intervalNanos) }
    }

    /** The vectors between [earlier] and [later] where they are in; else null, the pair counted late once. */
    @Synchronized
    fun vectors(earlier: MediaReader.Picture, later: MediaReader.Picture): Vectors? {
        val pair = pairs.firstOrNull { it.earlier === earlier && it.later === later } ?: return null
        if (!pair.done && !pair.late) {
            pair.late = true
            late++
        }
        return pair.vectors
    }

    private fun search(pair: Pair, region: PictureCrop, intervalNanos: Long) {
        val width = region.width
        val height = region.height
        val pace = scale?.takeIf { it.width == width && it.height == height }
            ?: SearchScale(width, height).also { scale = it }
        val step = pace.scale
        val a = luma(pair.earlier, region, step)
        val b = luma(pair.later, region, step)
        if (a.width != b.width || a.height != b.height ||
            a.width < 2 * MotionInterpolation.BLOCK || a.height < 2 * MotionInterpolation.BLOCK
        ) {
            pair.done = true
            return
        }
        val started = System.nanoTime()
        val search = estimator?.takeIf { it.width == a.width && it.height == a.height }
            ?: MotionInterpolation.Estimator(a.width, a.height).also { estimator = it }
        search.advance()
        search.estimate(a, b)
        val took = System.nanoTime() - started
        val cut = sceneChange.check(a, b)
        val bytes = ByteArray(search.blocksWide * search.blocksHigh * 4)
        for (k in 0 until search.blocksWide * search.blocksHigh) {
            bytes[k * 4] = (search.vectors[k * 2] + VECTOR_ZERO).toByte()
            bytes[k * 4 + 1] = (search.vectors[k * 2 + 1] + VECTOR_ZERO).toByte()
        }
        pair.vectors = Vectors(search.blocksWide, search.blocksHigh, width / a.width, bytes, cut)
        pair.done = true
        pace.report(took, intervalNanos, pair.late)
    }

    override fun close() {
        thread.shutdownNow()
    }

    companion object {
        /** A vector component of 0, as the motion shader reads it. */
        const val VECTOR_ZERO = 128

        // The pairs remembered: the one on screen, the next, and a few behind for a seek back.
        private const val KEPT_PAIRS = 6

        /**
         * The [region] of [picture]'s luma, every [step]th pixel of every [step]th row from the top, as the stage's
         * luma pass works it out: BT.601 weights up to 576 lines, BT.709 above; an HDR picture's coded values by their
         * high byte.
         */
        fun luma(picture: MediaReader.Picture, region: PictureCrop, step: Int): Deinterlacer.Plane {
            val left = region.left
            val top = region.top
            val w = region.width / step
            val h = region.height / step
            val sd = picture.height <= YuvMatrix.SD_HEIGHT
            val kr = if (sd) 0.299f else 0.2126f
            val kg = if (sd) 0.587f else 0.7152f
            val kb = if (sd) 0.114f else 0.0722f
            val deep = picture.hdr != null
            val rgba = picture.rgba
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val p = (top + y * step) * picture.width + left + x * step
                val v = if (deep) {
                    kr * (rgba[p * 8 + 1].toInt() and 255) + kg * (rgba[p * 8 + 3].toInt() and 255) +
                        kb * (rgba[p * 8 + 5].toInt() and 255)
                } else {
                    kr * (rgba[p * 4].toInt() and 255) + kg * (rgba[p * 4 + 1].toInt() and 255) +
                        kb * (rgba[p * 4 + 2].toInt() and 255)
                }
                out[y * w + x] = (v + 0.5f).toInt().coerceIn(0, 255)
            }
            return Deinterlacer.Plane(w, h, out)
        }
    }
}
