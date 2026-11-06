package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.data.BUILT_IN_SCREEN
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.data.screenKey
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
    ScreenSetupRows("This device's screen", BUILT_IN_SCREEN, store)
    external?.let { ScreenSetupRows(it.name, screenKey(it), store) }
}

/** One screen's setup, [key] as [StateStore.screenSetup] keeps it, under its [name]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreenSetupRows(name: String, key: String, store: StateStore) {
    val setup = store.screenSetup(key)
    fun save(changed: ScreenSetup) = store.saveScreenSetup(key, changed)
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
}
