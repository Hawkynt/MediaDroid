package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Test

class NeuralSelfTestTest {
    private fun timing(unit: String, ms: Double) = NeuralSelfTest.Timing(unit, mapOf(480 to ms))

    @Test
    fun `a GPU that keeps up spares the CPU its test`() {
        val asked = ArrayList<String>()
        val outcome = NeuralSelfTest.across(NeuralInterpolation::fastEnough, { 0L }) { unit, _ ->
            asked += unit
            NeuralSelfTest.Answer(timing(unit, 10.0))
        }
        assertEquals(listOf("GPU"), asked)
        assertEquals(NeuralSelfTest.Outcome.Ran(listOf(timing("GPU", 10.0))), outcome)
    }

    @Test
    fun `a slow GPU is followed by the CPU, fastest first, each starting afresh`() {
        val until = ArrayList<Long>()
        var now = 1_000L
        val outcome = NeuralSelfTest.across(NeuralInterpolation::fastEnough, { now }) { unit, deadline ->
            until += deadline.until
            now += 500L
            NeuralSelfTest.Answer(timing(unit, if (unit == "GPU") 90.0 else 60.0))
        }
        val loading = (NeuralSelfTest.LOAD_WAIT_MS * 1_000_000).toLong()
        assertEquals(listOf(1_000L + loading, 1_500L + loading), until)
        assertEquals(NeuralSelfTest.Outcome.Ran(listOf(timing("CPU", 60.0), timing("GPU", 90.0))), outcome)
    }

    @Test
    fun `an ended process with nothing measured makes the test ended, units that only ran wrong do not`() {
        assertEquals(
            NeuralSelfTest.Outcome.Ended,
            NeuralSelfTest.across(NeuralInterpolation::fastEnough, { 0L }) { _, _ -> null },
        )
        assertEquals(
            NeuralSelfTest.Outcome.Ended,
            NeuralSelfTest.across(NeuralInterpolation::fastEnough, { 0L }) { unit, _ ->
                if (unit == "GPU") null else NeuralSelfTest.Answer(null)
            },
        )
        assertEquals(
            NeuralSelfTest.Outcome.Ran(emptyList()),
            NeuralSelfTest.across(NeuralInterpolation::fastEnough, { 0L }) { _, _ -> NeuralSelfTest.Answer(null) },
        )
        assertEquals(
            NeuralSelfTest.Outcome.Ran(listOf(timing("CPU", 15.0))),
            NeuralSelfTest.across(NeuralInterpolation::fastEnough, { 0L }) { unit, _ ->
                if (unit == "GPU") null else NeuralSelfTest.Answer(timing(unit, 15.0))
            },
        )
    }

    @Test
    fun `a unit cut short counts the size it was making as taking the whole test's time`() {
        assertEquals(
            NeuralSelfTest.Timing("GPU", mapOf(480 to 12.0, 576 to NeuralInterpolation.MAX_WAIT_MS)),
            NeuralSelfTest.cutShort("GPU", mapOf(480 to 12.0), runningHeight = 576, smallestHeight = 480),
        )
        assertEquals(
            NeuralSelfTest.Timing("CPU", mapOf(480 to NeuralInterpolation.MAX_WAIT_MS)),
            NeuralSelfTest.cutShort("CPU", emptyMap(), runningHeight = 0, smallestHeight = 480),
        )
    }

    @Test
    fun `a unit has long to load its network, then its share of the test's time to run it`() {
        val deadline = NeuralSelfTest.Deadline(startNanos = 100L, runNanos = 1_000_000_000L, nowNanos = { 100L })
        assertEquals(100L + (NeuralSelfTest.LOAD_WAIT_MS * 1_000_000).toLong(), deadline.until)
        deadline.loaded(3_400_000_000L)
        assertEquals(4_400_000_000L, deadline.until)
        deadline.loaded(3_900_000_000L)
        assertEquals(4_400_000_000L, deadline.until)
        assertEquals(false, deadline.passed(4_399_999_999L))
        assertEquals(true, deadline.passed(4_400_000_000L))
    }
}
