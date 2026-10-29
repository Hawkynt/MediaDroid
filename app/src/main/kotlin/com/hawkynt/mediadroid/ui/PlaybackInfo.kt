package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import com.hawkynt.mediadroid.library.Languages
import com.hawkynt.mediadroid.library.Quality
import com.hawkynt.mediadroid.player.PlaybackChain
import java.io.File

/**
 * What is playing: the file, its container and size, every video / audio / subtitle track with codec,
 * language, channels, resolution and frame rate (the one playing marked), and how it plays — as a graph
 * (see [ChainGraph]): the files read, and a lane each for picture, sound and subtitles from them to the
 * screen and the speaker.
 */
@Composable
fun PlaybackInfoDialog(
    videoPath: String,
    tracks: Tracks,
    videoSize: VideoSize,
    graph: PlaybackChain.Graph,
    onDismiss: () -> Unit,
) {
    val file = File(videoPath)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("What is playing") },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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

                Section("How it plays")
                ChainGraph(graph)
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
    // Subtitles Media3 parses while extracting are cues by then; codecs keeps their format.
    val subtitleMime = if (format.sampleMimeType == androidx.media3.common.MimeTypes.APPLICATION_MEDIA3_CUES) {
        format.codecs
    } else {
        format.sampleMimeType?.takeIf(androidx.media3.common.MimeTypes::isText)
    }
    if (subtitleMime != null) {
        val language = format.language?.let(Languages::label)
        return listOfNotNull(
            PlaybackChain.subtitleFormat(subtitleMime),
            language,
            format.label?.takeIf { it != language },
        ).joinToString(" · ")
    }
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

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "$bytes bytes"
}

/** What the player and the platform report, gathered into [PlaybackChain.Facts]. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun playbackFacts(
    location: String,
    player: androidx.media3.exoplayer.ExoPlayer,
    videoDecoder: String?,
    audioDecoder: String?,
    audioTrack: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig?,
    picture: com.hawkynt.mediadroid.player.PictureAdjustment,
    fit: String,
    zoom: Float,
    screen: android.view.Display?,
    screenName: String,
    loudnessDb: Float,
    nightMode: Boolean,
    speed: Float,
    delayMs: Int,
    spatial: Boolean,
    device: String,
    subtitleDelayMs: Int,
    sidecars: List<Pair<com.hawkynt.mediadroid.library.Sidecars.Sidecar, String>> = emptyList(),
): PlaybackChain.Facts {
    val subtitleFiles = sidecars.filter { it.first.kind == com.hawkynt.mediadroid.library.Sidecars.Kind.SUBTITLES }
    val audioFiles = sidecars.filter { it.first.kind == com.hawkynt.mediadroid.library.Sidecars.Kind.AUDIO }
    val inputOf = { groupId: String? -> inputOf(groupId, subtitleFiles.size, audioFiles.size) }
    val videoFormat = player.videoFormat
    val audioFormat = player.audioFormat
    val size = player.videoSize
    val mode = screen?.mode
    val encoding = audioTrack?.encoding
    val passthrough = encoding != null && !androidx.media3.common.util.Util.isEncodingLinearPcm(encoding)
    val playing = { type: Int ->
        player.currentTracks.groups.firstOrNull { it.type == type && it.isSelected }
    }
    val textGroup = playing(C.TRACK_TYPE_TEXT)
    val textFormat = textGroup?.let { group ->
        (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat)
    }
    return PlaybackChain.Facts(
        // The file itself, then its subtitle files and its audio files, in the order they are merged in.
        inputs = (listOf(location) + (subtitleFiles + audioFiles).map { it.second }).mapIndexed { i, file ->
            PlaybackChain.Source(
                file,
                com.hawkynt.mediadroid.player.DemuxerLog.of(com.hawkynt.mediadroid.player.playableUri(file).toString())
                    ?: if (i in 1..subtitleFiles.size) "SubtitleExtractor" else null,
            )
        },
        video = videoFormat?.let {
            PlaybackChain.Video(
                input = inputOf(playing(C.TRACK_TYPE_VIDEO)?.mediaTrackGroup?.id),
                decoder = videoDecoder,
                width = size.width.takeIf { w -> w > 0 } ?: it.width,
                height = size.height.takeIf { h -> h > 0 } ?: it.height,
                pixelRatio = size.pixelWidthHeightRatio,
                picture = picture,
                fit = fit,
                zoom = zoom,
                screen = screenName,
                screenWidth = mode?.physicalWidth ?: 0,
                screenHeight = mode?.physicalHeight ?: 0,
                refreshHz = mode?.refreshRate ?: 0f,
            )
        },
        audio = audioFormat?.let {
            PlaybackChain.Audio(
                input = inputOf(playing(C.TRACK_TYPE_AUDIO)?.mediaTrackGroup?.id),
                decoder = audioDecoder,
                inputSampleRate = it.sampleRate,
                inputChannels = it.channelCount,
                sinkEncoding = encoding?.let(::encodingName),
                sinkSampleRate = audioTrack?.sampleRate ?: 0,
                sinkChannels = audioTrack?.let { t -> Integer.bitCount(t.channelConfig) } ?: 0,
                passthrough = passthrough,
                offload = audioTrack?.offload == true,
                loudnessDb = loudnessDb,
                nightMode = nightMode,
                speed = speed,
                delayMs = delayMs,
                spatial = spatial,
                device = device,
            )
        },
        subtitles = textFormat?.let {
            PlaybackChain.Subtitles(
                input = inputOf(textGroup.mediaTrackGroup.id),
                // Subtitles Media3 parses while extracting reach the renderer as cues; codecs keeps their format.
                mimeType = if (it.sampleMimeType == androidx.media3.common.MimeTypes.APPLICATION_MEDIA3_CUES) {
                    it.codecs
                } else {
                    it.sampleMimeType
                },
                language = it.language?.let(Languages::label),
                delayMs = subtitleDelayMs,
                screen = screenName,
            )
        },
        player = "Media3 ExoPlayer ${androidx.media3.common.MediaLibraryInfo.VERSION}",
    )
}

/**
 * The input a track group comes from, by the prefixes Media3 gives merged sources' group ids: with
 * [subtitles] subtitle files the video is `0:` and subtitle file k `k:`; with [audio] audio files merged
 * around that, the video is `0:0:`, subtitle file k `0:k:` and audio file j `j:`.
 */
private fun inputOf(groupId: String?, subtitles: Int, audio: Int): Int {
    val prefixes = groupId?.split(':')?.dropLast(1)?.map { it.toIntOrNull() ?: return 0 } ?: return 0
    return when {
        audio > 0 -> {
            val outer = prefixes.getOrNull(0) ?: return 0
            if (outer > 0) subtitles + outer else if (subtitles > 0) prefixes.getOrNull(1) ?: 0 else 0
        }
        subtitles > 0 -> prefixes.getOrNull(0) ?: 0
        else -> 0
    }
}

/** A `C.ENCODING_*` as people call it. */
private fun encodingName(encoding: Int): String = when (encoding) {
    C.ENCODING_PCM_8BIT -> "8-bit PCM"
    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> "16-bit PCM"
    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> "24-bit PCM"
    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> "32-bit PCM"
    C.ENCODING_PCM_FLOAT -> "float PCM"
    C.ENCODING_AC3 -> "Dolby Digital"
    C.ENCODING_E_AC3 -> "Dolby Digital Plus"
    C.ENCODING_E_AC3_JOC -> "Dolby Digital Plus with Atmos"
    C.ENCODING_AC4 -> "Dolby AC-4"
    C.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
    C.ENCODING_DTS -> "DTS"
    C.ENCODING_DTS_HD -> "DTS-HD"
    C.ENCODING_MP3 -> "MP3"
    C.ENCODING_AAC_LC -> "AAC"
    C.ENCODING_OPUS -> "Opus"
    else -> "encoding $encoding"
}
