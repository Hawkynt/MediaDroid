package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.R

/**
 * First start, and whenever the media folder is missing or being changed: grant file access, then
 * choose the folder with Android's own picker. A typed path stays available for TV boxes that ship
 * no picker.
 */
@Composable
fun OnboardingScreen(
    hasAccess: Boolean,
    currentPath: String,
    scanning: Boolean,
    scanError: String?,
    canCancel: Boolean,
    onGrantAccess: () -> Unit,
    onPickFolder: () -> Unit,
    onUsePath: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf(currentPath) }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.logo_mark), contentDescription = null, modifier = Modifier.size(112.dp))
            Spacer(Modifier.height(16.dp))
            Text("MediaDroid", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Your movies and shows, straight from the folders — covers, plots and all.",
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))

            if (!hasAccess) {
                Step(number = 1, text = "Let MediaDroid read your media folders.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = onGrantAccess, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text("Allow file access", fontSize = 20.sp)
                }
                Spacer(Modifier.height(32.dp))
            }

            Step(number = if (hasAccess) 1 else 2, text = "Choose the folder that holds your movies and shows.")
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onPickFolder,
                enabled = hasAccess && !scanning,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                if (scanning) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Reading your library…", fontSize = 20.sp)
                } else {
                    Icon(painterResource(R.drawable.ic_folder_open), contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Choose media folder", fontSize = 20.sp)
                }
            }
            if (currentPath.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Current: $currentPath", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (typing) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Folder path, e.g. /storage/1A2B-3C4D/Movies") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onUsePath(typed) },
                    enabled = hasAccess && !scanning && typed.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Use this folder", fontSize = 18.sp) }
            } else {
                TextButton(onClick = { typing = true }) { Text("Type a path instead", fontSize = 16.sp) }
            }

            scanError?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 16.sp, textAlign = TextAlign.Center)
            }
            if (canCancel) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onCancel) { Text("Back to the library", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.primary, shape = androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 18.sp)
    }
}

/** The first read of the folders after a start; later rescans happen behind the wall. */
@Composable
fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.logo_mark), contentDescription = null, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Reading your library…", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
