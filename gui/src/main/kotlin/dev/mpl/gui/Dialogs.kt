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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.mpl.api.BotSettings
import dev.mpl.api.ClientSettings
import dev.mpl.api.CreateInstanceRequest
import dev.mpl.api.CreateProjectRequest
import dev.mpl.api.MplHome
import dev.mpl.api.PluginLink
import dev.mpl.api.InstanceType
import kotlinx.coroutines.launch

@Composable
fun CreateProjectDialog(state: AppState, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var dir by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    MplDialog("New project", onClose, height = 330) {
        OutlinedTextField(name, { name = it.trim() }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                dir, { dir = it },
                label = { Text("Servers folder") },
                placeholder = { Text(MplHome.path.resolve("servers").resolve(name.ifBlank { "<name>" }).toString()) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = { scope.launch { FilePicker.folder(dir.ifBlank { null })?.let { dir = it } } }) { Text("Browse") }
        }
        Spacer(Modifier.weight(1f))
        DialogButtons(confirm = "Create", enabled = name.isNotBlank() && !state.isBusy(name), onCancel = onClose) {
            state.createProject(CreateProjectRequest(name, dir.ifBlank { null }), onDone = onClose)
        }
    }
}

@Composable
fun CreateInstanceDialog(state: AppState, project: String, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(InstanceType.PAPER) }
    var versions by remember { mutableStateOf(emptyList<String>()) }
    var version by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var memory by remember { mutableStateOf("") }
    var acceptEula by remember { mutableStateOf(true) }
    var offline by remember { mutableStateOf(true) }
    var submitted by remember { mutableStateOf(false) }
    val targets = state.projectInstances.map { it.def }.filter { it.type != InstanceType.SOULFIRE }
    var target by remember { mutableStateOf(targets.firstOrNull { it.type.isProxy }?.name ?: targets.firstOrNull()?.name.orEmpty()) }
    var count by remember { mutableStateOf("2") }
    var prefix by remember { mutableStateOf("Bot") }
    var jar by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("Player") }
    val scope = rememberCoroutineScope()
    val bots = type == InstanceType.SOULFIRE
    val client = type == InstanceType.CLIENT

    LaunchedEffect(type) {
        versions = state.versions(type.software!!)
        version = versions.firstOrNull { v -> v.none(Char::isLetter) } ?: versions.firstOrNull().orEmpty()
    }

    val creating = submitted && state.isBusy(name)
    val canCreate = name.isNotBlank() && !creating && when {
        bots -> target.isNotBlank() && (count.toIntOrNull() ?: 0) > 0 && (version.isNotBlank() || jar.isNotBlank())
        client -> version.isNotBlank() && Regex("[A-Za-z0-9_]{3,16}").matches(username)
        else -> version.isNotBlank() && (type.isProxy || acceptEula)
    }

    MplDialog("New instance in $project", onClose, height = 600) {
        OutlinedTextField(name, { name = it.trim() }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CREATABLE.forEach {
                FilterChip(selected = type == it, onClick = { type = it }, label = { Text(it.name.lowercase()) })
            }
        }
        if (client) {
            VersionField(version, versions, onChange = { version = it })
            OutlinedTextField(username, { username = it.trim() }, label = { Text("Player name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ChoiceField("Join", target.ifBlank { NO_TARGET }, listOf(NO_TARGET) + targets.map { it.name }, onChange = { target = if (it == NO_TARGET) "" else it })
            OutlinedTextField(memory, { memory = it.trim() }, label = { Text("Memory (2G)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        } else if (bots) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(jar, { jar = it }, label = { Text("Existing SoulFireCLI jar (optional)") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = { scope.launch { FilePicker.jar(jar.ifBlank { null })?.let { jar = it } } }) { Text("Browse") }
            }
            if (jar.isBlank()) VersionField(version, versions, onChange = { version = it })
            ChoiceField("Connect to", target, targets.map { it.name }, onChange = { target = it })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(count, { count = it.filter(Char::isDigit) }, label = { Text("Bots") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(prefix, { prefix = it.trim() }, label = { Text("Name prefix") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        } else {
            VersionField(version, versions, onChange = { version = it })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port (auto)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(memory, { memory = it.trim() }, label = { Text(if (type.isProxy) "Memory (512M)" else "Memory (2G)") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            if (type.isServer) {
                CheckRow("Accept the Minecraft EULA (aka.ms/MinecraftEULA)", acceptEula) { acceptEula = it }
                CheckRow("Offline mode (online-mode=false)", offline) { offline = it }
            }
        }
        Spacer(Modifier.weight(1f))
        DialogButtons(confirm = if (creating) "Downloading…" else "Create", enabled = canCreate, onCancel = onClose) {
            submitted = true
            val request = CreateInstanceRequest(
                project = project,
                name = name,
                type = type,
                version = version.takeIf { !bots || jar.isBlank() },
                port = port.toIntOrNull().takeIf { !bots && !client },
                memory = memory.ifBlank { null },
                jar = jar.ifBlank { null }.takeIf { bots },
                acceptEula = acceptEula,
                offlineMode = offline && type.isServer,
                bots = if (bots) BotSettings(target, count.toInt(), prefix.ifBlank { "Bot" }) else null,
                client = if (client) ClientSettings(username, target.ifBlank { null }) else null,
            )
            state.createInstance(request, onDone = onClose)
        }
    }
}

private val CREATABLE = InstanceType.entries.filter { it.software != null }
private const val NO_TARGET = "main menu"

@Composable
private fun ChoiceField(label: String, value: String, choices: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value, {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { TextButton(onClick = { expanded = true }) { Text("▾") } },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { onChange(it); expanded = false }) }
        }
    }
}

@Composable
fun RemoveDialog(title: String, detail: String?, deleteLabel: String, onClose: () -> Unit, onConfirm: (deleteFiles: Boolean) -> Unit) {
    var deleteFiles by remember { mutableStateOf(false) }
    MplDialog(title, onClose, height = 250) {
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        CheckRow(deleteLabel, deleteFiles) { deleteFiles = it }
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
fun LinkPluginDialog(state: AppState, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var targets by remember { mutableStateOf(emptySet<String>()) }
    var autoDeploy by remember { mutableStateOf(true) }
    var restart by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    MplDialog("Link plugin", onClose, height = 560) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                source, { source = it },
                label = { Text("Jar, glob or folder") },
                placeholder = { Text("~/MyPlugin/target/MyPlugin-*.jar") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    FilePicker.jar(source.substringBeforeLast('/').ifBlank { null })?.let { picked ->
                        source = versionGlob(picked)
                        if (name.isBlank()) name = picked.substringAfterLast('/').substringBefore('-').removeSuffix(".jar")
                    }
                }
            }) { Text("Browse") }
        }
        OutlinedTextField(name, { name = it.trim() }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Targets (none selected = all servers)", style = MaterialTheme.typography.labelLarge)
        Column {
            state.projectInstances.forEach { instance ->
                val target = instance.def.name
                CheckRow("$target (${instance.def.type.name.lowercase()})", target in targets) { checked ->
                    targets = if (checked) targets + target else targets - target
                }
            }
        }
        CheckRow("Deploy automatically when the jar changes", autoDeploy) { autoDeploy = it }
        CheckRow("Restart running servers after deploying", restart) { restart = it }
        Spacer(Modifier.weight(1f))
        DialogButtons(confirm = "Link", enabled = name.isNotBlank() && source.isNotBlank(), onCancel = onClose) {
            state.savePlugin(PluginLink(name, source, targets.toList(), autoDeploy, restart), onDone = onClose)
        }
    }
}

private fun versionGlob(path: String): String {
    val file = path.substringAfterLast('/')
    val glob = file.replace(Regex("""-\d[\w.+-]*\.jar$"""), "-*.jar")
    return path.removeSuffix(file) + glob
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
