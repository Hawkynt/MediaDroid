package com.hawkynt.mediadroid.player

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs a neural network's self-test (NeuralChecks, through NeuralInterpolator's, NeuralEnlarger's or NeuralColorizer's
 * selfTest) on one unit in a process
 * of its own (`:neuraltest`): a network that takes more memory than the device gives, or so long a GPU job that the GPU is lost
 * for the process, ends only that process — and a test that takes longer than [NeuralInterpolation.MAX_WAIT_MS] all
 * told is cut short by ending it, as a run on the device cannot be stopped otherwise.
 * The app ends the process after each test, so the next starts afresh.
 */
class NeuralTestService : Service() {

    // One test per process: the process is ended after it.
    private var busy = false

    private val requests = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            val reply = message.replyTo
            val name = message.data.getString(NETWORK).orEmpty()
            val kind = message.data.getString(KIND).orEmpty()
            val unit = message.data.getString(UNIT)?.let { runCatching { NeuralUpscaler.Unit.valueOf(it) }.getOrNull() }
            if (reply != null && unit != null && !busy) {
                busy = true
                thread(name = "neural-test") { test(reply, name, kind, unit) }
            }
            true
        },
    )

    private fun test(reply: Messenger, name: String, kind: String, unit: NeuralUpscaler.Unit) {
        fun send(what: Int, height: Int = 0, ms: Double = 0.0, passed: String? = null) {
            val message = Message.obtain(null, what, height, Process.myPid()).apply {
                data = Bundle().apply {
                    putDouble(MS, ms)
                    putString(PASSED, passed)
                }
            }
            runCatching { reply.send(message) }
        }
        send(STARTED)
        val progress = object : NeuralChecks.Progress {
            override fun running(height: Int) = send(RUNNING, height)

            override fun timed(height: Int, ms: Double) = send(TIMED, height, ms)
        }
        val timing = when (kind) {
            UPSCALER ->
                NeuralEnlarging.choice(name)?.let { NeuralEnlarger.selfTest(applicationContext, it, unit, progress) }
            COLORIZER -> NeuralColorizer.selfTest(applicationContext, unit, progress)
            else -> runCatching { PictureAdjustment.Interpolation.valueOf(name) }.getOrNull()
                ?.let { NeuralInterpolator.selfTest(applicationContext, it, unit, progress) }
        }
        // The app ends the process on this answer — whatever the network left behind, memory or a lost GPU, goes
        // with it.
        send(RESULT, passed = timing?.let { NeuralInterpolation.Passed(it.unit, it.msByHeight).encode() })
    }

    override fun onBind(intent: Intent): IBinder = requests.binder

    companion object {
        private const val TEST = 1
        private const val STARTED = 2
        private const val RUNNING = 3
        private const val TIMED = 4
        private const val RESULT = 5
        private const val NETWORK = "network"
        private const val KIND = "kind"
        private const val MOTION = "motion"
        private const val UPSCALER = "upscaler"
        private const val COLORIZER = "colorizer"
        private const val UNIT = "unit"
        private const val MS = "ms"
        private const val PASSED = "passed"
        private const val POLL_MS = 50L
        private const val GONE_WAIT_MS = 3000L

        /**
         * [network]'s self-test on the GPU, then the CPU unless the GPU kept up, each in a fresh test process, all
         * of it within [NeuralInterpolation.MAX_WAIT_MS] (NeuralSelfTest.across); blocks until both answered, ended
         * or were cut short — not on the main thread.
         */
        fun selfTest(context: Context, network: PictureAdjustment.Interpolation): NeuralSelfTest.Outcome =
            NeuralSelfTest.across(NeuralInterpolation::fastEnough) { unit, until ->
                selfTest(context, network.name, MOTION, NeuralUpscaler.Unit.valueOf(unit), until)
            }

        /** [choice]'s self-test (a model of waifu2x, Real-CUGAN or RealSR; NeuralEnlarger.selfTest), the same way. */
        fun selfTest(context: Context, choice: NeuralEnlarging.Choice): NeuralSelfTest.Outcome =
            NeuralSelfTest.across(NeuralEnlarging::fastEnough) { unit, until ->
                selfTest(context, choice.key, UPSCALER, NeuralUpscaler.Unit.valueOf(unit), until)
            }

        /** DDColor's self-test (NeuralColorizer.selfTest), the same way. */
        fun selfTestColorizer(context: Context): NeuralSelfTest.Outcome =
            NeuralSelfTest.across(Colorizing::fastEnough) { unit, until ->
                selfTest(context, "DDCOLOR", COLORIZER, NeuralUpscaler.Unit.valueOf(unit), until)
            }

        /**
         * The test on [unit] in a fresh test process, cut short at [until] (System.nanoTime) — its starting and
         * loading included; null where the process ended without answering.
         */
        private fun selfTest(
            context: Context,
            networkName: String,
            kind: String,
            unit: NeuralUpscaler.Unit,
            until: Long,
        ): NeuralSelfTest.Answer? {
            val lock = Any()
            val done = CountDownLatch(1)
            val gone = CountDownLatch(1)
            var answer: NeuralSelfTest.Answer? = null
            var pid = 0
            var runningHeight = 0
            var runningSince = 0L
            val times = LinkedHashMap<Int, Double>()
            val replies = Messenger(
                Handler(Looper.getMainLooper()) { message ->
                    synchronized(lock) {
                        pid = message.arg2
                        when (message.what) {
                            RUNNING -> {
                                runningHeight = message.arg1
                                runningSince = System.nanoTime()
                            }
                            TIMED -> {
                                times[message.arg1] = message.data.getDouble(MS)
                                runningSince = 0L
                            }
                            RESULT -> {
                                val passed = message.data.getString(PASSED)
                                    ?.let { NeuralInterpolation.Passed.decode(it) }
                                answer = NeuralSelfTest.Answer(
                                    passed?.let { NeuralSelfTest.Timing(unit.name, it.msByHeight) },
                                )
                                done.countDown()
                            }
                        }
                    }
                    true
                },
            )
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val request = Message.obtain(null, TEST).apply {
                        data = Bundle().apply {
                            putString(NETWORK, networkName)
                            putString(KIND, kind)
                            putString(UNIT, unit.name)
                        }
                        replyTo = replies
                    }
                    runCatching { Messenger(binder).send(request) }.onFailure { done.countDown() }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    gone.countDown()
                    done.countDown()
                }

                override fun onBindingDied(name: ComponentName) {
                    gone.countDown()
                    done.countDown()
                }
            }
            val app = context.applicationContext
            if (!app.bindService(Intent(app, NeuralTestService::class.java), connection, Context.BIND_AUTO_CREATE)) {
                return null
            }
            while (!done.await(POLL_MS, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() < until) continue
                synchronized(lock) {
                    if (answer == null) {
                        // Out of time, far beyond the budget already: cut short, the size being made (the smallest
                        // while still loading) counting as having taken as long as a whole test may.
                        val smallest = when (kind) {
                            UPSCALER -> PictureCheck.HEIGHT
                            COLORIZER -> Colorizing.SIZE
                            else -> runCatching { PictureAdjustment.Interpolation.valueOf(networkName) }.getOrNull()
                                ?.let { NeuralInterpolation.testSizes(it).first().height }
                                ?: NeuralInterpolation.TEST_SIZES.first().height
                        }
                        answer = NeuralSelfTest.Answer(
                            NeuralSelfTest.cutShort(unit.name, times, runningHeight, smallest),
                        )
                    }
                }
                break
            }
            // The process ends here and now, and is waited for: bound again too soon, the next unit's test would run
            // in it beside whatever this one left.
            synchronized(lock) { pid }.takeIf { it != 0 }?.let { Process.killProcess(it) }
            gone.await(GONE_WAIT_MS, TimeUnit.MILLISECONDS)
            runCatching { app.unbindService(connection) }
            return synchronized(lock) { answer }
        }
    }
}
