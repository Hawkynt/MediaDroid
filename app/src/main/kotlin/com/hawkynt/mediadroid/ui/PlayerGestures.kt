package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import com.hawkynt.mediadroid.player.PlaybackMath
import kotlin.math.abs

/** How far in from the left and right edge the back gesture reaches. */
private val SIDE_BAND = 28.dp

/** How far in from the top and bottom edge the status bar and navigation are reached. */
private val END_BAND = 72.dp

/** Which half of the picture a gesture started on. */
enum class Side { LEFT, RIGHT }

/**
 * The touch language of the streaming apps' players, on the bare picture:
 * - tap: controls on or off,
 * - double tap: [onDoubleTap] with the half tapped (left back, right ahead),
 * - one-finger vertical swipe: [onSwipe] with the half and how far up, as a fraction of the height; not from
 *   the screen's edges and corners, which stay with Android's own gestures,
 * - two fingers: [onPinch] with the zoom factor and the pan of this movement.
 */
fun Modifier.playerGestures(
    onTap: () -> Unit,
    onDoubleTap: (Side) -> Unit,
    onSwipe: (Side, Float) -> Unit,
    onSwipeEnd: () -> Unit,
    onPinch: (zoom: Float, pan: Offset) -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { onDoubleTap(if (it.x < size.width / 2) Side.LEFT else Side.RIGHT) },
        )
    }
    .pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val side = if (down.position.x < size.width / 2) Side.LEFT else Side.RIGHT
            val mayLevel = PlaybackMath.startsLevelSwipe(
                down.position.x,
                down.position.y,
                size.width.toFloat(),
                size.height.toFloat(),
                SIDE_BAND.toPx(),
                END_BAND.toPx(),
            )
            var swiping = false
            var travelled = Offset.Zero
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    // Two fingers: zoom and pan the picture.
                    onPinch(event.calculateZoom(), event.calculatePan())
                    event.changes.forEach { it.consume() }
                    continue
                }
                val change = pressed.first()
                val delta = change.positionChange()
                if (!swiping && mayLevel) {
                    travelled += delta
                    // Only a mainly vertical movement past the slop is a swipe; a tap stays a tap.
                    if (abs(travelled.y) > slop && abs(travelled.y) > abs(travelled.x) * 1.5f) swiping = true
                }
                if (swiping) {
                    onSwipe(side, -delta.y / size.height)
                    change.consume()
                }
            }
            if (swiping) onSwipeEnd()
        }
    }
