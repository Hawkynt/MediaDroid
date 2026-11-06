package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.engine.desktop.HardwareDecoding.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareDecodingTest {

    @Test
    fun `Windows tries D3D11VA and then DXVA2`() {
        assertEquals(listOf(Device.D3D11VA, Device.DXVA2), HardwareDecoding.candidates("Windows 11", false))
    }

    @Test
    fun `Linux tries NVDEC and then VA-API`() {
        assertEquals(listOf(Device.NVDEC, Device.VAAPI), HardwareDecoding.candidates("Linux", false))
    }

    @Test
    fun `software first, or a system without a known device, tries none`() {
        assertTrue(HardwareDecoding.candidates("Windows 10", true).isEmpty())
        assertTrue(HardwareDecoding.candidates("Linux", true).isEmpty())
        assertTrue(HardwareDecoding.candidates("Mac OS X", false).isEmpty())
    }

    @Test
    fun `the first device the codec supports and that opens is picked`() {
        val opened = mutableListOf<Device>()
        val picked = HardwareDecoding.pick(
            listOf(Device.NVDEC, Device.VAAPI),
            supports = { true },
            open = { device -> opened += device; if (device == Device.VAAPI) "va" else null },
        )
        assertEquals(Device.VAAPI to "va", picked)
        assertEquals(listOf(Device.NVDEC, Device.VAAPI), opened)
    }

    @Test
    fun `a device the codec cannot decode on is never opened`() {
        val opened = mutableListOf<Device>()
        val picked = HardwareDecoding.pick(
            listOf(Device.D3D11VA, Device.DXVA2),
            supports = { it == Device.DXVA2 },
            open = { device -> opened += device; "dx" },
        )
        assertEquals(Device.DXVA2 to "dx", picked)
        assertEquals(listOf(Device.DXVA2), opened)
    }

    @Test
    fun `none that opens leaves it to software`() {
        assertNull(HardwareDecoding.pick(listOf(Device.NVDEC, Device.VAAPI), { true }) { null })
        assertNull(HardwareDecoding.pick(emptyList(), { true }) { "never" })
    }

    @Test
    fun `the device names are FFmpeg's own`() {
        // av_hwdevice_get_type_name: what av_hwdevice_find_type_by_name takes.
        assertEquals(listOf("d3d11va", "dxva2", "cuda", "vaapi"), Device.entries.map { it.ffmpegName })
    }
}
