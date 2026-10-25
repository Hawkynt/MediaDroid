package com.hawkynt.mediadroid.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hawkynt.mediadroid.data.StateStore
import java.io.File
import kotlinx.coroutines.delay

/**
 * Full-screen playback with two promises: it remembers where you stopped, and a
 * show keeps going — when an episode ends it is marked watched and the next one
 * starts on its own, until the queue runs out.
 */
@Composable
fun PlayerScreen(
    queue: List<Playable>,
    startIndex: Int,
    store: StateStore,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    var index by remember { mutableStateOf(startIndex) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(index) {
        val playable = queue[index]
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(playable.videoPath))))
        store.resumeFor(playable.videoPath)?.let { resume ->
            if (resume.positionMs > 0) player.seekTo(resume.positionMs)
        }
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    store.markWatched(queue[index].videoPath)
                    if (index + 1 < queue.size) index++ else finished = true
                }
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
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { androidContext ->
                PlayerView(androidContext).apply {
                    this.player = player
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
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
        if (finished) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = onExit) {
                    Text("All done — back to the library", fontSize = 22.sp)
                }
            }
        }
    }
}
