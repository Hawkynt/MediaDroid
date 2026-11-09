package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.IntroCredits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Intros and credits found by their sound on the desktop, end to end (#480): generated episodes decoded by FFmpeg
 * (SoundPrints), fingerprinted by Chromaprint inside the native library, matched and placed (IntroCredits, as
 * SkipFinder does).
 */
class SoundPrintsTest {
    private val rate = 8000

    // [seconds] of a melody: a note of the scale every quarter second, chosen by [seed], with its octave above.
    private fun melody(seconds: Int, seed: Int): ShortArray {
        val random = Random(seed)
        val scale = doubleArrayOf(261.6, 293.7, 329.6, 349.2, 392.0, 440.0, 493.9, 523.3)
        val out = ShortArray(seconds * rate)
        var note = scale[0]
        for (i in out.indices) {
            if (i % (rate / 4) == 0) note = scale[random.nextInt(scale.size)]
            val t = i.toDouble() / rate
            val wave = sin(2 * PI * note * t) * 0.3 + sin(4 * PI * note * t) * 0.1
            out[i] = (wave * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    // An episode: a cold open of [coldOpen] seconds, the theme, its own story, the credits music, a closing sting.
    private fun episode(dir: File, n: Int, coldOpen: Int): File {
        val parts = listOf(melody(coldOpen, 10 + n), THEME, melody(STORY, 20 + n), CREDITS, melody(10, 30 + n))
        val samples = ShortArray(parts.sumOf { it.size }).also { out ->
            var at = 0
            for (part in parts) {
                part.copyInto(out, at)
                at += part.size
            }
        }
        return File(dir, "e$n.wav").also { wav(it, samples) }
    }

    // [samples] as a 16-bit mono WAV file at [rate].
    private fun wav(file: File, samples: ShortArray) {
        val data = samples.size * 2
        val out = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
        out.putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(data)
        samples.forEach { out.putShort(it) }
        file.writeBytes(out.array())
    }

    @Test
    fun `two episodes' intro and credits are found by their sound, each at its own time`() {
        assertTrue("the native library did not load", NativeNetworks.loaded)
        val dir = kotlin.io.path.createTempDirectory("episodes").toFile()
        try {
            val first = episode(dir, 1, coldOpen = 12)
            val second = episode(dir, 2, coldOpen = 31)
            fun print(file: File, closing: Boolean) = requireNotNull(SoundPrints.of(file.path, null, closing))
            // The edges a second inside (IntroCredits.MARGIN_MS), within a second: an item reads 0.37 s of sound, the
            // smoothing blurs a segment's edges, and the quarter-second notes around a stretch may happen to match.
            fun near(wantSeconds: Int, gotMs: Long) = assertEquals(wantSeconds * 1000.0, gotMs.toDouble(), 1000.0)
            fun inside(seconds: Int) = seconds + 1
            fun before(seconds: Int) = seconds - 1
            val openings = print(first, false) to print(second, false)
            val (introFirst, introSecond) =
                requireNotNull(IntroCredits.intro(openings.first.items, openings.second.items))
            near(inside(12), introFirst.startMs)
            near(before(12 + THEME_SECONDS), introFirst.endMs)
            near(inside(31), introSecond.startMs)
            val closings = print(first, true) to print(second, true)
            val (creditsFirst, creditsSecond) = requireNotNull(
                IntroCredits.credits(
                    closings.first.items, closings.first.startMs, closings.second.items, closings.second.startMs,
                ),
            )
            near(inside(12 + THEME_SECONDS + STORY), creditsFirst)
            near(inside(31 + THEME_SECONDS + STORY), creditsSecond)
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val THEME_SECONDS = 40
        // Long enough that the closing analysed (its last eight minutes) leaves the intro out.
        const val STORY = 600
        val THEME by lazy { SoundPrintsTest().melody(THEME_SECONDS, 1) }
        val CREDITS by lazy { SoundPrintsTest().melody(45, 2) }
    }
}
