package dev.mpl.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mpl.api.InstanceInfo
import dev.mpl.api.InstanceState

@Composable
fun InstancePane(state: AppState, info: InstanceInfo) {
    val id = info.def.id
    var removing by remember(id) { mutableStateOf(false) }
    var showFiles by remember(id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Header(state, info, onRemove = { removing = true })
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp)) {
            Tab("Console", selected = !showFiles) { showFiles = false }
            Tab("Files", selected = showFiles) { showFiles = true }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        key(id, showFiles, state.connection) {
            if (showFiles) FilesTab(state, id)
            else if (openDialogs.intValue > 0) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
            else SwingPanel(factory = { state.console(id).widget }, modifier = Modifier.fillMaxSize())
        }
    }

    if (removing) RemoveDialog(
        title = "Remove ${info.def.name}?",
        detail = info.def.dir,
        deleteLabel = "Also delete the server folder",
        onClose = { removing = false },
        onConfirm = { deleteFiles ->
            removing = false
            state.removeInstance(id, deleteFiles)
        },
    )
}

@Composable
fun Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp)) {
        Text(label, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 10.dp))
        Box(Modifier.height(2.dp).width(48.dp).background(if (selected) color else Color.Transparent))
    }
}

@Composable
private fun Header(state: AppState, info: InstanceInfo, onRemove: () -> Unit) {
    val id = info.def.id
    val busy = state.isBusy(id)

    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(info.def.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(info.state.color))
                Spacer(Modifier.size(6.dp))
                Text(
                    listOfNotNull(
                        info.state.name.lowercase(),
                        "${info.def.type.name.lowercase()} ${info.def.version}" + (info.def.build?.let { " #$it" } ?: ""),
                        info.def.port?.let { "port $it" },
                        info.pid?.let { "pid $it" },
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (info.state) {
                InstanceState.STARTING, InstanceState.RUNNING -> {
                    OutlinedButton(onClick = { state.action(id, "restart") }) { Text("Restart") }
                    OutlinedButton(onClick = { state.action(id, "kill") }) { Text("Kill") }
                    Button(onClick = { state.action(id, "stop") }) { Text("Stop") }
                }
                InstanceState.STOPPING -> {
                    OutlinedButton(onClick = { state.action(id, "kill") }) { Text("Kill") }
                    Button(onClick = {}, enabled = false) { Text("Stopping…") }
                }
                InstanceState.STOPPED, InstanceState.CRASHED -> {
                    TextButton(onClick = onRemove, enabled = !busy) { Text("Remove") }
                    Button(onClick = { state.action(id, "start") }, enabled = !busy) { Text("Start") }
                }
            }
            OutlinedButton(onClick = { state.openFolder(info.def.dir) }) { Text("Folder") }
            OutlinedButton(onClick = { state.openInTerminal(id) }) { Text("Pop out") }
        }
    }
}
