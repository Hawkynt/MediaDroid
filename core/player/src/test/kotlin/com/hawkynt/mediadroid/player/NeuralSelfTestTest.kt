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
    fun `a slow GPU is followed by the CPU, fastest first, each with its share of the time`() {
        val until = ArrayList<Long>()
        val outcome = NeuralSelfTest.across(NeuralInterpolation::fastEnough, { 1_000L }) { unit, at ->
            until += at
            NeuralSelfTest.Answer(timing(unit, if (unit == "GPU") 90.0 else 60.0))
        }
        assertEquals(listOf(1_000L + 1_000_000_000L, 1_000L + 2_000_000_000L), until)
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
}
