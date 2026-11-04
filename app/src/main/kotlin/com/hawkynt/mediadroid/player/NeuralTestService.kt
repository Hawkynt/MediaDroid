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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Runs a neural network's self-test (NeuralInterpolator.selfTest) in a process of its own (`:neuraltest`): a network
 * that takes more memory than the device gives, or so long a GPU job that the GPU is lost for the process, ends only
 * that process — the app goes on, and the switch stays off. The process ends after each test, so the next starts
 * afresh.
 */
class NeuralTestService : Service() {

    private val requests = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            val reply = message.replyTo
            val network = message.data.getString(NETWORK)
                ?.let { runCatching { PictureAdjustment.Interpolation.valueOf(it) }.getOrNull() }
            if (reply != null && network != null) {
                thread(name = "neural-test") {
                    val timings = NeuralInterpolator.selfTest(applicationContext, network)
                    val result = Message.obtain(null, RESULT).apply {
                        data = Bundle().apply {
                            putStringArrayList(
                                TIMINGS,
                                ArrayList(
                                    timings.map { NeuralInterpolation.Passed(it.unit.name, it.msByHeight).encode() },
                                ),
                            )
                        }
                    }
                    runCatching { reply.send(result) }
                    // Whatever the networks left behind — memory, a lost GPU — goes with the process.
                    Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, END_DELAY_MS)
                }
            }
            true
        },
    )

    override fun onBind(intent: Intent): IBinder = requests.binder

    /** How a self-test in the test process came out. */
    sealed interface Outcome {
        /** It ran: what each unit that ran the network right took, fastest first (empty where none did). */
        data class Ran(val timings: List<NeuralInterpolator.Timing>) : Outcome

        /** The test process ended before it answered: the network was more than the device could give. */
        data object Ended : Outcome
    }

    companion object {
        private const val TEST = 1
        private const val RESULT = 2
        private const val NETWORK = "network"
        private const val TIMINGS = "timings"
        private const val END_DELAY_MS = 300L
        private const val LONGEST_MINUTES = 15L

        /** [network]'s self-test in the test process; blocks until it answers or ends — not on the main thread. */
        fun selfTest(context: Context, network: PictureAdjustment.Interpolation): Outcome {
            val outcome = AtomicReference<Outcome?>(null)
            val done = CountDownLatch(1)
            fun finish(result: Outcome) {
                if (outcome.compareAndSet(null, result)) done.countDown()
            }
            val replies = Messenger(
                Handler(Looper.getMainLooper()) { message ->
                    if (message.what == RESULT) {
                        val timings = message.data.getStringArrayList(TIMINGS).orEmpty().mapNotNull { text ->
                            NeuralInterpolation.Passed.decode(text)?.let { passed ->
                                runCatching { NeuralUpscaler.Unit.valueOf(passed.unit) }.getOrNull()
                                    ?.let { NeuralInterpolator.Timing(it, passed.msByHeight) }
                            }
                        }
                        finish(Outcome.Ran(timings))
                    }
                    true
                },
            )
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val request = Message.obtain(null, TEST).apply {
                        data = Bundle().apply { putString(NETWORK, network.name) }
                        replyTo = replies
                    }
                    runCatching { Messenger(binder).send(request) }.onFailure { finish(Outcome.Ended) }
                }

                override fun onServiceDisconnected(name: ComponentName) = finish(Outcome.Ended)

                override fun onBindingDied(name: ComponentName) = finish(Outcome.Ended)
            }
            val app = context.applicationContext
            if (!app.bindService(Intent(app, NeuralTestService::class.java), connection, Context.BIND_AUTO_CREATE)) {
                return Outcome.Ended
            }
            done.await(LONGEST_MINUTES, TimeUnit.MINUTES)
            runCatching { app.unbindService(connection) }
            return outcome.get() ?: Outcome.Ended
        }
    }
}
