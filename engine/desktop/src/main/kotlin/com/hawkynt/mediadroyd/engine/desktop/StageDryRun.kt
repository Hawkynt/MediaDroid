package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.PictureAdjustment
import com.hawkynt.mediadroyd.player.PictureCheck
import com.hawkynt.mediadroyd.player.YuvMatrix
import com.hawkynt.mediadroyd.stage.StageCheck
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
        val frames = List(StageCheck.PICTURES) { StageCheck.picture(it) }
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
        var earlier: MediaReader.Picture? = null
        var older = emptyList<MediaReader.Picture>()
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
            val phases = StageCheck.phases(picture, span, refreshMs)
            var work = filmMs
            for (phase in phases) {
                // In real time, as the window would ask: Motion's search runs alongside meanwhile.
                val due = start + ((shown.ptsMs + phase * span) * 1e6).toLong()
                val wait = (due - System.nanoTime()) / 1_000_000L
                if (wait > 0) Thread.sleep(wait)
                val began = System.nanoTime()
                stage.process(
                    Moment(shown, previous, next, after, phase.toFloat(), earlier, older), picture, width, height,
                )
                work += (System.nanoTime() - began) / 1e6
            }
            older = (listOfNotNull(earlier) + older).take(Moment.OLDER)
            earlier = previous
            previous = shown
            if (k < WARM_FRAMES) continue
            measured++
            workTotal += work
            if (work > span) late++
        }
        PictureCheck.DryRun(measured, late + stage.motionLate - lateBefore, workTotal / measured.coerceAtLeast(1))
    }.getOrNull()

    /** [rgba] (a test picture) as limited-range BT.601 4:2:0 planes, as a decoder hands an SD picture over. */
    private fun planesOf(rgba: ByteArray): MediaReader.Planes {
        val w = PictureCheck.WIDTH
        val h = PictureCheck.HEIGHT
        val c = ((w + 1) / 2) * ((h + 1) / 2)
        val all = StageCheck.planes(rgba)
        return MediaReader.Planes(
            all.copyOfRange(0, w * h), all.copyOfRange(w * h, w * h + c), all.copyOfRange(w * h + c, w * h + 2 * c),
            YuvMatrix.BT601, fullRange = false,
        )
    }

}
