package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.data.Settings
import com.hawkynt.mediadroid.data.StateStore

/** The four digits spoken as words — a child who cannot read cannot enter them. */
private val DigitWords = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")

/**
 * The parent gate. Every visit draws a fresh random four-digit PIN and shows it on screen as words,
 * big enough to read from a sofa: grown-ups read the words and tap the digits, small children see
 * four words they cannot read yet. Nothing to remember, nothing stored.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PinGate(
    onUnlocked: () -> Unit,
) {
    val pin = remember { (1000..9999).random().toString() }
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    fun submit(digits: String) {
        if (digits == pin) {
            onUnlocked()
        } else {
            wrong = true
            entered = ""
        }
    }

    fun press(d: Char) {
        wrong = false
        val next = (entered + d).take(4)
        entered = next
        if (next.length == 4) submit(next)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A phone in landscape has no room for a keypad of four rows: the keys go in one wrapping row.
        val short = maxHeight < SHORT_SCREEN
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Settings are for grown-ups — tap these four numbers",
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(if (short) 8.dp else 16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)) {
                pin.forEach { digit ->
                    Text(
                        DigitWords[digit - '0'],
                        fontSize = if (short) 32.sp else 40.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(if (short) 4.dp else 12.dp))
            Text(
                when {
                    wrong -> "That was not it — try the words again"
                    entered.isEmpty() -> "•".repeat(4)
                    else -> "•".repeat(entered.length) + "○".repeat(4 - entered.length)
                },
                fontSize = if (wrong) 18.sp else 28.sp,
                color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(if (short) 8.dp else 16.dp))
            val keyHeight = if (short) 60.dp else 72.dp
            @Composable
            fun Key(d: Char) = Button(
                onClick = { press(d) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.height(keyHeight).width(88.dp),
            ) { Text(d.toString(), fontSize = 30.sp) }

            @Composable
            fun Clear() = OutlinedButton(
                onClick = { entered = "" },
                modifier = Modifier.height(keyHeight).width(88.dp),
            ) { Text("Clear", fontSize = 18.sp) }

            @Composable
            fun Back() = Button(
                onClick = { entered = entered.dropLast(1) },
                modifier = Modifier.height(keyHeight).width(88.dp),
            ) { Text("←", fontSize = 26.sp) }

            if (short) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    "1234567890".forEach { Key(it) }
                    Clear()
                    Back()
                }
            } else {
                listOf("123", "456", "789").forEach { rowDigits ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { rowDigits.forEach { Key(it) } }
                    Spacer(Modifier.height(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Clear()
                    Key('0')
                    Back()
                }
            }
        }
    }
}

/** Below this height the gate's keypad goes in one wrapping row. */
private val SHORT_SCREEN = 560.dp

/**
 * The guarded settings: playback defaults, picture and sound, languages, and
 * the maximum age for the library. Every change is saved straight away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    store: StateStore,
    rootPath: String,
    onChangeFolder: () -> Unit,
    unreachable: Set<String> = emptySet(),
    onAddDeviceFolder: () -> Unit = {},
    onSourcesChanged: () -> Unit = {},
) {
    var unlocked by remember { mutableStateOf(false) }
    if (!unlocked) {
        PinGate(onUnlocked = { unlocked = true })
        return
    }

    val settings = store.data.settings
    var defaultPicture by remember { mutableStateOf(store.defaultPicture()) }
    fun update(block: (Settings) -> Unit) {
        block(settings)
        store.save()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        SourcesSection(
            store = store,
            unreachable = unreachable,
            onChangeMainFolder = onChangeFolder,
            onAddDeviceFolder = onAddDeviceFolder,
            onChanged = onSourcesChanged,
        )
        Spacer(Modifier.height(24.dp))

        Text("Playback", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            label = "Binge-watch shows",
            detail = "When an episode ends, the next one starts.",
            checked = settings.bingeWatching,
            onChange = { c -> update { it.bingeWatching = c } },
        )
        SwitchRow(
            label = "Skip intros and credits",
            detail = "Jumps over intros and ends an episode at its credits, " +
                "where its chapters or its .edl file mark them.",
            checked = settings.skipIntroCredits,
            onChange = { c -> update { it.skipIntroCredits = c } },
        )
        Spacer(Modifier.height(8.dp))
        Text("TV and AV receiver", fontSize = 18.sp)
        SwitchRow(
            label = "Match the refresh rate",
            detail = "The TV switches to the film's frame rate (or a multiple), so camera pans do not judder.",
            checked = settings.matchRefreshRate,
            onChange = { c -> update { it.matchRefreshRate = c } },
        )
        Text("Even frames", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Where the screen cannot switch, the film runs a little faster or slower so every frame stays " +
                "on screen equally long — 24 fps on 120 Hz, 30 or 20 fps on 60 Hz. The sound keeps its pitch.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "Off", 5 to "Slight, up to 5 %", 25 to "Strong, up to 25 %").forEach { (percent, label) ->
                FilterChip(
                    selected = settings.judderLimitPercent == percent,
                    onClick = { update { it.judderLimitPercent = percent } },
                    label = { Text(label, fontSize = 16.sp) },
                )
            }
        }
        if (settings.judderLimitPercent > 0) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.hawkynt.mediadroid.player.PlaybackMath.JudderDirection.entries.forEach { direction ->
                    FilterChip(
                        selected = settings.judderDirection == direction.name,
                        onClick = { update { it.judderDirection = direction.name } },
                        label = { Text(direction.label, fontSize = 16.sp) },
                    )
                }
            }
        }
        SwitchRow(
            label = "Audio passthrough",
            detail = "Surround sound goes untouched to an AV receiver over HDMI, where the device supports it.",
            checked = settings.audioPassthrough,
            onChange = { c -> update { it.audioPassthrough = c } },
        )
        Spacer(Modifier.height(8.dp))
        Text("Video decoding", fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = !settings.softwareVideoDecoding,
                onClick = { update { it.softwareVideoDecoding = false } },
                label = { Text("Hardware (recommended)", fontSize = 16.sp) },
            )
            FilterChip(
                selected = settings.softwareVideoDecoding,
                onClick = { update { it.softwareVideoDecoding = true } },
                label = { Text("Software", fontSize = 16.sp) },
            )
        }
        Text(
            if (settings.softwareVideoDecoding) {
                "Software decoding drains the battery fast and can stutter on large videos. It is meant for " +
                    "videos that otherwise do not play at all or only with errors."
            } else {
                "The device's own decoders play the picture; the built-in software decoder only steps in " +
                    "for videos they cannot play."
            },
            fontSize = 14.sp,
            color = if (settings.softwareVideoDecoding) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(8.dp))
        Text("Default speed", fontSize = 18.sp)
        SpeedSlider(settings.defaultSpeed, { s -> update { it.defaultSpeed = s } }, Modifier.fillMaxWidth())
        SliderRow(
            label = "Loudness",
            value = settings.loudness,
            range = -12f..12f,
            valueText = "${settings.loudness.toInt()} dB",
            onValueChange = { v -> update { it.loudness = v } },
        )

        Spacer(Modifier.height(16.dp))
        Text("Default picture", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Every video starts with this; the gear in the player changes one video on its own.",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PictureSliders(
            picture = defaultPicture,
            onChange = { defaultPicture = it },
            onDone = {
                update {
                    it.brightness = defaultPicture.brightness
                    it.gamma = defaultPicture.gamma
                    it.contrast = defaultPicture.contrast
                    it.saturation = defaultPicture.saturation
                    it.sharpness = defaultPicture.sharpness
                    it.clarity = defaultPicture.clarity
                    it.denoise = defaultPicture.denoise
                }
            },
        )

        Spacer(Modifier.height(16.dp))
        Text("Languages", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = settings.subtitlesOn, onCheckedChange = { c -> update { it.subtitlesOn = c } })
            Spacer(Modifier.width(12.dp))
            Text("Subtitles on by default", fontSize = 18.sp)
        }
        Text("Subtitle size", fontSize = 18.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.hawkynt.mediadroid.player.SubtitleLook.Size.entries.forEach { size ->
                FilterChip(
                    selected = settings.subtitleSize == size.name,
                    onClick = { update { it.subtitleSize = size.name } },
                    label = { Text(size.label, fontSize = 16.sp) },
                )
            }
        }
        SwitchRow(
            label = "Subtitles raised",
            detail = "Above burned-in text at the bottom of the picture.",
            checked = settings.subtitleRaised,
            onChange = { c -> update { it.subtitleRaised = c } },
        )
        SwitchRow(
            label = "Dark box behind subtitles",
            detail = "Easier to read on bright pictures.",
            checked = settings.subtitleBox,
            onChange = { c -> update { it.subtitleBox = c } },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.audioLanguage,
            onValueChange = { t -> update { it.audioLanguage = t.trim().lowercase() } },
            label = { Text("Preferred audio language (e.g. en, de)" ) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.subtitleLanguage,
            onValueChange = { t -> update { it.subtitleLanguage = t.trim().lowercase() } },
            label = { Text("Preferred subtitle language (e.g. en)" ) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Text("Children", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Maximum age for the library", fontSize = 18.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 6, 12, 16, 18).forEach { age ->
                FilterChip(
                    selected = settings.maxAgeYears == age,
                    onClick = { update { it.maxAgeYears = age } },
                    label = { Text(if (age == 0) "Off" else "$age+", fontSize = 16.sp) },
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SwitchRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 18.sp)
            Text(detail, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 18.sp, modifier = Modifier.width(120.dp))
        Slider(value = value, onValueChange = onValueChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(valueText, fontSize = 16.sp, modifier = Modifier.width(64.dp))
    }
}
