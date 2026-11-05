package com.hawkynt.mediadroid.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** The kinds of network source the desktop reads (core:sources), as the picker names them. */
private val NetworkKinds = listOf(
    SourceKind.SMB to "Windows share (SMB)",
    SourceKind.NFS to "NFS",
    SourceKind.FTP to "FTP",
    SourceKind.FTPS to "FTPS",
    SourceKind.SFTP to "SFTP",
    SourceKind.WEBDAV to "WebDAV",
)

/**
 * The network sources: the ones added, each with a way to remove it, and a form to add one — tried once before it
 * is kept, so a typo shows at once rather than as an empty library. [onChanged] after every change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourcesPanel(places: DesktopLibrary, onChanged: () -> Unit, onClose: () -> Unit) {
    var sources by remember { mutableStateOf(places.networkSources) }
    var kind by remember { mutableStateOf(SourceKind.SMB) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun source() = Source(
        id = UUID.randomUUID().toString(),
        name = name.ifBlank { if (kind == SourceKind.WEBDAV) url else "$host ${path.trim('/')}".trim() },
        kind = kind,
        host = host.trim(),
        port = port.toIntOrNull() ?: 0,
        path = path.trim(),
        user = user.trim(),
        url = url.trim(),
    )

    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(onClick = onClose)) {
        Column(
            Modifier.align(Alignment.Center).width(560.dp).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface).clickable(enabled = false) {}
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Network sources", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (sources.isEmpty()) Text("None yet.", color = Color.Gray)
            sources.forEach { source ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, color = Color.White)
                        Text(source.rootUri, color = Color.Gray, fontSize = 13.sp)
                    }
                    IconButton(onClick = {
                        places.remove(source)
                        sources = places.networkSources
                        onChanged()
                    }) { Icon(Icons.Default.Delete, "Remove ${source.name}", tint = Color.Gray) }
                }
            }
            Text("Add one", fontSize = 18.sp, color = Color.White, modifier = Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NetworkKinds.forEach { (k, label) ->
                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(label) })
                }
            }
            // Servers announcing SMB on the local network, one click to fill in.
            val lan by rememberSmbServers(active = kind == SourceKind.SMB)
            if (kind == SourceKind.SMB && lan.isNotEmpty()) {
                Text("On this network", color = Color.Gray, fontSize = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    lan.forEach { found ->
                        FilterChip(
                            selected = host == found.host,
                            onClick = {
                                host = found.host
                                if (name.isBlank()) name = found.name
                            },
                            label = { Text(found.name) },
                        )
                    }
                }
            }
            if (kind == SourceKind.WEBDAV) {
                Field("Address", url, "https://cloud.example.org/remote.php/dav/files/me/Media") { url = it }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Field("Server", host, "nas or 192.168.1.10") { host = it } }
                    Box(Modifier.width(110.dp)) { Field("Port", port, "default", KeyboardType.Number) { port = it } }
                }
                val folderHint = if (kind == SourceKind.SMB) "/share/folder" else "/folder"
                Field("Folder", path, folderHint) { path = it }
            }
            if (kind != SourceKind.NFS) {
                Field("User", user, "empty for a guest") { user = it }
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Field("Name", name, "as the library shows it") { name = it }
            problem?.let { Text(it, color = Color(0xFFFF8A80)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Done") }
                Spacer(Modifier.weight(1f))
                Button(enabled = !busy && (host.isNotBlank() || url.isNotBlank()), onClick = {
                    val made = source()
                    busy = true
                    problem = null
                    scope.launch {
                        val wrong = withContext(Dispatchers.IO) { places.check(made, password) }
                        busy = false
                        if (wrong != null) {
                            problem = "That did not work: $wrong"
                        } else {
                            places.add(made, password)
                            sources = places.networkSources
                            host = ""; port = ""; path = ""; url = ""; user = ""; password = ""; name = ""
                            onChanged()
                        }
                    }
                }) { Text(if (busy) "Trying…" else "Try and add") }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    hint: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, placeholder = { Text(hint) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
