package com.hawkynt.mediadroyd.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString

/**
 * Windows' display modes through user32 (EnumDisplayDevices, EnumDisplaySettings, ChangeDisplaySettingsEx), as Kodi
 * switches them: each monitor attached to the desktop with its position and every mode of its adapter; a switch made
 * for this program only (CDS_FULLSCREEN), which Windows itself undoes when the program ends, however it ends.
 * Windows names a rate by whole hertz, 23 and 59 for the NTSC rates 23.976 and 59.94 (refreshOf).
 */
class WindowsDisplays : DisplaySystem {
    @Suppress("FunctionName")
    private interface User32 : Library {
        fun EnumDisplayDevicesW(device: WString?, index: Int, info: Pointer, flags: Int): Boolean
        fun EnumDisplaySettingsW(device: WString, modeNumber: Int, mode: Pointer): Boolean
        fun ChangeDisplaySettingsExW(
            device: WString,
            mode: Pointer?,
            window: Pointer?,
            flags: Int,
            parameter: Pointer?,
        ): Int
    }

    private val user32: User32? by lazy { runCatching { Native.load("user32", User32::class.java) }.getOrNull() }

    override val unavailable: String?
        get() = if (user32 == null) "Windows' display settings cannot be reached." else null

    override fun outputs(): List<DisplayOutput> {
        val api = user32 ?: return emptyList()
        val outputs = ArrayList<DisplayOutput>()
        val device = Memory(DISPLAY_DEVICE_SIZE.toLong())
        var index = 0
        while (true) {
            device.clear()
            device.setInt(0, DISPLAY_DEVICE_SIZE)
            if (!api.EnumDisplayDevicesW(null, index++, device, 0)) break
            if (device.getInt(STATE_FLAGS) and ATTACHED_TO_DESKTOP == 0) continue
            val name = WString(device.getWideString(DEVICE_NAME))
            val mode = devMode()
            if (!api.EnumDisplaySettingsW(name, ENUM_CURRENT_SETTINGS, mode)) continue
            val current = modeOf(mode)
            val x = mode.getInt(POSITION_X)
            val y = mode.getInt(POSITION_Y)
            val modes = ArrayList<DisplayMode>()
            var number = 0
            while (api.EnumDisplaySettingsW(name, number++, mode)) {
                if (mode.getInt(BITS_PER_PEL) == current.bits) modes += modeOf(mode).mode
            }
            outputs += DisplayOutput(name.toString(), x, y, current.mode, modes.distinct())
        }
        return outputs
    }

    override fun switch(output: String, mode: DisplayMode): Boolean {
        val api = user32 ?: return false
        val wanted = devMode()
        wanted.setInt(FIELDS, DM_PELSWIDTH or DM_PELSHEIGHT or DM_DISPLAYFREQUENCY)
        wanted.setInt(PELS_WIDTH, mode.width)
        wanted.setInt(PELS_HEIGHT, mode.height)
        wanted.setInt(DISPLAY_FREQUENCY, mode.rate.toInt())
        val answer = api.ChangeDisplaySettingsExW(WString(output), wanted, null, CDS_FULLSCREEN, null)
        return answer == DISP_CHANGE_SUCCESSFUL
    }

    private class Shown(val mode: DisplayMode, val bits: Int)

    private fun devMode() = Memory(DEVMODE_SIZE.toLong()).apply {
        clear()
        setShort(DM_SIZE, DEVMODE_SIZE.toShort())
    }

    private fun modeOf(mode: Pointer): Shown {
        val hz = mode.getInt(DISPLAY_FREQUENCY)
        return Shown(
            DisplayMode(mode.getInt(PELS_WIDTH), mode.getInt(PELS_HEIGHT), refreshOf(hz), hz.toString()),
            mode.getInt(BITS_PER_PEL),
        )
    }

    companion object {
        /**
         * The refresh rate Windows' whole [hertz] stand for: one under a multiple of 24 or 30 (23, 29, 47, 59, 71, 119,
         * 143, 239) is the NTSC rate just below it, the multiple times 1000/1001; the rest as they are.
         */
        fun refreshOf(hertz: Int): Float {
            val ntsc = hertz > 1 && ((hertz + 1) % 24 == 0 || (hertz + 1) % 30 == 0)
            return if (ntsc) (hertz + 1) * 1000f / 1001f else hertz.toFloat()
        }

        // DEVMODEW (wingdi.h): 220 bytes; the display fields' offsets.
        private const val DEVMODE_SIZE = 220
        private const val DM_SIZE = 68L
        private const val FIELDS = 72L
        private const val POSITION_X = 76L
        private const val POSITION_Y = 80L
        private const val BITS_PER_PEL = 168L
        private const val PELS_WIDTH = 172L
        private const val PELS_HEIGHT = 176L
        private const val DISPLAY_FREQUENCY = 184L

        // DISPLAY_DEVICEW: cb, DeviceName[32], DeviceString[128], StateFlags, DeviceID[128], DeviceKey[128].
        private const val DISPLAY_DEVICE_SIZE = 840
        private const val DEVICE_NAME = 4L
        private const val STATE_FLAGS = 324L
        private const val ATTACHED_TO_DESKTOP = 0x1

        private const val ENUM_CURRENT_SETTINGS = -1
        private const val DM_PELSWIDTH = 0x80000
        private const val DM_PELSHEIGHT = 0x100000
        private const val DM_DISPLAYFREQUENCY = 0x400000
        private const val CDS_FULLSCREEN = 0x4
        private const val DISP_CHANGE_SUCCESSFUL = 0
    }
}
