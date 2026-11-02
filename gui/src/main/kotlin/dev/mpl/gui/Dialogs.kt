package dev.mpl.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.mpl.api.BotSettings
import dev.mpl.api.ClientSettings
import dev.mpl.api.CreateInstanceRequest
import dev.mpl.api.CreateProjectRequest
import dev.mpl.api.DbBinding
import dev.mpl.api.InstanceType
import dev.mpl.api.MplHome
import dev.mpl.api.PluginLink
import kotlinx.coroutines.launch

@Composable
fun CreateProjectDialog(state: AppState, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var dir by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    MplDialog("New project", onClose) {
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
    var proxied by remember { mutableStateOf(true) }
    val hasProxy = state.projectInstances.any { it.def.type.isProxy }
    var submitted by remember { mutableStateOf(false) }
    val targets = state.projectInstances.map { it.def }.filter { it.type != InstanceType.SOULFIRE }
    var target by remember { mutableStateOf(targets.firstOrNull { it.type.isProxy }?.name ?: targets.firstOrNull()?.name.orEmpty()) }
    var count by remember { mutableStateOf("2") }
    var prefix by remember { mutableStateOf("Bot") }
    var jar by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("Player") }
    var databaseName by remember { mutableStateOf("") }
    var bindAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val bots = type == InstanceType.SOULFIRE
    val client = type == InstanceType.CLIENT

    LaunchedEffect(type) {
        versions = state.versions(type.software!!)
        version = if (type.isDatabase) versions.firstOrNull().orEmpty() else versions.firstOrNull { v -> v.none(Char::isLetter) } ?: versions.firstOrNull().orEmpty()
    }

    val creating = submitted && state.isBusy(name)
    val canCreate = name.isNotBlank() && !creating && when {
        bots -> target.isNotBlank() && (count.toIntOrNull() ?: 0) > 0 && (version.isNotBlank() || jar.isNotBlank())
        client -> version.isNotBlank() && Regex("[A-Za-z0-9_]{3,16}").matches(username)
        type.isDatabase -> version.isNotBlank() && (databaseName.isBlank() || Regex("[A-Za-z0-9_]{1,48}").matches(databaseName))
        else -> version.isNotBlank() && (type.isProxy || acceptEula)
    }

    MplDialog("New instance in $project", onClose, width = 620) {
        OutlinedTextField(name, { name = it.trim() }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { TYPE_GROUPS.forEach { (group, types) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    types.forEach { FilterChip(selected = type == it, onClick = { type = it }, label = { Text(TYPE_LABELS[it] ?: it.name.lowercase()) }) }
                }
            }
        } }
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
        } else if (type.isDatabase) {
            VersionField(version, versions, onChange = { version = it })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port (auto)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(databaseName, { databaseName = it.trim() }, label = { Text("Database (project name)") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            CheckRow("Allow other machines to connect (listen on all interfaces)", bindAll) { bindAll = it }
            Text(
                if (version == "system") "Uses the MariaDB installed on this machine." else "Downloads MariaDB $version (several hundred MB) on first use.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            VersionField(version, versions, onChange = { version = it })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port (auto)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(memory, { memory = it.trim() }, label = { Text(if (type.isProxy) "Memory (512M)" else "Memory (2G)") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            if (type.isServer) Column {
                CheckRow("Accept the Minecraft EULA (aka.ms/MinecraftEULA)", acceptEula) { acceptEula = it }
                CheckRow("Offline mode (online-mode=false)", offline) { offline = it }
                if (hasProxy) CheckRow("Behind the project's proxy (off: standalone server with its own port)", proxied) { proxied = it }
            }
        }
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
                proxied = proxied,
                databaseName = databaseName.ifBlank { null }.takeIf { type.isDatabase },
                bindAll = bindAll && type.isDatabase,
            )
            state.createInstance(request, onDone = onClose)
        }
    }
}

private val TYPE_GROUPS = listOf(
    "Server" to listOf(InstanceType.PAPER, InstanceType.PURPUR, InstanceType.FOLIA, InstanceType.SPIGOT),
    "Proxy" to listOf(InstanceType.VELOCITY, InstanceType.WATERFALL, InstanceType.BUNGEECORD),
    "Database" to listOf(InstanceType.MARIADB),
    "Player" to listOf(InstanceType.SOULFIRE, InstanceType.CLIENT),
)
private val TYPE_LABELS = mapOf(InstanceType.SOULFIRE to "soulfire bots", InstanceType.CLIENT to "minecraft client")
private const val NO_TARGET = "main menu"

@Composable
private fun ChoiceField(label: String, value: String, choices: List<String>, onChange: (String) -> Unit) =
    DropdownField(label, value, choices, editable = false, onChange = onChange)

@Composable
private fun VersionField(version: String, versions: List<String>, onChange: (String) -> Unit) =
    DropdownField("Version", version, versions.take(60), editable = true, onChange = onChange)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(label: String, value: String, choices: List<String>, editable: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, onExpandedChange = { expanded = it }) {
        val anchor = if (editable) ExposedDropdownMenuAnchorType.PrimaryEditable else ExposedDropdownMenuAnchorType.PrimaryNotEditable
        OutlinedTextField(
            value, onChange,
            readOnly = !editable,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded, Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable)) },
            modifier = Modifier.menuAnchor(anchor).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 260.dp)) {
            choices.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { onChange(it); expanded = false }) }
        }
    }
}

@Composable
fun RemoveDialog(title: String, detail: String?, deleteLabel: String, onClose: () -> Unit, onConfirm: (deleteFiles: Boolean) -> Unit) {
    var deleteFiles by remember { mutableStateOf(false) }
    MplDialog(title, onClose, width = 480) {
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        CheckRow(deleteLabel, deleteFiles) { deleteFiles = it }
        DialogButtons(confirm = "Remove", enabled = true, onCancel = onClose) { onConfirm(deleteFiles) }
    }
}

@Composable
fun LinkPluginDialog(state: AppState, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var targets by remember { mutableStateOf(emptySet<String>()) }
    var autoDeploy by remember { mutableStateOf(true) }
    var restart by remember { mutableStateOf(true) }
    var dbFile by remember { mutableStateOf("") }
    var dbValues by remember { mutableStateOf("") }
    val hasDatabase = state.projectInstances.any { it.def.type.isDatabase }
    val scope = rememberCoroutineScope()

    MplDialog("Link plugin", onClose) {
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
        Text("Deploy to (none selected = all servers)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.projectInstances.filter { it.def.type.isServer || it.def.type.isProxy }.forEach { instance ->
                val target = instance.def.name
                FilterChip(
                    selected = target in targets,
                    onClick = { targets = if (target in targets) targets - target else targets + target },
                    label = { Text("$target · ${instance.def.type.name.lowercase()}") },
                )
            }
        }
        Column {
            CheckRow("Deploy automatically when the jar changes", autoDeploy) { autoDeploy = it }
            CheckRow("Restart running servers after deploying", restart) { restart = it }
        }
        if (hasDatabase) {
            Text("Database (optional): write the project database into this plugin's config on every start", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(dbFile, { dbFile = it.trim() }, label = { Text("Config file") }, placeholder = { Text("plugins/MyPlugin/config.yml") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                dbValues, { dbValues = it },
                label = { Text("key=template, one per line") },
                placeholder = { Text("storage.host={host}\nstorage.port={port}\nstorage.password={password}") },
                supportingText = { Text("{host} {port} {database} {user} {password} {jdbc}. The file must exist (start the server once).") },
                minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth(),
            )
        }
        val binding = parseBinding(dbFile, dbValues)
        DialogButtons(confirm = "Link", enabled = name.isNotBlank() && source.isNotBlank(), onCancel = onClose) {
            state.savePlugin(PluginLink(name, source, targets.toList(), autoDeploy, restart, db = binding), onDone = onClose)
        }
    }
}

private fun parseBinding(file: String, text: String): DbBinding? {
    val values = text.lines().filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    return if (file.isBlank() || values.isEmpty()) null else DbBinding(file, values)
}

private fun versionGlob(path: String): String {
    val file = path.substringAfterLast('/')
    val glob = file.replace(Regex("""-\d[\w.+-]*\.jar$"""), "-*.jar")
    return path.removeSuffix(file) + glob
}

@Composable
private fun DialogButtons(confirm: String, enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Spacer(Modifier.size(8.dp))
        Button(onClick = onConfirm, enabled = enabled) { Text(confirm) }
    }
}

@Composable
fun QuitDialog(state: AppState, onKeepRunning: () -> Unit, onQuit: () -> Unit) {
    val alive = state.aliveInstances
    MplDialog(if (state.quitting) "Stopping…" else "Instances are still running", { state.closeRequested = false }, width = 520) {
        Text(
            if (state.quitting) "Stopping ${alive.size} running instances and the daemon."
            else if (alive.size == 1) "${alive.single().def.id} is running. Keep it running in the background, or stop it and quit?"
            else "${alive.joinToString { it.def.id.toString() }} are running. Keep them running in the background, or stop everything and quit?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = { state.closeRequested = false }, enabled = !state.quitting) { Text("Cancel") }
            OutlinedButton(onClick = onKeepRunning, enabled = !state.quitting) { Text("Keep running") }
            Button(onClick = onQuit, enabled = !state.quitting) { Text("Stop all and quit") }
        }
    }
}

val openDialogs = mutableIntStateOf(0)

@Composable
private fun MplDialog(title: String, onClose: () -> Unit, width: Int = 560, content: @Composable ColumnScope.() -> Unit) {
    DisposableEffect(Unit) {
        openDialogs.intValue++
        onDispose { openDialogs.intValue-- }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(width.dp)
                .heightIn(max = 760.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}
