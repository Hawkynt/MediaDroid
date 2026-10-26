package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
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
 * The parent gate. A random four-digit PIN is generated once and shown on
 * screen as words, big enough to read from a sofa: grown-ups read the words and
 * tap the digits, small children see four words they cannot read yet. The PIN
 * is re-shown on every visit, so nobody has to remember it.
 */
@Composable
fun PinGate(
    store: StateStore,
    onUnlocked: () -> Unit,
) {
    val fresh = store.data.settings.pin.isEmpty()
    var pin by remember {
        mutableStateOf(store.data.settings.pin.ifEmpty { (1000..9999).random().toString() })
    }
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    fun submit(digits: String) {
        if (digits == pin) {
            if (fresh) {
                store.data.settings.pin = pin
                store.save()
            }
            onUnlocked()
        } else {
            wrong = true
            entered = ""
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (fresh) "Your new PIN — tap these four numbers" else "Settings are for grown-ups",
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            pin.forEach { digit ->
                Text(
                    DigitWords[digit - '0'],
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                wrong -> "That was not it — try the words again"
                entered.isEmpty() -> "•".repeat(4)
                else -> "•".repeat(entered.length) + "○".repeat(4 - entered.length)
            },
            fontSize = if (wrong) 18.sp else 28.sp,
            color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        listOf("123", "456", "789").forEach { rowDigits ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowDigits.forEach { d ->
                    Button(
                        onClick = {
                            wrong = false
                            val next = (entered + d).take(4)
                            entered = next
                            if (next.length == 4) submit(next)
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(72.dp).width(88.dp),
                    ) { Text(d.toString(), fontSize = 30.sp) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { entered = "" },
                modifier = Modifier.height(72.dp).width(88.dp),
            ) { Text("Clear", fontSize = 18.sp) }
            Button(
                onClick = { entered = entered.dropLast(1) },
                modifier = Modifier.height(72.dp).width(88.dp),
            ) { Text("←", fontSize = 26.sp) }
        }
    }
}

/**
 * The guarded settings: playback defaults, picture and sound, languages, and
 * the maximum age for the library. Every change is saved straight away.
 */
@Composable
fun SettingsScreen(store: StateStore, rootPath: String, onChangeFolder: () -> Unit) {
    var unlocked by remember { mutableStateOf(false) }
    if (!unlocked) {
        PinGate(store, onUnlocked = { unlocked = true })
        return
    }

    val settings = store.data.settings
    fun update(block: (Settings) -> Unit) {
        block(settings)
        store.save()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        Text("Library", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Media folder", fontSize = 18.sp)
        Text(rootPath, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onChangeFolder) { Text("Choose another folder", fontSize = 18.sp) }
        Spacer(Modifier.height(24.dp))

        Text("Playback", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Default speed", fontSize = 18.sp)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Speeds.forEach { s ->
                FilterChip(
                    selected = settings.defaultSpeed == s,
                    onClick = { update { it.defaultSpeed = s } },
                    label = { Text(if (s == 1.0f) "1×" else "${s}×", fontSize = 16.sp) },
                )
            }
        }
        SliderRow(
            label = "Loudness",
            value = settings.loudness,
            range = -12f..12f,
            valueText = "${settings.loudness.toInt()} dB",
            onValueChange = { v -> update { it.loudness = v } },
        )

        Spacer(Modifier.height(16.dp))
        Text("Picture", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        SliderRow("Brightness", settings.brightness, -1f..1f, "%.2f".format(settings.brightness)) { v -> update { it.brightness = v } }
        SliderRow("Gamma", settings.gamma, 0.5f..2f, "%.2f".format(settings.gamma)) { v -> update { it.gamma = v } }
        SliderRow("Contrast", settings.contrast, 0.5f..2f, "%.2f".format(settings.contrast)) { v -> update { it.contrast = v } }
        SliderRow("Colors", settings.saturation, 0f..2f, "%.2f".format(settings.saturation)) { v -> update { it.saturation = v } }

        Spacer(Modifier.height(16.dp))
        Text("Languages", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = settings.subtitlesOn, onCheckedChange = { c -> update { it.subtitlesOn = c } })
            Spacer(Modifier.width(12.dp))
            Text("Subtitles on by default", fontSize = 18.sp)
        }
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 6, 12, 16, 18).forEach { age ->
                FilterChip(
                    selected = settings.maxAgeYears == age,
                    onClick = { update { it.maxAgeYears = age } },
                    label = { Text(if (age == 0) "Off" else "$age+", fontSize = 16.sp) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                update { it.pin = (1000..9999).random().toString() }
                unlocked = false
            },
        ) { Text("Choose a new PIN", fontSize = 18.sp) }
        Spacer(Modifier.height(32.dp))
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
