package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import com.hawkynt.mediadroid.sources.SftpFs
import com.hawkynt.mediadroid.sources.SourceFiles
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
                source.kind in NETWORK_KINDS -> TextButton(onClick = { editing = source }) { Text("Edit") }
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
        }) { Text("Add a network source", fontSize = 16.sp) }
    }

    editing?.let { source ->
        NetworkSourceDialog(
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

private const val SMB_FOLDER_HINT = "Share and folder — media/movies"
private const val FOLDER_HINT = "Folder — /media/movies"

/** The kinds of network source the dialog adds. */
private val NETWORK_KINDS = listOf(SourceKind.SMB, SourceKind.FTP, SourceKind.FTPS, SourceKind.SFTP)

/**
 * The fields of a network source — a Windows / Samba share, FTP, FTPS or SFTP — with a test that lists
 * it before it is saved. For SFTP the server's host key seen on the test (or on saving) is kept with
 * the source and checked on every later connection.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetworkSourceDialog(
    source: Source,
    password: String,
    onDismiss: () -> Unit,
    onSave: (Source, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(source.kind) }
    var name by remember { mutableStateOf(source.name) }
    var server by remember { mutableStateOf(source.host) }
    var port by remember { mutableStateOf(source.port.takeIf { it > 0 }?.toString().orEmpty()) }
    var folder by remember { mutableStateOf(source.path.trim('/')) }
    var user by remember { mutableStateOf(source.user) }
    var secret by remember { mutableStateOf(password) }
    var hostKey by remember { mutableStateOf(source.hostKey) }
    var status by remember { mutableStateOf<String?>(null) }

    fun edited() = source.copy(
        kind = kind,
        name = name.trim().ifEmpty { "${server.trim()}/${folder.trim('/').substringBefore('/')}".trimEnd('/') },
        host = server.trim().substringAfter("://").removePrefix("\\\\").trimEnd('/'),
        port = port.trim().toIntOrNull() ?: 0,
        path = "/" + folder.trim().replace('\\', '/').trim('/'),
        user = user.trim(),
        hostKey = if (kind == SourceKind.SFTP) hostKey else "",
    )

    /** Connects with the fields as they stand; returns what to say, and keeps an SFTP host key. */
    suspend fun test(candidate: Source): String = withContext(Dispatchers.IO) {
        runCatching {
            val fs = SourceFiles.open(candidate) { secret } ?: error("this kind cannot be read yet")
            fs.use {
                val entries = it.root.listFiles() ?: error("the folder cannot be read")
                (it as? SftpFs)?.hostKey?.let { key -> hostKey = key }
                "Connected: ${entries.size} entries in ${candidate.rootUri}"
            }
        }.getOrElse { "Not reachable: ${it.message ?: it.javaClass.simpleName}" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Network source") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NETWORK_KINDS.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(kindLabel(option)) },
                        )
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    server,
                    { server = it },
                    label = { Text("Server — nas.local or 192.168.1.10") },
                    singleLine = true,
                )
                OutlinedTextField(
                    port,
                    { port = it.filter(Char::isDigit) },
                    label = { Text("Port — empty for the usual one") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    folder,
                    { folder = it },
                    label = { Text(if (kind == SourceKind.SMB) SMB_FOLDER_HINT else FOLDER_HINT) },
                    singleLine = true,
                )
                OutlinedTextField(
                    user,
                    { user = it },
                    label = { Text(if (kind == SourceKind.SFTP) "User" else "User — empty for a guest") },
                    singleLine = true,
                )
                OutlinedTextField(
                    secret,
                    { secret = it },
                    label = { Text(if (kind == SourceKind.SFTP) "Password, or a private key" else "Password") },
                    singleLine = kind != SourceKind.SFTP,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (kind == SourceKind.SFTP && hostKey.isNotEmpty()) {
                    Text("Host key: $hostKey", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        status = "Connecting…"
                        val candidate = edited()
                        scope.launch { status = test(candidate) }
                    }) { Text("Test") }
                    Spacer(Modifier.width(12.dp))
                    status?.let { Text(it, fontSize = 14.sp) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = server.isNotBlank() && (kind != SourceKind.SMB || folder.isNotBlank()),
                onClick = {
                    // An SFTP server is met once before saving, so its host key is kept from the start.
                    if (kind == SourceKind.SFTP && hostKey.isEmpty()) {
                        val candidate = edited()
                        scope.launch {
                            status = test(candidate)
                            if (hostKey.isNotEmpty()) onSave(edited(), secret)
                        }
                    } else {
                        onSave(edited(), secret)
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
