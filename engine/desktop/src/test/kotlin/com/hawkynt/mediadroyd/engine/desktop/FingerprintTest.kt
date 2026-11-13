package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.NativeFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Chromaprint inside the native library (NativeFingerprint), against Chromaprint's own test of its API. */
class FingerprintTest {

    @Test
    fun `the fingerprint is Chromaprint's own, as its API test expects it`() {
        assertTrue("the native library did not load", NativeNetworks.loaded)
        // tests/test_api.cpp, TestFp: data/test_stereo_44100.raw fed as one channel at 44100 Hz, the default
        // algorithm, the fingerprint's simhash 3732003127.
        val raw = File(CHROMAPRINT, "tests/data/test_stereo_44100.raw").readBytes()
        val samples = ShortArray(raw.size / 2)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val items = requireNotNull(NativeFingerprint.fingerprint(samples, 44100, 1))
        assertEquals(3732003127L, simhash(items))
    }

    // Chromaprint's SimHash (src/simhash.cpp): each bit set where more than half the items, rounded down, have it.
    private fun simhash(items: IntArray): Long {
        val ones = IntArray(32)
        for (item in items) for (bit in 0 until 32) ones[bit] += item ushr bit and 1
        var hash = 0L
        for (bit in 0 until 32) if (ones[bit] > items.size / 2) hash = hash or (1L shl bit)
        return hash
    }

    private companion object {
        // The source tree :native:chromaprintSource unpacks (engine/desktop/build.gradle.kts).
        val CHROMAPRINT = File(System.getProperty("mediadroyd.chromaprint"))
    }
}
