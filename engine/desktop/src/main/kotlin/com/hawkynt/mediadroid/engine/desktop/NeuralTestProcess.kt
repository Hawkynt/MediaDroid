package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.player.Colorizing
import com.hawkynt.mediadroid.player.NeuralChecks
import com.hawkynt.mediadroid.player.NeuralEnlarging
import com.hawkynt.mediadroid.player.NeuralInterpolation
import com.hawkynt.mediadroid.player.NeuralSelfTest
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureCheck
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A neural network's self-test on the desktop, as NeuralTestService runs it on Android: each unit (NeuralSelfTest,
 * the GPU then the CPU) in a process of its own — a JVM started for it running [main] — so a network that takes more
 * memory than the machine gives, or a GPU job that loses the device, ends only that process; a test past its share of
 * the two seconds is cut short by ending it. The process tells how far it is on its output, one line a step.
 */
object NeuralTestProcess {
    private const val PREFIX = "@mediadroid "
    private const val MOTION = "motion"
    private const val UPSCALER = "upscaler"
    private const val COLORIZER = "colorizer"

    /** Test processes started so far. */
    @Volatile var processes = 0
        private set

    /** [network]'s self-test with the models in [models]; blocks. */
    fun selfTest(models: File, network: PictureAdjustment.Interpolation): NeuralSelfTest.Outcome =
        NeuralSelfTest.across(NeuralInterpolation::fastEnough) { unit, until ->
            one(models, MOTION, network.name, unit, until, NeuralInterpolation.testSizes(network).first().height)
        }

    /** [choice]'s self-test with the models in [models]; blocks. */
    fun selfTest(models: File, choice: NeuralEnlarging.Choice): NeuralSelfTest.Outcome =
        NeuralSelfTest.across(NeuralEnlarging::fastEnough) { unit, until ->
            one(models, UPSCALER, choice.key, unit, until, PictureCheck.HEIGHT)
        }

    /** The colouriser's self-test with the models in [models]; blocks. */
    fun selfTestColorizer(models: File): NeuralSelfTest.Outcome =
        NeuralSelfTest.across(Colorizing::fastEnough) { unit, until ->
            one(models, COLORIZER, "DDCOLOR", unit, until, Colorizing.SIZE)
        }

    /** The JVM a test runs in: this one's own `java`; null where the runtime carries none. */
    private fun java(): File? = File(System.getProperty("java.home"), "bin")
        .let { bin -> listOf(File(bin, "java"), File(bin, "java.exe")) }
        .firstOrNull { it.canExecute() }

    private fun one(
        models: File,
        kind: String,
        name: String,
        unit: String,
        until: Long,
        smallestHeight: Int,
    ): NeuralSelfTest.Answer? {
        val jvm = java() ?: return inProcess(models, kind, name, unit, until, smallestHeight)
        val command = buildList {
            add(jvm.path)
            System.getProperty(NativeNetworks.FOLDER_PROPERTY)?.let { add("-D${NativeNetworks.FOLDER_PROPERTY}=$it") }
            System.getProperty("java.library.path")?.let { add("-Djava.library.path=$it") }
            add("-cp")
            add(System.getProperty("java.class.path"))
            add(NeuralTestProcess::class.java.name)
            addAll(listOf(models.path, kind, name, unit))
        }
        val process = runCatching { ProcessBuilder(command).redirectErrorStream(false).start() }.getOrNull()
            ?: return inProcess(models, kind, name, unit, until, smallestHeight)
        processes++
        val err = process.errorStream
        thread(isDaemon = true) { err.copyTo(OutputStream.nullOutputStream()) }
        val lock = Any()
        val times = LinkedHashMap<Int, Double>()
        var running = 0
        var answer: NeuralSelfTest.Answer? = null
        val reader = thread(isDaemon = true) {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (!line.startsWith(PREFIX)) return@forEachLine
                val parts = line.removePrefix(PREFIX).split(' ')
                synchronized(lock) {
                    when (parts[0]) {
                        "RUNNING" -> running = parts[1].toInt()
                        "TIMED" -> times[parts[1].toInt()] = parts[2].toDouble()
                        "RESULT" -> answer = NeuralSelfTest.Answer(
                            parts.getOrNull(1)?.let { NeuralInterpolation.Passed.decode(it) }
                                ?.let { NeuralSelfTest.Timing(unit, it.msByHeight) },
                        )
                    }
                }
            }
        }
        val waitMs = ((until - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)
        val ended = process.waitFor(waitMs, TimeUnit.MILLISECONDS)
        if (!ended) process.destroyForcibly().waitFor(3, TimeUnit.SECONDS)
        reader.join(1_000)
        return synchronized(lock) {
            // Out of time: cut short, the size being made counting as the whole test's time. Ended by itself
            // without an answer: the network was more than the machine could give.
            answer ?: if (ended) null else {
                NeuralSelfTest.Answer(NeuralSelfTest.cutShort(unit, times, running, smallestHeight))
            }
        }
    }

    // Without a JVM of its own to start (a runtime cut down to the app's), the test runs on a thread here: cut short
    // at [until] as well, though a network that takes the process down takes the app along.
    private fun inProcess(
        models: File,
        kind: String,
        name: String,
        unit: String,
        until: Long,
        smallestHeight: Int,
    ): NeuralSelfTest.Answer? {
        val lock = Any()
        val times = LinkedHashMap<Int, Double>()
        var running = 0
        var answer: NeuralSelfTest.Answer? = null
        val progress = object : NeuralChecks.Progress {
            override fun running(height: Int) = synchronized(lock) { running = height }
            override fun timed(height: Int, ms: Double) = synchronized(lock) { times[height] = ms }
        }
        val worker = thread(isDaemon = true, name = "neural-test") {
            val timing = runCatching { test(models, kind, name, unit, progress) }.getOrNull()
            synchronized(lock) { answer = NeuralSelfTest.Answer(timing) }
        }
        worker.join(((until - System.nanoTime()) / 1_000_000L).coerceAtLeast(1))
        return synchronized(lock) {
            answer ?: NeuralSelfTest.Answer(NeuralSelfTest.cutShort(unit, times, running, smallestHeight))
        }
    }

    /** The test itself, in whichever process runs it: the network opened on [unit] and checked (NeuralChecks). */
    internal fun test(
        models: File,
        kind: String,
        name: String,
        unit: String,
        progress: NeuralChecks.Progress,
    ): NeuralSelfTest.Timing? {
        val neural = DesktopNeural(models)
        val gpu = unit == "GPU"
        if (kind == COLORIZER) {
            return neural.colorizer(gpu)?.use { net -> NeuralChecks.colorizer({ net.run(it) }, unit, progress) }
        }
        return if (kind == UPSCALER) {
            val choice = NeuralEnlarging.choice(name) ?: return null
            neural.enlarger(choice, gpu)?.use { net ->
                NeuralChecks.enlarger({ p, w, h -> net.run(p, w, h) }, net.scale, unit, progress)
            }
        } else {
            val network = PictureAdjustment.Interpolation.valueOf(name)
            neural.interpolator(network, gpu)?.use { net ->
                NeuralChecks.interpolator(
                    { a, b, w, h, ia, ib, before, after -> net.run(a, b, w, h, ia, ib, before, after) },
                    unit,
                    progress,
                    sizes = NeuralInterpolation.testSizes(network),
                )
            }
        }
    }

    /** A test process: `<models> <motion|upscaler> <choice> <GPU|CPU>`, its steps and answer on its output. */
    @JvmStatic
    fun main(args: Array<String>) {
        val out = System.out
        fun say(line: String) = synchronized(out) {
            out.println(PREFIX + line)
            out.flush()
        }
        val progress = object : NeuralChecks.Progress {
            override fun running(height: Int) = say("RUNNING $height")
            override fun timed(height: Int, ms: Double) = say("TIMED $height $ms")
        }
        val timing = runCatching { test(File(args[0]), args[1], args[2], args[3], progress) }.getOrNull()
        say("RESULT" + (timing?.let { " " + NeuralInterpolation.Passed(it.unit, it.msByHeight).encode() } ?: ""))
        out.flush()
        Runtime.getRuntime().halt(0)
    }
}
