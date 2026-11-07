package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.hawkynt.mediadroid.engine.PlaybackReport
import com.hawkynt.mediadroid.engine.Track
import com.hawkynt.mediadroid.library.CodecProfile
import com.hawkynt.mediadroid.library.Languages
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PlaybackChain
import com.hawkynt.mediadroid.player.TrackText
import java.io.File

/**
 * What is playing: the file, its container and size, every video / audio / subtitle track with codec,
 * language, channels, resolution and frame rate (the one playing marked), and how it plays — as a graph
 * (see [ChainGraph]): the files read, and a lane each for picture, sound and subtitles from them to the
 * screen and the speaker — and, under Advanced, the switch for the statistics over the picture (see
 * [StatisticsOverlay]). The file's tags and its tracks come from the engine ([report], [tracks]).
 */
@Composable
fun PlaybackInfoDialog(
    playing: Playable,
    report: PlaybackReport?,
    tracks: List<Track>,
    videoPath: String,
    graph: PlaybackChain.Graph,
    statisticsShown: Boolean,
    onStatistics: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    histogramShown: Boolean = false,
    onHistogram: (Boolean) -> Unit = {},
) {
    val file = File(videoPath)
    // What the engine read from the file, as it plays it.
    val fileTags = report?.tags ?: PlaybackReport.FileTags()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("What is playing") },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Section("About")
                Line(playing.title, bold = true)
                playing.subtitle?.let { Line(it) }
                playing.facts.forEach { Line(it) }
                Line(playing.plot ?: "Nothing is written about it yet.")
                // What the file's own tags say, as the engine read them.
                val tags = listOfNotNull(
                    fileTags.title?.let { "Title: $it" },
                    fileTags.displayTitle?.let { "Shown as: $it" },
                    fileTags.subtitle?.let { "Subtitle: $it" },
                    fileTags.artist?.let { "Artist: $it" },
                    fileTags.album?.let { "Album: $it" },
                    fileTags.genre?.let { "Genre: $it" },
                    fileTags.year?.let { "Year: $it" },
                    fileTags.writer?.let { "Writer: $it" },
                    fileTags.description?.let { "Description: $it" },
                )
                if (tags.isNotEmpty()) {
                    Section("Tags in the file")
                    tags.forEach { Line(it) }
                }

                Section("File")
                Line(file.name)
                Line(file.parent ?: "")
                Line("${file.extension.uppercase()} · ${size(file.length())}")
                report?.video?.takeIf { it.width > 0 }?.let { Line("Picture ${it.width} × ${it.height}") }

                listOf(
                    Track.Kind.VIDEO to "Video",
                    Track.Kind.AUDIO to "Audio",
                    Track.Kind.TEXT to "Subtitles",
                ).forEach { (kind, label) ->
                    val ofKind = tracks.filter { it.kind == kind }
                    if (ofKind.isNotEmpty()) {
                        Section(label)
                        ofKind.forEach { track ->
                            Line((if (track.selected) "▶ " else "   ") + describe(track), bold = track.selected)
                        }
                    }
                }

                Section("How it plays")
                ChainGraph(graph)

                Section("Advanced")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Statistics over the picture", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(checked = statisticsShown, onCheckedChange = onStatistics)
                }
                Text(
                    "Frame times, jitter and judder, the processor's work and Motion's search, over the last seconds.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Histogram over the picture", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(checked = histogramShown, onCheckedChange = onHistogram)
                }
                Text(
                    "How often each brightness and colour occurs on screen, to set brightness and contrast in the " +
                        "gear without clipping: the darkest peak should sit just right of the left end, the " +
                        "brightest just left of the right end.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/** One track as the file holds it (TrackText): the codec and its profile, never the decoder that plays it. */
fun describe(track: Track): String {
    val format = track.format
    return TrackText.describe(
        when (track.kind) {
            Track.Kind.VIDEO -> TrackText.Kind.VIDEO
            Track.Kind.AUDIO -> TrackText.Kind.AUDIO
            Track.Kind.TEXT -> TrackText.Kind.TEXT
        },
        format?.mimeType,
        format?.codecs,
        format?.width ?: 0,
        format?.height ?: 0,
        format?.frameRate ?: 0f,
        format?.channels ?: 0,
        track.language,
        track.label,
    )
}

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "$bytes bytes"
}

/**
 * What the engine reports ([report], null while it cannot tell yet: then only the file at [location]) and what the
 * screen and the household add, gathered into [PlaybackChain.Facts]: the decoders by their names, the picture's
 * adjustment, fit and zoom, the screen it shows on ([screen], its size in pixels and refresh rate, 0 unknown), the
 * sound's loudness, night mode, speed and delay, the output device, the subtitles' delay.
 */
fun playbackFacts(
    location: String,
    report: PlaybackReport?,
    videoDecoder: String?,
    audioDecoder: String?,
    picture: PictureAdjustment,
    fit: String,
    zoom: Float,
    screen: String,
    screenWidth: Int,
    screenHeight: Int,
    refreshHz: Float,
    loudnessDb: Float,
    nightMode: Boolean,
    evenLoudness: Boolean,
    replayGainDb: Float?,
    speed: Float,
    delayMs: Int,
    spatial: Boolean,
    device: String,
    subtitleDelayMs: Int,
    speedFactor: Float = 1f,
): PlaybackChain.Facts = PlaybackChain.Facts(
    inputs = report?.inputs?.map { PlaybackChain.Source(it.path, it.demuxer) }
        ?: listOf(PlaybackChain.Source(location, null)),
    video = report?.video?.let {
        PlaybackChain.Video(
            input = it.input,
            decoder = videoDecoder,
            width = it.width,
            height = it.height,
            pixelRatio = it.pixelAspect,
            picture = picture,
            fit = fit,
            zoom = zoom,
            screen = screen,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            refreshHz = refreshHz,
            decodedFormat = it.decodedFormat,
            chroma = chromaOf(CodecProfile.of(it.codecs)),
            bitDepth = it.bitDepth,
            colorSpace = it.colorSpace,
            transfer = it.transfer,
            frameRate = it.frameRate,
            speedFactor = speedFactor,
            range = it.range,
        )
    },
    audio = report?.audio?.let {
        PlaybackChain.Audio(
            input = it.input,
            decoder = audioDecoder,
            inputSampleRate = it.sampleRate,
            inputChannels = it.channels,
            sinkEncoding = it.sinkEncoding,
            sinkSampleRate = it.sinkSampleRate,
            sinkChannels = it.sinkChannels,
            passthrough = it.passthrough,
            offload = it.offload,
            loudnessDb = loudnessDb,
            nightMode = nightMode,
            evenLoudness = evenLoudness,
            replayGainDb = replayGainDb,
            speed = speed,
            delayMs = delayMs,
            spatial = spatial,
            device = device,
            decodedFormat = it.decodedFormat,
            pcmFormat = it.pcmFormat,
        )
    },
    subtitles = report?.subtitles?.let {
        PlaybackChain.Subtitles(
            input = it.input,
            mimeType = it.mimeType,
            language = it.language?.let(Languages::label),
            delayMs = subtitleDelayMs,
            screen = screen,
            libass = it.libass,
        )
    },
    player = report?.player.orEmpty(),
)

/** The chroma subsampling a profile implies: 4:2:2 or 4:4:4 where it says so, else 4:2:0. */
private fun chromaOf(profile: String?): String = when {
    profile == null -> "4:2:0"
    "4:4:4" in profile -> "4:4:4"
    "4:2:2" in profile -> "4:2:2"
    else -> "4:2:0"
}
