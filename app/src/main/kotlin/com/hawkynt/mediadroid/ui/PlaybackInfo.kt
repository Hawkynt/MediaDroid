package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import com.hawkynt.mediadroid.library.Languages
import com.hawkynt.mediadroid.library.Quality
import java.io.File

/**
 * What is playing: the file, its container and size, every video / audio / subtitle track with codec,
 * language, channels, resolution and frame rate (the one playing marked), and the decoders at work.
 */
@Composable
fun PlaybackInfoDialog(
    videoPath: String,
    tracks: Tracks,
    videoSize: VideoSize,
    videoDecoder: String?,
    audioDecoder: String?,
    onDismiss: () -> Unit,
) {
    val file = File(videoPath)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("What is playing") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Section("File")
                Line(file.name)
                Line(file.parent ?: "")
                Line("${file.extension.uppercase()} · ${size(file.length())}")
                if (videoSize.width > 0) Line("Picture ${videoSize.width} × ${videoSize.height}")

                listOf(
                    C.TRACK_TYPE_VIDEO to "Video",
                    C.TRACK_TYPE_AUDIO to "Audio",
                    C.TRACK_TYPE_TEXT to "Subtitles",
                ).forEach { (type, label) ->
                    val groups = tracks.groups.filter { it.type == type }
                    if (groups.isNotEmpty()) {
                        Section(label)
                        groups.forEach { group ->
                            for (i in 0 until group.length) {
                                val playing = group.isTrackSelected(i)
                                Line((if (playing) "▶ " else "   ") + describe(group.getTrackFormat(i)), bold = playing)
                            }
                        }
                    }
                }

                Section("Decoders")
                Line("Video: ${decoder(videoDecoder)}")
                Line("Audio: ${decoder(audioDecoder)}")
            }
        },
    )
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun Line(text: String, bold: Boolean = false) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * One track as the file holds it: `HEVC · Main 10@L5.1 · 3840 × 1606 · 23.976 fps` or
 * `Dolby Digital · 5.1 · German` — the codec and its profile, never the decoder that plays it.
 */
internal fun describe(format: Format): String {
    val codec = Quality.codecOf(format.sampleMimeType, format.codecs)
    val parts = mutableListOf<String>()
    val shown = Quality.of(
        com.hawkynt.mediadroid.nfo.StreamDetails(
            format.width.takeIf { it > 0 },
            format.height.takeIf { it > 0 },
            codec,
            null,
            if (androidx.media3.common.MimeTypes.isAudio(format.sampleMimeType)) {
                listOf(com.hawkynt.mediadroid.nfo.AudioStream(codec, format.channelCount.takeIf { it > 0 }, null))
            } else {
                emptyList()
            },
        ),
    )
    (shown.video ?: shown.audio ?: codec ?: format.sampleMimeType)?.let { parts += it }
    com.hawkynt.mediadroid.library.CodecProfile.of(format.codecs)?.let { parts += it }
    if (format.width > 0) parts += "${format.width} × ${format.height}"
    if (format.frameRate > 0) {
        parts += String.format(java.util.Locale.ROOT, "%.3f", format.frameRate).trimEnd('0').trimEnd('.') + " fps"
    }
    shown.channels?.takeIf { format.channelCount > 0 }?.let { parts += it }
    format.language?.let { parts += Languages.label(it) }
    format.label?.let { parts += it }
    return parts.joinToString(" · ")
}

/** A decoder name with what kind it is: Android's own (hardware or not) or the bundled FFmpeg. */
internal fun decoder(name: String?): String = when {
    name == null -> "not in use"
    name.startsWith("ffmpeg", ignoreCase = true) || name.contains("Ffmpeg") -> "$name (software, FFmpeg)"
    name.startsWith("c2.android.") || name.startsWith("OMX.google.") -> "$name (software)"
    name.contains("sw", ignoreCase = true) && name.startsWith("OMX.") -> "$name (software)"
    else -> "$name (hardware)"
}

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "$bytes bytes"
}
