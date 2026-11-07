package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureFit

/**
 * The gear's side panel: speed, this video's picture, subtitles and tracks. Picture changes show
 * live and are remembered for this one video when a slider is let go.
 */
@Composable
fun GearPanel(
    speed: Float,
    onSpeed: (Float) -> Unit,
    fit: PictureFit,
    onFit: (PictureFit) -> Unit,
    subtitlesOn: Boolean,
    onSubtitles: (Boolean) -> Unit,
    picture: PictureAdjustment,
    onPicture: (PictureAdjustment) -> Unit,
    onPictureDone: () -> Unit,
    onResetPicture: () -> Unit,
    /** Tries a choice of deinterlacing, upscaling or smooth motion before it is taken (see pictureCheck). */
    check: PictureTry = { _, _ -> PictureVerdict(true) },
    /** The colourisers offered for this video (see rememberColorizers). */
    colorizers: List<PictureAdjustment.Colorize> = listOf(PictureAdjustment.Colorize.OFF),
    onAudioTrack: () -> Unit,
    onSubtitleTrack: () -> Unit,
    /** Picks a second subtitle track, shown at the top beside the first. */
    onSecondSubtitleTrack: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    sections: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
    afterPictureSize: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
    /** The folder this video is in, and the one whose picture it starts with, if any has one (FolderDefaults). */
    folder: String = "",
    pictureFolder: String? = null,
    onUseForFolder: () -> Unit = {},
    onForgetFolder: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(max = 400.dp)
            .fillMaxWidth(0.9f)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .clickable(interactionSource = null, indication = null) {}
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Playback & picture", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }

        PanelTitle("Speed")
        SpeedSlider(speed, onSpeed, Modifier.fillMaxWidth())

        sections()

        PanelTitle("Picture size")
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PictureFit.entries.forEach { option ->
                FilterChip(
                    selected = fit == option,
                    onClick = { onFit(option) },
                    label = { Text(option.label, fontSize = 16.sp) },
                )
            }
        }
        Text(
            (if (fit == PictureFit.PANORAMIC) "A narrow picture fills a wide screen: the middle as it is, the sides " +
                "stretched. " else "") + "Pinch with two fingers to zoom; choosing a size resets the zoom.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        // The frame's shape, for a file whose flag is wrong (PictureShape); remembered with this video's picture.
        Text("Shape", fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.hawkynt.mediadroid.player.AspectOverride.entries.forEach { option ->
                FilterChip(
                    selected = picture.aspect == option,
                    onClick = {
                        onPicture(picture.copy(aspect = option))
                        onPictureDone()
                    },
                    label = { Text(option.label, fontSize = 16.sp) },
                )
            }
        }
        Text(
            "Only for a picture that looks squeezed or stretched: its file says the wrong shape.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        afterPictureSize()

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 20.dp)) {
            Text(
                "Picture for this video",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResetPicture) { Text("Reset", fontSize = 16.sp) }
        }
        PictureSliders(
            picture, onChange = onPicture, onDone = onPictureDone, stereo = true, logo = true, check = check,
            colorizers = colorizers,
        )
        // The picture for every video in the folder, as one: a folder of VHS rips starting deinterlaced.
        OutlinedButton(onClick = onUseForFolder, modifier = Modifier.padding(top = 8.dp)) {
            Text("Use for the whole folder", fontSize = 16.sp)
        }
        Text(
            if (pictureFolder != null) {
                "Videos in $pictureFolder start with that folder's picture; a video changed here keeps its own."
            } else {
                "Every video in $folder will start with this picture; each can still be changed on its own."
            },
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pictureFolder != null) {
            TextButton(onClick = onForgetFolder) { Text("Forget the folder's picture", fontSize = 16.sp) }
        }

        PanelTitle("Subtitles & sound")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Subtitles", fontSize = 17.sp, modifier = Modifier.weight(1f))
            Switch(checked = subtitlesOn, onCheckedChange = onSubtitles)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = onAudioTrack) { Text("Audio track…", fontSize = 16.sp) }
            OutlinedButton(onClick = onSubtitleTrack) { Text("Subtitle track…", fontSize = 16.sp) }
        }
        OutlinedButton(onClick = onSecondSubtitleTrack, modifier = Modifier.padding(top = 8.dp)) {
            Text("Second subtitles…", fontSize = 16.sp)
        }
    }
}

@Composable
fun PanelTitle(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}
