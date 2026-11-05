package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayGainTest {

    @Test
    fun vorbisCommentsAsFoobarAndMetaflacWriteThem() {
        val gain = ReplayGain.parse(
            listOf(
                "REPLAYGAIN_TRACK_GAIN" to "-6.48 dB",
                "REPLAYGAIN_TRACK_PEAK" to "0.988769",
                "REPLAYGAIN_ALBUM_GAIN" to "-7.03 dB",
                "REPLAYGAIN_ALBUM_PEAK" to "1.000000",
                "ARTIST" to "Somebody",
            ),
        )!!
        assertEquals(-6.48f, gain.trackGainDb!!, 1e-6f)
        assertEquals(0.988769f, gain.trackPeak!!, 1e-6f)
        assertEquals(-7.03f, gain.albumGainDb!!, 1e-6f)
        assertEquals(1f, gain.albumPeak!!, 1e-6f)
    }

    @Test
    fun keysInAnyCaseAndBehindAFreeformDomain() {
        // ID3 TXXX descriptions as lower case, iTunes freeform atoms with their domain, a value without the unit.
        assertEquals(2.5f, ReplayGain.parse(listOf("replaygain_track_gain" to "+2.50 dB"))!!.trackGainDb!!, 1e-6f)
        assertEquals(
            -3.1f,
            ReplayGain.parse(listOf("com.apple.iTunes:replaygain_track_gain" to "-3.10"))!!.trackGainDb!!,
            1e-6f,
        )
        // A decimal comma, as some taggers in German locales wrote it.
        assertEquals(-1.25f, ReplayGain.parse(listOf("REPLAYGAIN_TRACK_GAIN" to "-1,25 dB"))!!.trackGainDb!!, 1e-6f)
    }

    @Test
    fun opusR128GainsAreQ78RelativeToMinus23Lufs() {
        // RFC 7845 5.2.1: -1280 / 256 = -5 dB to -23 LUFS, which is 0 dB to ReplayGain's -18 LUFS.
        val gain = ReplayGain.parse(listOf("R128_TRACK_GAIN" to "-1280", "R128_ALBUM_GAIN" to "512"))!!
        assertEquals(0f, gain.trackGainDb!!, 1e-6f)
        assertEquals(7f, gain.albumGainDb!!, 1e-6f)
    }

    @Test
    fun withoutAUsableGainThereIsNone() {
        assertNull(ReplayGain.parse(listOf("TITLE" to "x")))
        assertNull(ReplayGain.parse(listOf("REPLAYGAIN_TRACK_GAIN" to "loud")))
        assertNull(ReplayGain.parse(listOf("REPLAYGAIN_TRACK_PEAK" to "0.5")))
    }

    @Test
    fun theTrackGainIsPlayedButNeverPastThePeak() {
        assertEquals(-6.48f, ReplayGain(-6.48f, 0.98f, null, null).gainDb, 1e-6f)
        // +6 dB would push a 0.8 peak past full scale; it stops at 20 log10(1 / 0.8) = 1.94 dB.
        assertEquals(1.9382f, ReplayGain(6f, 0.8f, null, null).gainDb, 1e-4f)
        // Only an album gain: that one.
        assertEquals(-2f, ReplayGain(null, null, -2f, 0.5f).gainDb, 1e-6f)
    }
}
