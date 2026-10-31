package dev.mpl.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.mpl.api.CreateInstanceRequest
import dev.mpl.api.InstanceType

@Composable
fun CreateInstanceDialog(state: AppState, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(InstanceType.PAPER) }
    var versions by remember { mutableStateOf(emptyList<String>()) }
    var version by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var memory by remember { mutableStateOf("") }
    var acceptEula by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(true) }
    var submitted by remember { mutableStateOf(false) }

    LaunchedEffect(type) {
        versions = state.versions(type.fillProject!!)
        version = versions.firstOrNull { v -> v.none(Char::isLetter) }.orEmpty()
    }

    val creating = submitted && name in state.busy
    val canCreate = name.isNotBlank() && version.isNotBlank() && (type.isProxy || acceptEula) && !creating

    MplDialog("New instance", onClose, height = 560) {
        OutlinedTextField(name, { name = it.trim() }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InstanceType.entries.filter { it.fillProject != null }.forEach {
                FilterChip(selected = type == it, onClick = { type = it }, label = { Text(it.name.lowercase()) })
            }
        }
        VersionField(version, versions, onChange = { version = it })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port (auto)") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(memory, { memory = it.trim() }, label = { Text(if (type.isProxy) "Memory (512M)" else "Memory (2G)") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        if (!type.isProxy) {
            CheckRow("Accept the Minecraft EULA (aka.ms/MinecraftEULA)", acceptEula) { acceptEula = it }
            CheckRow("Offline mode (online-mode=false)", offline) { offline = it }
        }
        Spacer(Modifier.weight(1f))
        DialogButtons(confirm = if (creating) "Downloading…" else "Create", enabled = canCreate, onCancel = onClose) {
            submitted = true
            val request = CreateInstanceRequest(
                name = name,
                type = type,
                version = version,
                port = port.toIntOrNull(),
                memory = memory.ifBlank { null },
                acceptEula = acceptEula,
                offlineMode = offline && !type.isProxy,
            )
            state.create(request, onDone = onClose)
        }
    }
}

@Composable
fun RemoveInstanceDialog(name: String, onClose: () -> Unit, onConfirm: (deleteFiles: Boolean) -> Unit) {
    var deleteFiles by remember { mutableStateOf(false) }
    MplDialog("Remove $name?", onClose, height = 230) {
        CheckRow("Also delete the server directory", deleteFiles) { deleteFiles = it }
        Spacer(Modifier.weight(1f))
        DialogButtons(confirm = "Remove", enabled = true, onCancel = onClose) { onConfirm(deleteFiles) }
    }
}

@Composable
private fun VersionField(version: String, versions: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            version, onChange,
            label = { Text("Version") },
            singleLine = true,
            trailingIcon = { TextButton(onClick = { expanded = true }) { Text("▾") } },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            versions.take(40).forEach {
                DropdownMenuItem(text = { Text(it) }, onClick = { onChange(it); expanded = false })
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DialogButtons(confirm: String, enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Spacer(Modifier.size(8.dp))
        Button(onClick = onConfirm, enabled = enabled) { Text(confirm) }
    }
}

@Composable
private fun MplDialog(title: String, onClose: () -> Unit, height: Int, content: @Composable ColumnScope.() -> Unit) {
    DialogWindow(onCloseRequest = onClose, title = title, state = rememberDialogState(size = DpSize(480.dp, height.dp))) {
        MplTheme {
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }
}
