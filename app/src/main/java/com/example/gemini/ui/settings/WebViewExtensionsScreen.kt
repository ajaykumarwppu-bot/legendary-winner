package com.example.gemini.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.gemini.ui.browser.BrowserExtension
import com.example.gemini.ui.browser.BrowserExtensionManager
import kotlinx.coroutines.launch

@Composable
fun WebViewExtensionsScreen(onChanged: () -> Unit) {
    val manager = remember { BrowserExtensionManager.instance }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<BrowserExtension?>(null) }
    var zip by remember { mutableStateOf<Uri?>(null) }
    var rename by remember { mutableStateOf<BrowserExtension?>(null) }
    var folder by remember { mutableStateOf("") }
    fun runOperation(action: suspend () -> Unit) {
        scope.launch {
            busy = true; error = null
            runCatching { action() }.onSuccess { onChanged() }.onFailure { error = it.message ?: "Extension operation failed" }
            busy = false
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val filename = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
            folder = update?.folderName ?: filename.orEmpty().substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "-").take(64).ifBlank { "my-extension" }
            zip = uri
        }
    }
    LaunchedEffect(Unit) { runCatching { manager.refreshExtensions() }.onFailure { error = it.message } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Install MCP extensions from ZIP files. Changes apply to the running browser immediately.", style = MaterialTheme.typography.bodyMedium)
        Text("To create an extension with your model, add WebView Extension Developer in MCP settings. It supplies the SDK, draft editing, installation, updates, tests and worker logs.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { update = null; picker.launch(arrayOf("*/*")) }) { Text("Install ZIP") }
            OutlinedButton(enabled = !busy, onClick = { runOperation { manager.refreshExtensions() } }) { Text("Refresh") }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        manager.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (manager.extensions.isEmpty()) Text("No extensions installed. Choose a ZIP containing manifest.json and a background worker.")
        manager.extensions.forEach { extension ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(extension.folderName, style = MaterialTheme.typography.titleMedium)
                            Text("${extension.name} · ${extension.version} · ${extension.toolCount} MCP tools", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = extension.isEnabled, enabled = !busy, onCheckedChange = { enabled -> runOperation { manager.setEnabled(extension.id, enabled) } })
                    }
                    if (extension.description.isNotBlank()) Text(extension.description, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(enabled = !busy, onClick = { update = extension; picker.launch(arrayOf("*/*")) }) { Text("Update ZIP") }
                        TextButton(enabled = !busy, onClick = { rename = extension; folder = extension.folderName }) { Text("Rename folder") }
                        TextButton(enabled = !busy && extension.isEnabled, onClick = { runOperation { manager.reload(extension.id) } }) { Text("Reload") }
                    }
                }
            }
        }
        Text("Add an enabled extension to your model in MCP settings → WebView Extension MCP. Renaming a folder preserves its MCP connection. Updating or reloading restarts that extension's worker.", style = MaterialTheme.typography.bodySmall)
    }
    if (zip != null) AlertDialog(
        onDismissRequest = { zip = null }, title = { Text(if (update == null) "Install extension" else "Update extension") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = folder, onValueChange = { folder = it }, enabled = update == null, label = { Text("Folder name") }, singleLine = true)
            Text(if (update == null) "The ZIP may contain manifest.json directly or inside one enclosing folder." else "The extension's ID and stored data are preserved. The previous package is restored if loading fails.")
        } },
        confirmButton = { TextButton(enabled = folder.isNotBlank(), onClick = { val uri = zip!!; val id = update?.id; val name = folder; zip = null; runOperation { manager.installArchive(uri, name, id) } }) { Text(if (update == null) "Install" else "Update") } },
        dismissButton = { TextButton(onClick = { zip = null }) { Text("Cancel") } }
    )
    rename?.let { extension -> AlertDialog(
        onDismissRequest = { rename = null }, title = { Text("Rename extension folder") },
        text = { OutlinedTextField(value = folder, onValueChange = { folder = it }, label = { Text("Folder name") }, singleLine = true) },
        confirmButton = { TextButton(enabled = folder.isNotBlank(), onClick = { val name = folder; rename = null; runOperation { manager.renameFolder(extension.id, name) } }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } }
    ) }
}

@Composable
fun WebViewExtensionMcpPicker(onDismiss: () -> Unit, onSelect: (BrowserExtension) -> Unit) {
    val manager = remember { BrowserExtensionManager.instance }
    LaunchedEffect(Unit) { manager.loadExtensions() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("WebView Extension MCP") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val available = manager.extensions.filter { it.isEnabled && it.toolCount > 0 }
            if (available.isEmpty()) Text("Enable or install an extension in Settings → WebView Extensions first.")
            available.forEach { extension ->
                OutlinedButton(onClick = { onSelect(extension) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(extension.folderName)
                        Text("${extension.name} · ${extension.toolCount} tools", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
