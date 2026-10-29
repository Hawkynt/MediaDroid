package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The field-order detector against FFmpeg 6.0's `idet` on the same pictures: its verdict for each frame
 * and over the frames so far, as its debug log prints them (the `.idet.txt` files in `resources/deinterlace`, made
 * with the commands in `make-input.py`).
 */
class FieldOrderTest {

    private fun verdicts(input: String): List<Pair<String, String>> {
        val bytes = resource("$input.gray")
        val count = bytes.size / (48 * 64)
        val detector = FieldOrder()
        // FFmpeg's idet looks at frame k with k - 1 and k + 1 around it, the first frame standing in for its
        // own predecessor; the last frame has no successor and is not judged.
        return (0 until count - 1).map { k ->
            val single = detector.add(plane(bytes, maxOf(k - 1, 0)), plane(bytes, k), plane(bytes, k + 1))
            single.label to detector.order.label
        }
    }

    private fun expected(input: String): List<Pair<String, String>> =
        String(resource("$input.idet.txt")).lines().filter { it.isNotBlank() }.map { line ->
            val (single, multi) = Regex("Single frame:\\s*(\\S+), Multi frame:\\s*(\\S+)").find(line)!!.destructured
            single to multi
        }

    @Test
    fun `top field first is told as FFmpeg tells it`() = assertEquals(expected("input"), verdicts("input"))

    @Test
    fun `bottom field first is told as FFmpeg tells it`() =
        assertEquals(expected("input-bff"), verdicts("input-bff"))

    @Test
    fun `progressive pictures are told as FFmpeg tells them`() =
        assertEquals(expected("input-progressive"), verdicts("input-progressive"))

    @Test
    fun `until a verdict the order is undetermined and taken as top field first`() {
        val detector = FieldOrder()
        assertEquals(FieldOrder.Type.UNDETERMINED, detector.order)
        assertEquals(true, detector.topFieldFirst)
    }
}
