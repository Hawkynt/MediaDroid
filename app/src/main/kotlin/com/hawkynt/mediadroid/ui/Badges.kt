package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.library.Languages
import com.hawkynt.mediadroid.library.Quality

/**
 * The minimum age as a square badge in the colours German households know from FSK labels —
 * white 0, yellow 6, green 12, blue 16, red 18 — readable for anyone who cannot read the number.
 */
@Composable
fun AgeBadge(age: Int, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val (background, foreground) = when {
        age >= 18 -> Color(0xFFE2001A) to Color.White
        age >= 16 -> Color(0xFF009FE3) to Color.White
        age >= 12 -> Color(0xFF00A651) to Color.White
        age >= 6 -> Color(0xFFFFED00) to Color.Black
        else -> Color.White to Color.Black
    }
    Box(
        modifier = modifier.size(size).background(background, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("$age", color = foreground, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Audio and subtitle languages of a file as flags, from its NFO's stream details. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguageFlags(audio: List<String>, subtitles: List<String>, modifier: Modifier = Modifier) {
    if (audio.isEmpty() && subtitles.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (audio.isNotEmpty()) FlagGroup("Audio", audio)
        if (subtitles.isNotEmpty()) FlagGroup("Subtitles", subtitles)
    }
}

@Composable
private fun FlagGroup(label: String, codes: List<String>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        codes.forEach { code ->
            val flag = Languages.flag(code)
            if (flag != null) {
                Text(flag, fontSize = 18.sp)
            } else {
                Text(
                    Languages.label(code),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/**
 * What a preview picture tells about its file, drawn over it: audio languages as flags at the top
 * left, subtitle languages behind a "CC" at the top right, and resolution, HDR, video and audio format
 * along the bottom. Every mark sits on its own dark chip so it reads on any picture.
 */
@Composable
fun BoxScope.ThumbBadges(audio: List<String>, subtitles: List<String>, quality: Quality) {
    if (audio.isNotEmpty()) {
        Row(Modifier.align(Alignment.TopStart).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            audio.take(4).forEach { ThumbChip(Languages.flag(it) ?: Languages.label(it)) }
        }
    }
    if (subtitles.isNotEmpty()) {
        Row(Modifier.align(Alignment.TopEnd).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ThumbChip("CC " + subtitles.take(3).joinToString(" ") { Languages.flag(it) ?: Languages.label(it) })
        }
    }
    if (quality.badges.isNotEmpty()) {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            quality.badges.forEach { ThumbChip(it, bold = true) }
        }
    }
}

@Composable
private fun ThumbChip(text: String, bold: Boolean = false) {
    Text(
        text,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(3.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp),
    )
}
