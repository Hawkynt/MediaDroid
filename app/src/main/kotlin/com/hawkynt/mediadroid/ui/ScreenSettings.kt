package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.state.BUILT_IN_SCREEN
import com.hawkynt.mediadroid.state.StateStore
import com.hawkynt.mediadroid.data.screenKey
import kotlin.math.roundToInt
import com.hawkynt.mediadroid.player.Keystone
import com.hawkynt.mediadroid.player.ScreenSetup
import com.hawkynt.mediadroid.player.ScreenShape

/**
 * Settings → Screens: each output screen's own setup — this device's screen, and the TV or projector attached now —
 * kept for that screen alone. Nothing here needs changing for an ordinary screen.
 */
@Composable
fun ScreenSettings(store: StateStore) {
    val external = rememberExternalDisplay()
    Text("Screens", fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text(
        "Only for projectors and special screens; an ordinary TV or phone needs nothing here. Each screen keeps " +
            "its own.",
        fontSize = 15.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ScreenSetupRows("This device's screen", BUILT_IN_SCREEN, null, store)
    // The patterns on this screen, through the stage with the default picture and this screen's setup.
    var patterns by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { patterns = true }, modifier = Modifier.padding(top = 8.dp)) {
        Text("Show test patterns", fontSize = 16.sp)
    }
    Text(
        "Black and white levels, grey steps, colour bars, a grid and gamma, to set brightness, contrast and colour " +
            "in the default picture above — and a projector's keystone — by eye.",
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (patterns) {
        TestPatternScreen(store.defaultPicture(), store.screenSetup(BUILT_IN_SCREEN)) { patterns = false }
    }
    external?.let { ScreenSetupRows(it.name, screenKey(it), it, store) }
}

/**
 * One screen's setup, [key] as [StateStore.screenSetup] keeps it, under its [name]; [display] the external display it
 * is (null: this device's own screen), where the keystone's grid is shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreenSetupRows(name: String, key: String, display: android.view.Display?, store: StateStore) {
    // Read again once the grid closes: it kept what it changed in the store itself.
    var version by remember { mutableStateOf(0) }
    val setup = remember(version) { store.screenSetup(key) }
    fun save(changed: ScreenSetup) {
        store.saveScreenSetup(key, changed)
        version++
    }
    var grid by remember { mutableStateOf(false) }
    if (grid) {
        val done: () -> Unit = {
            grid = false
            version++
        }
        val keep: (ScreenSetup) -> Unit = { store.saveScreenSetup(key, it) }
        if (display == null) {
            TestPatternScreen(store.defaultPicture(), setup, keystone = keep, onClose = done)
        } else {
            KeystoneOnProjector(display, store.defaultPicture(), setup, keep, done)
        }
    }
    Text(name, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp))
    Text("Shape as seen", fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ScreenShape.entries.forEach { shape ->
            FilterChip(
                selected = setup.shape == shape,
                onClick = { save(setup.copy(shape = shape)) },
                label = { Text(shape.label, fontSize = 16.sp) },
            )
        }
    }
    Text(
        "For a projector behind an anamorphic lens, or a screen whose pixels are not square: the shape the " +
            "picture is seen at on the wall. Pictures are drawn narrower to match, so circles stay round.",
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ProjectorRows(setup, ::save) { grid = true }
    SwitchLine(
        "OLED screen: burn-in care",
        "The picture, its black bars and the subtitles drift a few pixels every half minute, too slowly to see, " +
            "so no edge stays on the same pixels for hours. Only for OLED screens; others need nothing.",
        setup.burnInCare,
    ) { save(setup.copy(burnInCare = it)) }
}

/**
 * A projector's setup: the picture mirrored for rear projection, upside down for a ceiling mount, and its keystone
 * corrected on two axes — with a sketch of where the picture lands on the panel, since the video is not playing here.
 */
@Composable
private fun ProjectorRows(setup: ScreenSetup, save: (ScreenSetup) -> Unit, onGrid: () -> Unit) {
    Text("Projector", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
    SwitchLine("Rear projection", "The picture mirrored, for a screen lit from behind.", setup.mirror) {
        save(setup.copy(mirror = it))
    }
    SwitchLine("Ceiling mount", "The picture upside down, for a projector hung from the ceiling.", setup.flip) {
        save(setup.copy(flip = it))
    }
    var vertical by remember(setup.keystoneVertical) { mutableFloatStateOf(setup.keystoneVertical) }
    var horizontal by remember(setup.keystoneHorizontal) { mutableFloatStateOf(setup.keystoneHorizontal) }
    KeystoneSlider("Keystone, top and bottom", "top narrower", "bottom narrower", vertical, { vertical = it }) {
        save(setup.copy(keystoneVertical = vertical))
    }
    KeystoneSlider("Keystone, left and right", "right shorter", "left shorter", horizontal, { horizontal = it }) {
        save(setup.copy(keystoneHorizontal = horizontal))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        KeystoneSketch(vertical, horizontal, Modifier.width(160.dp).height(90.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                "Where the picture lands on the panel: a projector aimed up throws it wider at the top — narrow " +
                    "the top until the picture on the wall is square again.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onGrid) { Text("Correct with the grid", fontSize = 16.sp) }
            if (vertical != 0f || horizontal != 0f) {
                TextButton(
                    onClick = {
                        vertical = 0f
                        horizontal = 0f
                        save(setup.copy(keystoneVertical = 0f, keystoneHorizontal = 0f))
                    },
                ) { Text("No keystone", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun SwitchLine(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp)
            Text(detail, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** One keystone, -[Keystone.LIMIT]..[Keystone.LIMIT], saved when let go; what either side does said beside it. */
@Composable
fun KeystoneSlider(
    label: String,
    above: String,
    below: String,
    value: Float,
    onValue: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        val percent = kotlin.math.abs(value * 100).roundToInt()
        Text(
            when {
                percent == 0 -> "off"
                value > 0f -> "$above $percent %"
                else -> "$below $percent %"
            },
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Slider(
        value = value,
        // In steps of half a percent, and back to none near the middle.
        onValueChange = { onValue(if (kotlin.math.abs(it) < 0.01f) 0f else (it * 200).roundToInt() / 200f) },
        onValueChangeFinished = onDone,
        valueRange = -Keystone.LIMIT..Keystone.LIMIT,
    )
}

/** The panel, and the quadrilateral the keystone draws the picture into (Keystone.corners), as seen. */
@Composable
private fun KeystoneSketch(vertical: Float, horizontal: Float, modifier: Modifier) {
    val panel = MaterialTheme.colorScheme.outline
    val picture = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        drawRect(panel, style = Stroke(width = 2f))
        val c = Keystone.corners(vertical, horizontal)
        val path = Path().apply {
            // From the bottom left, as the stage counts; the sketch's y grows downwards.
            moveTo((c[0] * size.width).toFloat(), ((1 - c[1]) * size.height).toFloat())
            for (i in 1..3) lineTo((c[2 * i] * size.width).toFloat(), ((1 - c[2 * i + 1]) * size.height).toFloat())
            close()
        }
        drawPath(path, picture.copy(alpha = 0.5f))
        drawPath(path, picture, style = Stroke(width = 2f))
    }
}

/**
 * The keystone set with the grid on a projector attached as [display]: the grid there (PatternPresentation), drawn with
 * [setup] as it changes, and the keystone's controls on the phone, each change handed to [keep].
 */
@Composable
private fun KeystoneOnProjector(
    display: android.view.Display,
    picture: com.hawkynt.mediadroid.player.PictureAdjustment,
    setup: ScreenSetup,
    keep: (ScreenSetup) -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var live by remember { mutableStateOf(setup) }
    val presentation = remember(display) { PatternPresentation(context, display, picture, setup) }
    androidx.compose.runtime.DisposableEffect(presentation) {
        presentation.show()
        onDispose { presentation.release() }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        KeystoneControls(
            live,
            onChange = {
                live = it
                presentation.show(it)
                keep(it)
            },
            onDone = onClose,
        )
    }
}
