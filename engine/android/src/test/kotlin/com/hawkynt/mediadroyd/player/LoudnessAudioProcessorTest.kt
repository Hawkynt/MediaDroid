package com.hawkynt.mediadroyd.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class LoudnessAudioProcessorTest {

    private fun pcm(samples: ShortArray): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            asShortBuffer().put(samples)
        }

    @Test
    fun `hands the sound through the shared chain unchanged in layout`() {
        val file = FileLoudness("a.mkv#1:0", null)
        var gain = 6f
        var night = false
        val processor = LoudnessAudioProcessor({ gain }, { night }, { true }, { file })
        processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val chain = LoudnessChain(48_000, 2)
        for (n in 0 until 200) {
            gain = (n % 25 - 12).toFloat()
            night = n / 40 % 2 == 1
            val input = ShortArray(2048) { (12_000 * sin((n * 1024 + it / 2) * 0.05 + it % 2)).toInt().toShort() }
            processor.queueInput(pcm(input))
            val out = processor.output
            val actual = ShortArray(out.remaining() / 2).also { out.asShortBuffer().get(it) }
            chain.process(input, 0, 1024, gain, night, true, file)
            assertArrayEquals("buffer $n", input, actual)
        }
    }
}
