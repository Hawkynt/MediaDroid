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
 * Runs a neural network's self-test (NeuralInterpolator.selfTest) on one unit in a process of its own
 * (`:neuraltest`): a network that takes more memory than the device gives, or so long a GPU job that the GPU is lost
 * for the process, ends only that process — and an in-between that takes longer than
 * [NeuralInterpolation.MAX_WAIT_MS] is cut short by ending it, as a run on the device cannot be stopped otherwise.
 * The app ends the process after each test, so the next starts afresh.
 */
class NeuralTestService : Service() {

    // One test per process: the process is ended after it.
    private var busy = false

    private val requests = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            val reply = message.replyTo
            val network = message.data.getString(NETWORK)
                ?.let { runCatching { PictureAdjustment.Interpolation.valueOf(it) }.getOrNull() }
            val unit = message.data.getString(UNIT)?.let { runCatching { NeuralUpscaler.Unit.valueOf(it) }.getOrNull() }
            if (reply != null && network != null && unit != null && !busy) {
                busy = true
                thread(name = "neural-test") { test(reply, network, unit) }
            }
            true
        },
    )

    private fun test(reply: Messenger, network: PictureAdjustment.Interpolation, unit: NeuralUpscaler.Unit) {
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
        val timing = NeuralInterpolator.selfTest(
            applicationContext,
            network,
            unit,
            object : NeuralInterpolator.Companion.Progress {
                override fun running(height: Int) = send(RUNNING, height)

                override fun timed(height: Int, ms: Double) = send(TIMED, height, ms)
            },
        )
        // The app ends the process on this answer — whatever the network left behind, memory or a lost GPU, goes
        // with it.
        send(RESULT, passed = timing?.let { NeuralInterpolation.Passed(it.unit.name, it.msByHeight).encode() })
    }

    override fun onBind(intent: Intent): IBinder = requests.binder

    /** How a self-test in the test process came out. */
    sealed interface Outcome {
        /** It ran: what each unit that ran the network right took, fastest first (empty where none did). */
        data class Ran(val timings: List<NeuralInterpolator.Timing>) : Outcome

        /** Every unit's test process ended before it answered: the network was more than the device could give. */
        data object Ended : Outcome
    }

    companion object {
        private const val TEST = 1
        private const val STARTED = 2
        private const val RUNNING = 3
        private const val TIMED = 4
        private const val RESULT = 5
        private const val NETWORK = "network"
        private const val UNIT = "unit"
        private const val MS = "ms"
        private const val PASSED = "passed"
        private const val POLL_MS = 50L
        private const val GONE_WAIT_MS = 3000L
        private const val LONGEST_MINUTES = 10L

        /**
         * [network]'s self-test on the GPU, then the CPU unless the GPU kept up, each in a fresh test process; blocks
         * until both answered, ended or were cut short — not on the main thread.
         */
        fun selfTest(context: Context, network: PictureAdjustment.Interpolation): Outcome {
            val timings = ArrayList<NeuralInterpolator.Timing>()
            var ended = 0
            for (unit in listOf(NeuralUpscaler.Unit.GPU, NeuralUpscaler.Unit.CPU)) {
                if (timings.any { NeuralInterpolation.fastEnough(it.msByHeight.values.first()) }) break
                when (val one = selfTest(context, network, unit)) {
                    null -> ended++
                    else -> one.timing?.let { timings += it }
                }
            }
            return if (timings.isEmpty() && ended > 0) {
                Outcome.Ended
            } else {
                Outcome.Ran(timings.sortedBy { it.msByHeight.values.first() })
            }
        }

        /** One unit's answer: its timing, or null where it cannot run the network or runs it wrong. */
        private class Answer(val timing: NeuralInterpolator.Timing?)

        /** The test on [unit] in a fresh test process; null where the process ended without answering. */
        private fun selfTest(
            context: Context,
            network: PictureAdjustment.Interpolation,
            unit: NeuralUpscaler.Unit,
        ): Answer? {
            val lock = Any()
            val done = CountDownLatch(1)
            val gone = CountDownLatch(1)
            var answer: Answer? = null
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
                                answer = Answer(passed?.let { NeuralInterpolator.Timing(unit, it.msByHeight) })
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
                            putString(NETWORK, network.name)
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
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(LONGEST_MINUTES)
            while (!done.await(POLL_MS, TimeUnit.MILLISECONDS) && System.nanoTime() < deadline) {
                synchronized(lock) {
                    val waited = if (runningSince == 0L) 0.0 else (System.nanoTime() - runningSince) / 1e6
                    if (waited >= NeuralInterpolation.MAX_WAIT_MS && pid != 0 && answer == null) {
                        // Far beyond the budget already: the run is cut short, and counts as having taken this long.
                        times[runningHeight] = NeuralInterpolation.MAX_WAIT_MS
                        answer = Answer(NeuralInterpolator.Timing(unit, LinkedHashMap(times)))
                        done.countDown()
                    }
                }
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
