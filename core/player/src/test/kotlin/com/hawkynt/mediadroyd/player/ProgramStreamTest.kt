package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramStreamTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `an MPEG-1 system stream opens with a pack header`() {
        assertTrue(ProgramStream.opensWithPack(bytes(0x00, 0x00, 0x01, 0xBA, 0x21, 0x00, 0x01)))
    }

    @Test
    fun `an MPEG-2 program stream opens with a pack header too`() {
        assertTrue(ProgramStream.opensWithPack(bytes(0x00, 0x00, 0x01, 0xBA, 0x44, 0x00, 0x04)))
    }

    @Test
    fun `an MP3 with or without its tag does not`() {
        assertFalse(ProgramStream.opensWithPack(bytes(0x49, 0x44, 0x33, 0x04, 0x00)))
        assertFalse(ProgramStream.opensWithPack(bytes(0xFF, 0xFB, 0x90, 0x64)))
    }

    @Test
    fun `nor does a file shorter than a start code, or another start code`() {
        assertFalse(ProgramStream.opensWithPack(bytes(0x00, 0x00, 0x01)))
        assertFalse(ProgramStream.opensWithPack(bytes(0x00, 0x00, 0x01, 0xB3, 0x2D)))
    }
}
