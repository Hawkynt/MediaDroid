package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.player.Deinterlacer
import com.hawkynt.mediadroid.player.FieldOrder
import com.hawkynt.mediadroid.player.FilmCadence
import com.hawkynt.mediadroid.player.Telecine
import com.hawkynt.mediadroid.player.YuvMatrix

/**
 * Film on the desktop (PictureAdjustment.Deinterlace.FILM): pulldown undone as the Android stage undoes it, by
 * FilmCadence — at NTSC's rate each decoded picture matched with its neighbours' fields (FFmpeg's fieldmatch) and
 * woven, and of every five the repeated one dropped (mixed: video without a repeated picture keeps all five); at PAL's
 * 2:2 film found and woven whole. A picture still combed after matching, or not film at PAL's rate, is deinterlaced
 * by YADIF. The kept pictures take the cycle's own time, spread evenly over it — the film's own 24 a second from 30 —
 * so the sound needs no wait: the pictures are decoded ahead of the clock, about six of them held here meanwhile.
 * Which field comes first is told from the first pictures (FieldOrder). On the reader's thread, between decoder and
 * queue; HDR pictures pass untouched.
 */
class Film {
    private val order = FieldOrder()
    private var toJudge = FRAMES_TO_JUDGE
    private val cadence = FilmCadence()
    private val pictures = ArrayDeque<MediaReader.Picture>()
    private val lumas = ArrayDeque<Deinterlacer.Plane>()
    private val cycle = ArrayList<MediaReader.Picture>()

    /** Forgets every picture, for a seek or a new video: the next cycle starts afresh. */
    fun reset() {
        pictures.clear()
        lumas.clear()
        cycle.clear()
        order.reset()
        toJudge = FRAMES_TO_JUDGE
        cadence.reset()
    }

    /** Takes the next decoded [picture]; returns the pictures a finished cycle keeps, in order, else none. */
    fun add(picture: MediaReader.Picture): List<MediaReader.Picture> {
        if (picture.hdr != null) {
            reset()
            return listOf(picture)
        }
        val last = pictures.lastOrNull()
        if (last != null && (last.width != picture.width || last.height != picture.height)) reset()
        val step = if (picture.width > MAX_FULL_WIDTH) 2 else 1
        pictures.addLast(picture)
        lumas.addLast(luma(picture, step))
        while (pictures.size > 3) {
            pictures.removeFirst()
            lumas.removeFirst()
        }
        if (toJudge > 0 && lumas.size == 3) {
            toJudge--
            order.add(lumas[0], lumas[1], lumas[2])
        }
        if (pictures.size < 2) return emptyList()
        val n = pictures.size
        val next = pictures[n - 1]
        val cur = pictures[n - 2]
        val prev = if (n >= 3) pictures[n - 3] else cur
        val prevLuma = if (n >= 3) lumas[n - 3] else null
        val topFirst = order.topFieldFirst
        val judged = cadence.add(prevLuma, lumas[n - 2], lumas[n - 1], topFirst, intervalNs(cur, next))
        cycle += if (judged.combed) {
            yadif(prev, cur, next, topFirst)
        } else {
            when (judged.match) {
                Telecine.Match.PREVIOUS -> weave(cur, prev, judged.field)
                Telecine.Match.NEXT -> weave(cur, next, judged.field)
                Telecine.Match.BOTTOM -> weave(cur, prev, 1 - judged.field)
                Telecine.Match.UPPER -> weave(cur, next, 1 - judged.field)
                Telecine.Match.CURRENT -> cur
            }
        }
        val verdict = judged.cycle ?: return emptyList()
        val kept = cycle.filterIndexed { i, _ -> i != verdict.drop }
        val first = cycle.first().ptsMs
        val span = (cycle.last().ptsMs - first).takeIf { it > 0 }?.let { it * cycle.size / (cycle.size - 1) }
            ?: (cycle.size * DEFAULT_FRAME_MS)
        cycle.clear()
        return kept.mapIndexed { j, p ->
            MediaReader.Picture(first + span * j / kept.size, p.width, p.height, p.rgba)
        }
    }

    // The time between two pictures, from their stamps; NTSC's where they carry none.
    private fun intervalNs(cur: MediaReader.Picture, next: MediaReader.Picture): Long =
        (next.ptsMs - cur.ptsMs).takeIf { it > 0 }?.let { it * 1_000_000L } ?: (DEFAULT_FRAME_MS * 1_000_000L)

    companion object {
        /** Pictures wider than this are matched on every second column, as on Android. */
        const val MAX_FULL_WIDTH = 1024

        /** The pictures the field order is judged on after a start. */
        private const val FRAMES_TO_JUDGE = 50

        /** A cycle's frame time where its pictures carry no usable times: NTSC's. */
        private const val DEFAULT_FRAME_MS = 1001L / 30

        /**
         * [picture]'s luma, every [step]th column, as the stage's luma pass works it out: BT.601 weights up to
         * 576 lines, BT.709 above, rounded to a byte.
         */
        fun luma(picture: MediaReader.Picture, step: Int = 1): Deinterlacer.Plane {
            val sd = picture.height <= YuvMatrix.SD_HEIGHT
            val kr = if (sd) 0.299f else 0.2126f
            val kg = if (sd) 0.587f else 0.7152f
            val kb = if (sd) 0.114f else 0.0722f
            val w = picture.width / step
            val h = picture.height
            val rgba = picture.rgba
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val i = (y * picture.width + x * step) * 4
                val v = kr * (rgba[i].toInt() and 255) + kg * (rgba[i + 1].toInt() and 255) +
                    kb * (rgba[i + 2].toInt() and 255)
                out[y * w + x] = (v + 0.5f).toInt().coerceIn(0, 255)
            }
            return Deinterlacer.Plane(w, h, out)
        }

        /** [cur] with the rows of parity [rows] (0 even, 1 odd, from the top) taken from [other]. */
        fun weave(cur: MediaReader.Picture, other: MediaReader.Picture, rows: Int): MediaReader.Picture {
            val out = cur.rgba.copyOf()
            val line = cur.width * 4
            for (y in rows until cur.height step 2) System.arraycopy(other.rgba, y * line, out, y * line, line)
            return MediaReader.Picture(cur.ptsMs, cur.width, cur.height, out)
        }

        /** [cur] with its later field rebuilt by YADIF from [prev] and itself, each colour on its own. */
        fun yadif(
            prev: MediaReader.Picture,
            cur: MediaReader.Picture,
            next: MediaReader.Picture,
            topFirst: Boolean,
        ): MediaReader.Picture {
            val w = cur.width
            val h = cur.height
            if (w < 10 || h < 10) return cur
            val out = cur.rgba.copyOf()
            fun plane(p: MediaReader.Picture, c: Int) =
                Deinterlacer.Plane(w, h, IntArray(w * h) { p.rgba[it * 4 + c].toInt() and 255 })
            for (c in 0 until 3) {
                val rebuilt = Deinterlacer.deinterlace(
                    Deinterlacer.Method.YADIF, plane(prev, c), plane(cur, c), plane(next, c), false, topFirst,
                )
                for (i in 0 until w * h) out[i * 4 + c] = rebuilt.pixels[i].toByte()
            }
            return MediaReader.Picture(cur.ptsMs, w, h, out)
        }
    }
}
