package com.hawkynt.mediadroid.player

import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * What a neural network's self-test does on one unit, the same on every platform (the platform opens the network and
 * runs it in a process of its own, see NeuralSelfTest): whether the unit computes it right, on a small check picture
 * against the truth, and what one picture takes there at the sizes playback needs — a frame interpolation network
 * ([interpolator]) at each of NeuralInterpolation.TEST_SIZES while it keeps up, an upscaler ([enlarger]) at 576p.
 */
object NeuralChecks {
    /** A frame interpolation network on its unit, as the native part runs it (NativeInterpolation.between). */
    fun interface Between {
        /**
         * The in-between of the RGBA frames [earlier] and [later] ([width] × [height]) as RGB bytes, null where it
         * failed; frames with ids keep what was worked out from them, [before] and [after] the pair where known.
         */
        fun run(
            earlier: ByteBuffer,
            later: ByteBuffer,
            width: Int,
            height: Int,
            earlierId: Long,
            laterId: Long,
            before: ByteBuffer?,
            after: ByteBuffer?,
        ): ByteBuffer?
    }

    /** An upscaler on its unit: the RGBA [picture] ([width] × [height]) [scale] times its size as RGB; null failed. */
    fun interface Enlarge {
        fun run(picture: ByteBuffer, width: Int, height: Int): ByteBuffer?
    }

    /**
     * A colouriser on its unit (NativeInterpolation.colorize): [grey] (Colorizing.input's three planes) to the a and b
     * of Lab at 256 × 256, null where it failed.
     */
    fun interface Colorize {
        fun run(grey: FloatArray): FloatArray?
    }

    /** What a self-test tells as it goes, for whoever waits on it to cut it short. */
    interface Progress {
        /** A picture at the test size of [height] is being made. */
        fun running(height: Int)

        /** One at [height] took [ms]. */
        fun timed(height: Int, ms: Double)
    }

    /**
     * The self-test of the frame interpolation network [net] on [unit]: whether it computes right (see [check]), and
     * what one in-between takes at each of [NeuralInterpolation.TEST_SIZES], smallest first and only on while the last
     * kept up; null where it runs wrong. Past the budget on the small check already, the smallest size is estimated
     * from it by its pixels and nothing larger is given. [log] hears what the check found.
     */
    fun interpolator(
        net: Between,
        unit: String,
        progress: Progress,
        log: (String) -> Unit = {},
    ): NeuralSelfTest.Timing? {
        val smallest = NeuralInterpolation.TEST_SIZES.first()
        progress.running(smallest.height)
        val checkMs = check(net, unit, log) ?: return null
        if (NeuralInterpolation.tooSlowAlready(checkMs)) {
            val estimate = NeuralInterpolation.estimate(checkMs, CHECK_SIDE * CHECK_SIDE)
            progress.timed(smallest.height, estimate)
            return NeuralSelfTest.Timing(unit, mapOf(smallest.height to estimate))
        }
        val times = LinkedHashMap<Int, Double>()
        for (size in NeuralInterpolation.TEST_SIZES) {
            progress.running(size.height)
            val ms = time(net, size) ?: break
            times[size.height] = ms
            progress.timed(size.height, ms)
            if (!NeuralInterpolation.fastEnough(ms)) break
        }
        return if (times.isEmpty()) null else NeuralSelfTest.Timing(unit, times)
    }

    /**
     * Whether [net] computes right on its unit: the in-between of two test frames 10 × 6 pixels apart against the frame
     * truly halfway, away from the edges where the motion uncovers what neither frame shows — what it took where it
     * is right (after once to warm up: the first run sets the unit up), null where it is not.
     */
    private fun check(net: Between, unit: String, log: (String) -> Unit): Double? {
        val w = CHECK_SIDE
        val h = CHECK_SIDE
        val first = testFrame(w, h, 0)
        val middle = testFrame(w, h, 1)
        val last = testFrame(w, h, 2)
        // The frames either side of the pair, for a network that looks at four (FLAVR).
        val before = testFrame(w, h, -2)
        val after = testFrame(w, h, 4)
        net.run(first, last, w, h, 0, 0, before, after) ?: return null
        val start = System.nanoTime()
        val between = net.run(first, last, w, h, 0, 0, before, after) ?: return null
        val ms = (System.nanoTime() - start) / 1e6
        var networkError = 0L
        var blendError = 0L
        var count = 0
        for (y in EDGE until h - EDGE) for (x in EDGE until w - EDGE) for (c in 0 until 3) {
            val i = (y * w + x) * 4 + c
            val truth = middle.get(i).toInt() and 0xFF
            val blend = ((first.get(i).toInt() and 0xFF) + (last.get(i).toInt() and 0xFF)) / 2
            networkError += abs((between.get((y * w + x) * 3 + c).toInt() and 0xFF) - truth)
            blendError += abs(blend - truth)
            count++
        }
        val networkMean = networkError.toDouble() / count
        val blendMean = blendError.toDouble() / count
        log("check on $unit: in-between off by $networkMean, a blend by $blendMean, $ms ms")
        return if (NeuralInterpolation.rightEnough(networkMean, blendMean)) ms else null
    }

    // One in-between at [size] as playback makes them: a stream of test frames, each pair sharing its earlier frame
    // with the pair before (and so its features, by the frames' ids); the first pair warms up, the pairs after it are
    // timed. Null where it failed.
    private fun time(net: Between, size: NeuralInterpolation.Size): Double? {
        val frames = Array(SAMPLE_RUNS + 2) { k -> testFrame(size.width, size.height, k * 2) }
        fun pair(k: Int) = net.run(
            frames[k], frames[k + 1], size.width, size.height, k + 1L, k + 2L,
            frames.getOrNull(k - 1), frames.getOrNull(k + 2),
        )
        val warm = System.nanoTime()
        pair(0) ?: return null
        // Far too slow already warming up: that is answer enough, without waiting for more of it.
        val warmMs = (System.nanoTime() - warm) / 1e6
        if (warmMs > FAR_TOO_SLOW * NeuralInterpolation.BUDGET_MS) return warmMs
        val start = System.nanoTime()
        for (k in 1..SAMPLE_RUNS) pair(k) ?: return null
        return (System.nanoTime() - start) / 1e6 / SAMPLE_RUNS
    }

    /**
     * The self-test of the upscaler [net], [scale] times the size, on [unit]: whether it runs right — a drawing shrunk
     * by the scale and enlarged again, the output shrunk back against its input (NeuralEnlarging.rightEnough) — and
     * what one 576p picture takes there; past the budget on the small check already, the 576p time is estimated from
     * it. Null where it runs wrong.
     */
    fun enlarger(
        net: Enlarge,
        scale: Int,
        unit: String,
        progress: Progress,
        log: (String) -> Unit = {},
    ): NeuralSelfTest.Timing? {
        val height = PictureCheck.HEIGHT
        progress.running(height)
        val checkMs = check(net, scale, unit, log) ?: return null
        val pixels = PictureCheck.WIDTH * PictureCheck.HEIGHT
        if (!NeuralEnlarging.fastEnough(checkMs)) {
            val estimate = checkMs * pixels / (CHECK_SIDE * CHECK_SIDE)
            progress.timed(height, estimate)
            return NeuralSelfTest.Timing(unit, mapOf(height to estimate))
        }
        val picture = drawing(PictureCheck.WIDTH, PictureCheck.HEIGHT)
        net.run(picture, PictureCheck.WIDTH, PictureCheck.HEIGHT) ?: return null
        val start = System.nanoTime()
        repeat(SAMPLE_RUNS) { net.run(picture, PictureCheck.WIDTH, PictureCheck.HEIGHT) ?: return null }
        val ms = (System.nanoTime() - start) / 1e6 / SAMPLE_RUNS
        progress.timed(height, ms)
        return NeuralSelfTest.Timing(unit, mapOf(height to ms))
    }

    // Whether [net] runs right on its unit, and what the check's picture took (after one to warm up): a drawing shrunk
    // by the upscaler's scale and enlarged again, the output shrunk back against its input.
    private fun check(net: Enlarge, k: Int, unit: String, log: (String) -> Unit): Double? {
        val side = CHECK_SIDE
        // The grid grows with the scale, so the small picture always has 12-pixel cells.
        val full = drawing(side * k, side * k, 12 * k)
        val small = ByteBuffer.allocateDirect(side * side * 4)
        for (y in 0 until side) for (x in 0 until side) for (c in 0 until 4) {
            var sum = 0
            for (dy in 0 until k) for (dx in 0 until k) sum += full.byte(((y * k + dy) * side * k + x * k + dx) * 4 + c)
            small.put(((sum + k * k / 2) / (k * k)).toByte())
        }
        small.rewind()
        net.run(small, side, side) ?: return null
        val start = System.nanoTime()
        val out = net.run(small, side, side) ?: return null
        val ms = (System.nanoTime() - start) / 1e6
        var error = 0L
        var count = 0
        val wide = side * k
        for (y in ENLARGER_EDGE until side - ENLARGER_EDGE) for (x in ENLARGER_EDGE until side - ENLARGER_EDGE) {
            for (c in 0 until 3) {
                var sum = 0
                for (dy in 0 until k) for (dx in 0 until k) sum += out.byte(((y * k + dy) * wide + x * k + dx) * 3 + c)
                error += abs((sum + k * k / 2) / (k * k) - small.byte((y * side + x) * 4 + c))
                count++
            }
        }
        val mean = error.toDouble() / count
        log("check on $unit: off by $mean, $ms ms")
        return if (NeuralEnlarging.rightEnough(mean)) ms else null
    }

    /**
     * The self-test of the colouriser [net] on [unit]: whether it colours the check picture (resources colorize/,
     * tools/ddcolor) as DDColor's PyTorch model does — its a and b averaged over 16 × 16 blocks within
     * Colorizing.MAX_ERROR — and what that one run took; null where it runs wrong. One run is all the test's time
     * allows for a network this size, so its time counts its first run's warming up too: a little against the device.
     */
    fun colorizer(net: Colorize, unit: String, progress: Progress, log: (String) -> Unit = {}): NeuralSelfTest.Timing? {
        val size = Colorizing.SIZE
        progress.running(size)
        val grey = checkGrey()
        val start = System.nanoTime()
        val ab = net.run(grey) ?: return null
        val ms = (System.nanoTime() - start) / 1e6
        if (ab.size != 2 * size * size) return null
        val expected = checkAb()
        var error = 0.0
        for (i in expected.indices) {
            val plane = i / 256
            val by = i % 256 / 16
            val bx = i % 16
            var sum = 0.0
            val corner = plane * size * size + by * 16 * size + bx * 16
            for (y in 0 until 16) for (x in 0 until 16) sum += ab[corner + y * size + x]
            error += abs(sum / 256 - expected[i])
        }
        error /= expected.size
        log("check on $unit: off by $error, $ms ms")
        if (!Colorizing.rightEnough(error)) return null
        progress.timed(size, ms)
        return NeuralSelfTest.Timing(unit, mapOf(size to ms))
    }

    // The colouriser's check picture as the network's input: its grey in R, G and B.
    private fun checkGrey(): FloatArray {
        val bytes = requireNotNull(NeuralChecks::class.java.getResourceAsStream("/colorize/check.grey")).use {
            it.readBytes()
        }
        val plane = Colorizing.SIZE * Colorizing.SIZE
        return FloatArray(3 * plane) { (bytes[it % plane].toInt() and 0xFF) / 255f }
    }

    // What DDColor's PyTorch model makes of it, averaged over 16 × 16 blocks: 256 a values, then 256 b.
    private fun checkAb(): DoubleArray =
        requireNotNull(NeuralChecks::class.java.getResourceAsStream("/colorize/check.ab")).bufferedReader().use { r ->
            r.readLines().filter { it.isNotBlank() }.map { it.trim().toDouble() }.toDoubleArray()
        }

    /**
     * A test frame: smooth random blotches about [CELL] pixels across in each colour, moved 5 × 3 pixels per [shift].
     * Nothing in it repeats, so its motion has one answer — stripes would let a network take a move for one the other
     * way.
     */
    fun testFrame(w: Int, h: Int, shift: Int): ByteBuffer = ByteBuffer.allocateDirect(w * h * 4).also { b ->
        for (y in 0 until h) for (x in 0 until w) {
            val sx = x + shift * 5
            val sy = y + shift * 3
            for (c in 0 until 3) b.put(noise(sx, sy, c).toByte())
            b.put(-1)
        }
        b.rewind()
    }

    /**
     * The upscaler's test picture: flat colours with round edges, as drawings are — a disc in each cell of a
     * [cell]-pixel grid, its radius 7/24 to 20/24 of the cell, each its own colour, on a light ground (the desktop
     * check's `drawing`).
     */
    fun drawing(w: Int, h: Int, cell: Int = 24): ByteBuffer = ByteBuffer.allocateDirect(w * h * 4).also { b ->
        for (y in 0 until h) for (x in 0 until w) {
            val cx = x / cell
            val cy = y / cell
            val dx = x - (cx * cell + cell / 2)
            val dy = y - (cy * cell + cell / 2)
            val radius = (7 + level(cx, cy, 3) % 14) * cell / 24
            val inside = dx * dx + dy * dy <= radius * radius
            for (c in 0 until 3) b.put((if (inside) level(cx, cy, c) else 230).toByte())
            b.put(-1)
        }
        b.rewind()
    }

    // Value noise: a random level per cell corner, bilinear between them; the same on every device.
    private fun noise(x: Int, y: Int, channel: Int): Int {
        val cx = Math.floorDiv(x, CELL)
        val cy = Math.floorDiv(y, CELL)
        val fx = (x - cx * CELL).toFloat() / CELL
        val fy = (y - cy * CELL).toFloat() / CELL
        val top = level(cx, cy, channel) * (1 - fx) + level(cx + 1, cy, channel) * fx
        val bottom = level(cx, cy + 1, channel) * (1 - fx) + level(cx + 1, cy + 1, channel) * fx
        return (top * (1 - fy) + bottom * fy).toInt()
    }

    private fun level(cx: Int, cy: Int, channel: Int): Int {
        var v = cx * 73856093 xor cy * 19349663 xor channel * 83492791
        v = (v xor (v ushr 13)) * 0x5bd1e995
        return (v xor (v ushr 15)) and 0xFF
    }

    private fun ByteBuffer.byte(at: Int): Int = get(at).toInt() and 0xFF

    /** In-betweens timed per size, in a stream after one pair to warm up; upscaled pictures after one. */
    private const val SAMPLE_RUNS = 2

    /** How many times the budget an in-between may take warming up before the self-test stops timing it. */
    private const val FAR_TOO_SLOW = 10

    /**
     * The side of the check's square test pictures: small, so a network far too slow for the device is told so at
     * once, without a long job for the graphics chip, which would hold up the whole screen meanwhile.
     */
    const val CHECK_SIDE = 128

    /** The margins the checks leave out: where the motion uncovers what neither frame shows, the picture's border. */
    private const val EDGE = 20
    private const val ENLARGER_EDGE = 4

    private const val CELL = 8
}
