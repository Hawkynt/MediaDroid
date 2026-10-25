package com.hawkynt.mediadroid.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hawkynt.mediadroid.R
import com.hawkynt.mediadroid.data.StateStore
import java.io.File
import kotlinx.coroutines.delay

/** Playback speeds the player offers, slowest to fastest. */
val Speeds = listOf(0.2f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 5.0f)

/**
 * Full-screen playback: it remembers where you stopped, a show keeps going, and
 * the household controls live right on the screen — speed from 0.2x to 5x,
 * subtitles on/off, and a picker for audio and subtitle tracks when the file
 * carries several. Picture and sound follow the guarded settings.
 */
@Composable
fun PlayerScreen(
    queue: List<Playable>,
    startIndex: Int,
    store: StateStore,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val settings = store.data.settings
    // PREFER lets the bundled FFmpeg decoder win over the device's own audio
    // decoders, so AC3/DTS/TrueHD tracks play on hardware that lacks them.
    val player = remember {
        ExoPlayer.Builder(context)
            .setRenderersFactory(
                DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER),
            )
            .build()
    }
    var index by remember { mutableStateOf(startIndex) }
    var finished by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableStateOf(settings.defaultSpeed) }
    var subtitlesOn by remember { mutableStateOf(settings.subtitlesOn) }
    var audioPicker by remember { mutableStateOf(false) }
    var subtitlePicker by remember { mutableStateOf(false) }
    // Once the household picks a track by hand, the settings language steps back.
    var manualTrackPick by remember { mutableStateOf(false) }

    // Preferred languages steer automatic selection for each new item. The
    // global preferred language covers audio; the subtitle language is applied
    // as an override once the item's tracks are known (see the listener).
    LaunchedEffect(index) {
        val playable = queue[index]
        manualTrackPick = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(settings.audioLanguage.ifEmpty { null })
            .build()
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(playable.videoPath))))
        store.resumeFor(playable.videoPath)?.let { resume ->
            if (resume.positionMs > 0) player.seekTo(resume.positionMs)
        }
        player.prepare()
        player.playWhenReady = true
    }

    LaunchedEffect(speed, subtitlesOn) {
        player.setPlaybackSpeed(speed)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesOn)
            .build()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    store.markWatched(queue[index].videoPath)
                    if (index + 1 < queue.size) index++ else finished = true
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playError = error.errorCodeName
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val wanted = settings.subtitleLanguage
                if (wanted.isEmpty() || manualTrackPick) return
                val group = tracks.groups.firstOrNull { g ->
                    g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.getTrackFormat(it).language == wanted }
                } ?: return
                val track = (0 until group.length).first { group.getTrackFormat(it).language == wanted }
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
                    .build()
            }
        }
        player.addListener(listener)
        onDispose {
            val position = player.currentPosition
            val duration = player.duration
            if (duration > 0 && position > 0) store.saveResume(queue[index].videoPath, position, duration)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(3_000)
            if (player.isPlaying) {
                store.saveResume(queue[index].videoPath, player.currentPosition, player.duration)
            }
        }
    }

    BackHandler(onBack = onExit)

    val current = queue[index]
    // Picture settings: brightness is a scrim over the picture, the rest a
    // color matrix on the layer (gamma approximated via the matrix).
    val picture = Modifier.graphicsLayer {
        // One composed matrix: saturation around gray, then contrast around mid
        // gray, with gamma approximated as an extra lift (a matrix cannot do a
        // true power curve). Row sums of the saturation part are 1, so the
        // contrast offset passes through untouched.
        val c = settings.contrast
        val s = settings.saturation
        val o = (c - 1f) * -127.5f + (settings.gamma - 1f) * -40f
        colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    (0.213f + 0.787f * s) * c, (0.715f - 0.715f * s) * c, (0.072f - 0.072f * s) * c, 0f, o,
                    (0.213f - 0.213f * s) * c, (0.715f + 0.285f * s) * c, (0.072f - 0.072f * s) * c, 0f, o,
                    (0.213f - 0.213f * s) * c, (0.715f - 0.715f * s) * c, (0.072f + 0.928f * s) * c, 0f, o,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { androidContext ->
                // Inflated, not constructed: the texture surface (see the layout)
                // is only reachable through the XML attribute.
                (
                    android.view.LayoutInflater.from(androidContext)
                        .inflate(R.layout.player_view, null, false) as PlayerView
                    ).apply {
                    this.player = player
                    useController = true
                }
            },
            modifier = picture.fillMaxSize(),
        )
        if (settings.brightness < 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = -settings.brightness)))
        } else if (settings.brightness > 0f) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = settings.brightness * 0.5f)))
        }
        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                buildString {
                    append(current.title)
                    current.subtitle?.let { append("  ·  $it") }
                },
                color = Color.White,
                fontSize = 18.sp,
            )
        }
        // Household controls, big enough for a thumb.
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Speeds.forEach { s ->
                FilterChip(
                    selected = speed == s,
                    onClick = { speed = s },
                    label = { Text(if (s == 1.0f) "1×" else "${s}×", fontSize = 16.sp) },
                )
            }
            FilterChip(
                selected = subtitlesOn,
                onClick = { subtitlesOn = !subtitlesOn },
                label = { Text("Subtitles", fontSize = 16.sp) },
            )
            FilterChip(selected = false, onClick = { audioPicker = true }, label = { Text("Audio…", fontSize = 16.sp) })
            FilterChip(selected = false, onClick = { subtitlePicker = true }, label = { Text("Subtitle track…", fontSize = 16.sp) })
        }
        if (audioPicker) {
            TrackDialog(
                player = player,
                trackType = C.TRACK_TYPE_AUDIO,
                onDismiss = { audioPicker = false },
                onPick = { picked ->
                    manualTrackPick = true
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .apply { picked?.let { setOverrideForType(it) } }
                        .build()
                    audioPicker = false
                },
            )
        }
        if (subtitlePicker) {
            TrackDialog(
                player = player,
                trackType = C.TRACK_TYPE_TEXT,
                onDismiss = { subtitlePicker = false },
                onPick = { picked ->
                    manualTrackPick = true
                    subtitlesOn = picked != null || subtitlesOn
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .apply { picked?.let { setOverrideForType(it) } }
                        .build()
                    subtitlePicker = false
                },
            )
        }
        if (finished) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = onExit) {
                    Text("All done — back to the library", fontSize = 22.sp)
                }
            }
        }
        playError?.let { code ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = onExit) {
                    Text("Cannot play this file ($code) — back to the library", fontSize = 22.sp)
                }
            }
        }
    }
}

/** Lists the audio or subtitle tracks of the current item; "Automatic" clears the pick. */
@Composable
private fun TrackDialog(
    player: Player,
    trackType: @C.TrackType Int,
    onDismiss: () -> Unit,
    onPick: (TrackSelectionOverride?) -> Unit,
) {
    val groups = player.currentTracks.groups.filter { it.type == trackType }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", fontSize = 18.sp) } },
        text = {
            Column {
                TextButton(onClick = { onPick(null) }) { Text("Automatic", fontSize = 20.sp) }
                groups.forEach { group ->
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val label = format.label ?: format.language ?: "Track ${i + 1}"
                        TextButton(onClick = { onPick(TrackSelectionOverride(group.mediaTrackGroup, i)) }) {
                            Text(
                                buildString {
                                    append(label)
                                    format.language?.let { append("  ($it)") }
                                },
                                fontSize = 20.sp,
                            )
                        }
                    }
                }
                if (groups.isEmpty()) Text("This file has only one such track.", fontSize = 18.sp)
            }
        },
    )
}
