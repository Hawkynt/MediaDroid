package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.data.PRIMARY_SOURCE
import com.hawkynt.mediadroid.data.SecretStore
import com.hawkynt.mediadroid.data.SourceAccess
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import com.hawkynt.mediadroid.sources.SmbFs
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the library comes from: the media folder chosen at the start and every other saved source —
 * more folders on the device, Windows / Samba shares. Each is saved once with a name; its password
 * goes to the Keystore-backed [SecretStore], never into the state file.
 */
@Composable
fun SourcesSection(
    store: StateStore,
    unreachable: Set<String>,
    onChangeMainFolder: () -> Unit,
    onAddDeviceFolder: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Source?>(null) }
    val sources = store.sources

    Text("Sources", fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text(
        "Every folder and share listed here makes up one library; a film found on two of them is one card.",
        fontSize = 15.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    sources.forEach { source ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(source.name, fontSize = 18.sp)
                Text(
                    kindLabel(source.kind) + " · " + source.rootUri,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (source.id in unreachable) {
                    Text("Not reachable right now", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            when {
                source.id == PRIMARY_SOURCE -> OutlinedButton(onClick = onChangeMainFolder) { Text("Change") }
                source.kind == SourceKind.SMB -> TextButton(onClick = { editing = source }) { Text("Edit") }
            }
            if (source.id != PRIMARY_SOURCE) {
                TextButton(onClick = {
                    store.saveSources(sources.filter { it.id != source.id }.withPrimary(store))
                    SecretStore.of(context).save(source.id, null)
                    SourceAccess.of(context).forget(source.id)
                    onChanged()
                }) { Text("Remove") }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onAddDeviceFolder) { Text("Add a folder", fontSize = 16.sp) }
        OutlinedButton(onClick = {
            editing = Source(UUID.randomUUID().toString(), "", SourceKind.SMB)
        }) { Text("Add a network share", fontSize = 16.sp) }
    }

    editing?.let { source ->
        SmbDialog(
            source = source,
            password = SecretStore.of(context).secretFor(source.id).orEmpty(),
            onDismiss = { editing = null },
            onSave = { saved, password ->
                SecretStore.of(context).save(saved.id, password)
                SourceAccess.of(context).forget(saved.id)
                val others = sources.filter { it.id != saved.id }
                store.saveSources((others + saved).withPrimary(store))
                editing = null
                onChanged()
            },
        )
    }
}

/** Saved sources always keep the media folder chosen at the start, first. */
private fun List<Source>.withPrimary(store: StateStore): List<Source> {
    val primary = store.sources.firstOrNull { it.id == PRIMARY_SOURCE }
    return if (primary == null || any { it.id == PRIMARY_SOURCE }) this else listOf(primary) + this
}

private fun kindLabel(kind: SourceKind) = when (kind) {
    SourceKind.LOCAL -> "On this device"
    SourceKind.SMB -> "Windows / Samba share"
    SourceKind.NFS -> "NFS share"
    SourceKind.FTP, SourceKind.FTPS -> "FTP"
    SourceKind.SFTP -> "SFTP"
    SourceKind.WEBDAV -> "WebDAV"
    SourceKind.DOCUMENTS -> "Cloud"
}

/** The fields of a Windows / Samba share, with a test that lists the share before it is saved. */
@Composable
private fun SmbDialog(
    source: Source,
    password: String,
    onDismiss: () -> Unit,
    onSave: (Source, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(source.name) }
    var server by remember { mutableStateOf(source.host) }
    var folder by remember { mutableStateOf(source.path.trim('/')) }
    var user by remember { mutableStateOf(source.user) }
    var secret by remember { mutableStateOf(password) }
    var status by remember { mutableStateOf<String?>(null) }

    fun edited() = source.copy(
        name = name.trim().ifEmpty { "${server.trim()}/${folder.trim('/').substringBefore('/')}" },
        host = server.trim().removePrefix("smb://").removePrefix("\\\\").trimEnd('/'),
        path = "/" + folder.trim().replace('\\', '/').trim('/'),
        user = user.trim(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Windows / Samba share") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    server,
                    { server = it },
                    label = { Text("Server — nas.local or 192.168.1.10") },
                    singleLine = true,
                )
                OutlinedTextField(
                    folder,
                    { folder = it },
                    label = { Text("Share and folder — media/movies") },
                    singleLine = true,
                )
                OutlinedTextField(
                    user,
                    { user = it },
                    label = { Text("User — empty for a guest share") },
                    singleLine = true,
                )
                OutlinedTextField(
                    secret,
                    { secret = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        status = "Connecting…"
                        val candidate = edited()
                        scope.launch {
                            status = withContext(Dispatchers.IO) {
                                runCatching {
                                    SmbFs(candidate) { secret }.use { fs ->
                                        val entries = fs.root.listFiles() ?: error("the share or folder cannot be read")
                                        "Connected: ${entries.size} entries in ${candidate.rootUri}"
                                    }
                                }.getOrElse { "Not reachable: ${it.message ?: it.javaClass.simpleName}" }
                            }
                        }
                    }) { Text("Test") }
                    Spacer(Modifier.width(12.dp))
                    status?.let { Text(it, fontSize = 14.sp) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = server.isNotBlank() && folder.isNotBlank(),
                onClick = { onSave(edited(), secret) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
