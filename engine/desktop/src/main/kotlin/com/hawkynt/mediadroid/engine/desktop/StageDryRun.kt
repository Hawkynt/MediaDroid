package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureCheck
import com.hawkynt.mediadroid.player.YuvMatrix
import java.util.concurrent.ConcurrentHashMap

/**
 * The dry run behind PictureCheck on the desktop, as PictureChecker is on Android: a stage of its own (GlStage) with a
 * picture's filters, fed 576p test pictures at 25 a second in real time — through Film first where Film is chosen —
 * and asked for every picture the window would draw: one per picture, two at field rate, one per screen refresh while
 * smooth motion mixes. What the stage took for each picture is measured, and a picture is late where that is more than
 * its time or Motion's vectors were not in when it showed. What a picture's costly part (PictureCheck.key) showed at a
 * size stays known for as long as the app runs.
 */
object StageDryRun {
    /** The pictures fed before measuring: the stage's programs build, Motion's search settles on its scale. */
    private const val WARM_FRAMES = 12

    /** The pictures measured: with the warm-up, under two seconds all told. */
    private const val MEASURED_FRAMES = 30

    private val known = ConcurrentHashMap<String, PictureCheck.DryRun>()

    /** The frame time a check plays at: 576p's 25 frames a second. */
    val frameMs: Double get() = 1000.0 / PictureCheck.FRAMES_PER_SECOND

    /**
     * What the stage does with [picture] drawing at [width] × [height] on a screen refreshing [refreshHz] times a
     * second, the neural networks as [neural] has them; null where the stage could not be set up. Takes under two
     * seconds the first time; blocks.
     */
    fun run(
        picture: PictureAdjustment,
        width: Int,
        height: Int,
        refreshHz: Double,
        neural: GlStage.Neural? = null,
    ): PictureCheck.DryRun? {
        val key = "${PictureCheck.key(picture)}@${width}x$height"
        known[key]?.let { return it }
        // Read back at once: what the stage takes for a picture is waited for, not hidden behind the next call.
        return GlStage(Presentation.SYNC).use { stage ->
            if (!stage.ready) return@use null
            stage.neural = neural
            measure(stage, picture, width, height, refreshHz)?.also { known[key] = it }
        }
    }

    private fun measure(
        stage: GlStage,
        picture: PictureAdjustment,
        width: Int,
        height: Int,
        refreshHz: Double,
    ): PictureCheck.DryRun? = runCatching {
        val frames = testFrames()
        val film = if (picture.deinterlace == PictureAdjustment.Deinterlace.FILM) Film() else null
        val intervalMs = frameMs
        val refreshMs = 1000.0 / refreshHz.coerceIn(24.0, 240.0)
        // The pictures as the window gets them, each with what Film took to make it.
        val decoded = ArrayDeque<Pair<MediaReader.Picture, Double>>()
        var fed = 0
        // A chroma upscaler runs on the decoded planes: the pictures carry theirs, as the reader's do then.
        val planes = if (picture.chroma != PictureAdjustment.Chroma.BILINEAR) frames.map(::planesOf) else null
        fun feed() {
            val raw = MediaReader.Picture((fed * intervalMs).toLong(), PictureCheck.WIDTH, PictureCheck.HEIGHT,
                frames[fed % frames.size], planes = planes?.get(fed % frames.size))
            fed++
            if (film == null) {
                decoded.addLast(raw to 0.0)
                return
            }
            val start = System.nanoTime()
            val out = film.add(raw)
            val ms = (System.nanoTime() - start) / 1e6
            out.forEach { decoded.addLast(it to ms / out.size) }
        }
        val start = System.nanoTime()
        var previous: MediaReader.Picture? = null
        var measured = 0
        var late = 0
        var workTotal = 0.0
        var lateBefore = 0
        for (k in 0 until WARM_FRAMES + MEASURED_FRAMES) {
            while (decoded.size < 3) feed()
            if (k == WARM_FRAMES) lateBefore = stage.motionLate
            val (shown, filmMs) = decoded.removeFirst()
            val next = decoded[0].first
            val after = decoded[1].first
            val span = (next.ptsMs - shown.ptsMs).toDouble().coerceAtLeast(1.0)
            val phases = when {
                picture.interpolation != PictureAdjustment.Interpolation.OFF ->
                    generateSequence(0.0) { it + refreshMs }.takeWhile { it < span }.map { it / span }.toList()
                picture.isFieldRate -> listOf(0.0, 0.5)
                else -> listOf(0.0)
            }
            var work = filmMs
            for (phase in phases) {
                // In real time, as the window would ask: Motion's search runs alongside meanwhile.
                val due = start + ((shown.ptsMs + phase * span) * 1e6).toLong()
                val wait = (due - System.nanoTime()) / 1_000_000L
                if (wait > 0) Thread.sleep(wait)
                val began = System.nanoTime()
                stage.process(Moment(shown, previous, next, after, phase.toFloat()), picture, width, height)
                work += (System.nanoTime() - began) / 1e6
            }
            previous = shown
            if (k < WARM_FRAMES) continue
            measured++
            workTotal += work
            if (work > span) late++
        }
        PictureCheck.DryRun(measured, late + stage.motionLate - lateBefore, workTotal / measured.coerceAtLeast(1))
    }.getOrNull()

    /**
     * Test pictures at 576p, as Android's check plays: smooth random blotches about 12 pixels across, moving 4 pixels
     * across and 2 down a picture, so motion has something to find and the pictures differ as video does.
     */
    private fun testFrames(): List<ByteArray> {
        val w = PictureCheck.WIDTH
        val h = PictureCheck.HEIGHT
        return List(8) { k ->
            val rgba = ByteArray(w * h * 4)
            for (y in 0 until h) for (x in 0 until w) {
                val i = (y * w + x) * 4
                for (c in 0 until 3) rgba[i + c] = blotch(x + k * 4, y + k * 2, c).toByte()
                rgba[i + 3] = -1
            }
            rgba
        }
    }

    /** [rgba] (a test picture) as limited-range BT.601 4:2:0 planes, as a decoder hands an SD picture over. */
    private fun planesOf(rgba: ByteArray): MediaReader.Planes {
        val w = PictureCheck.WIDTH
        val h = PictureCheck.HEIGHT
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        fun c(x: Int, y: Int, k: Int) =
            rgba[(y.coerceAtMost(h - 1) * w + x.coerceAtMost(w - 1)) * 4 + k].toInt() and 0xFF
        fun luma(x: Int, y: Int) = 0.299 * c(x, y, 0) + 0.587 * c(x, y, 1) + 0.114 * c(x, y, 2)
        val y = ByteArray(w * h) { (16 + luma(it % w, it / w) * 219 / 255 + 0.5).toInt().toByte() }
        val u = ByteArray(cw * ch) {
            val x = it % cw * 2
            val row = it / cw * 2
            (128 + (c(x, row, 2) - luma(x, row)) / 1.772 * 224 / 255 + 0.5).toInt().toByte()
        }
        val v = ByteArray(cw * ch) {
            val x = it % cw * 2
            val row = it / cw * 2
            (128 + (c(x, row, 0) - luma(x, row)) / 1.402 * 224 / 255 + 0.5).toInt().toByte()
        }
        return MediaReader.Planes(y, u, v, YuvMatrix.BT601, fullRange = false)
    }

    private fun blotch(x: Int, y: Int, channel: Int): Int {
        val cell = 12
        val cx = Math.floorDiv(x, cell)
        val cy = Math.floorDiv(y, cell)
        val fx = (x - cx * cell).toFloat() / cell
        val fy = (y - cy * cell).toFloat() / cell
        fun level(i: Int, j: Int): Float {
            var v = i * 73856093 xor j * 19349663 xor channel * 83492791
            v = (v xor (v ushr 13)) * 0x5bd1e995
            return ((v xor (v ushr 15)) and 0xFF).toFloat()
        }
        val top = level(cx, cy) * (1 - fx) + level(cx + 1, cy) * fx
        val bottom = level(cx, cy + 1) * (1 - fx) + level(cx + 1, cy + 1) * fx
        return (top * (1 - fy) + bottom * fy).toInt()
    }
}
