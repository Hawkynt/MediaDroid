package com.hawkynt.mediadroid.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Lip-sync: the picture follows the audio clock, so reporting the audio position [delayMs] later moves
 * the sound against the picture — positive delays make the sound come later, negative ones earlier.
 */
@OptIn(UnstableApi::class)
class DelayedAudioSink(sink: AudioSink, private val delayMs: () -> Int) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (position == AudioSink.CURRENT_POSITION_NOT_SET) return position
        return position + delayMs() * 1000L
    }
}

/** Which audio output plays right now, as a key to remember a delay by and a name to show. */
data class AudioOutput(val key: String, val name: String)

/**
 * The output media audio goes to: on Android 13+ as the system routes it; before that the most likely
 * one of the connected outputs (a Bluetooth or USB headset beats a wired one beats HDMI beats the speaker).
 */
fun currentAudioOutput(context: Context): AudioOutput {
    val audio = context.getSystemService(AudioManager::class.java)
    val device = if (Build.VERSION.SDK_INT >= 33) {
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        audio.getAudioDevicesForAttributes(attributes).firstOrNull()
    } else {
        null
    } ?: audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).maxByOrNull { rank(it.type) }
    if (device == null) return AudioOutput("speaker", "This device")
    val kind = when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi"
        else -> "speaker"
    }
    val product = device.productName?.toString()?.trim().orEmpty()
    val name = when (kind) {
        "speaker" -> "This device"
        "wired" -> "Headphones"
        "hdmi" -> product.ifEmpty { "HDMI" }
        else -> product.ifEmpty { kind.replaceFirstChar { it.uppercase() } }
    }
    return AudioOutput(if (kind == "speaker" || kind == "wired") kind else "$kind:$product", name)
}

private fun rank(type: Int): Int = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 5
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 4
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> 3
    AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> 2
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1
    else -> 0
}
