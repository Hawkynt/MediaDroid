package com.hawkynt.mediadroid.engine.desktop

/**
 * Which of the graphics chip's video decoders [MediaReader] tries, through FFmpeg's hardware decoding (a device
 * context on the decoder, the pictures copied back to the processor for the stage): on Windows D3D11VA, then DXVA2
 * for the chips and drivers without it; on Linux NVDEC, then VA-API — an NVIDIA chip through its own decoder rather
 * than a VA-API layer translating to it, Intel and AMD through VA-API. A device that does not open, or opens and then
 * decodes nothing (NVDEC with no working NVIDIA chip behind it), hands over to the next one; whatever none takes — the
 * setting asking for software, no device, a codec the chip cannot decode, a profile the driver refuses — FFmpeg
 * decodes in software, as before.
 */
object HardwareDecoding {

    /** A hardware decoder: FFmpeg's device type [ffmpegName], and the [label] the household knows it by. */
    enum class Device(val ffmpegName: String, val label: String) {
        D3D11VA("d3d11va", "D3D11VA"),
        DXVA2("dxva2", "DXVA2"),
        NVDEC("cuda", "NVDEC"),
        VAAPI("vaapi", "VA-API"),
    }

    /**
     * The devices to try on the system [osName] (Java's `os.name`), in order; none with [softwareFirst] (the setting
     * that puts software decoding first, as on Android) or on a system without one of them.
     */
    fun candidates(osName: String, softwareFirst: Boolean): List<Device> = when {
        softwareFirst -> emptyList()
        osName.startsWith("Windows") -> listOf(Device.D3D11VA, Device.DXVA2)
        osName.startsWith("Linux") -> listOf(Device.NVDEC, Device.VAAPI)
        else -> emptyList()
    }

    /** [candidates] for this machine. */
    fun candidates(softwareFirst: Boolean): List<Device> =
        candidates(System.getProperty("os.name").orEmpty(), softwareFirst)

    /**
     * The first of [candidates] the stream's codec can decode on ([supports]) and whose device opens ([open], null
     * where it does not), with what [open] gave; null where none does — software, then. A device is opened only after
     * its codec support is known, so a codec no chip decodes never wakes one.
     */
    fun <T : Any> pick(candidates: List<Device>, supports: (Device) -> Boolean, open: (Device) -> T?): Pair<Device, T>? {
        for (device in candidates) {
            if (!supports(device)) continue
            val opened = open(device) ?: continue
            return device to opened
        }
        return null
    }
}
