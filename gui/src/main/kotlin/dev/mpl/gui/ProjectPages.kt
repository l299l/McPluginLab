package dev.mpl.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mpl.api.InstanceType
import dev.mpl.api.PluginStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun PluginsPage(state: AppState) {
    var editing by remember { mutableStateOf(false) }

    Page("Plugins", "Linked jars are copied into the target servers whenever the build output changes.", action = {
        OutlinedButton(onClick = { state.deploy(null) }, enabled = state.plugins.isNotEmpty()) { Text("Deploy all") }
        Spacer(Modifier.size(8.dp))
        Button(onClick = { editing = true }) { Text("Link plugin") }
    }) {
        if (state.plugins.isEmpty()) Text(
            "No plugins linked. Link your plugin's build output, e.g. ~/MyPlugin/target/MyPlugin-*.jar",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.plugins.forEach { PluginCard(state, it) }
    }

    if (editing) LinkPluginDialog(state, onClose = { editing = false })
}

@Composable
private fun PluginCard(state: AppState, status: PluginStatus) {
    val link = status.link
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(link.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(link.source.shortPath, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { state.removePlugin(link.name) }) { Text("Unlink") }
            OutlinedButton(onClick = { state.deploy(link.name) }, enabled = !state.isBusy("plugin:${link.name}")) { Text("Deploy now") }
        }
        Spacer(Modifier.size(8.dp))
        Detail("Targets", link.targets.ifEmpty { listOf("all servers") }.joinToString())
        Detail("Mode", listOfNotNull(if (link.autoDeploy) "auto deploy" else "manual", if (link.restart) "restart running servers" else null).joinToString(", "))
        Detail("Current jar", status.jar?.substringAfterLast('/') ?: "no jar matches yet")
        status.deployedAt?.let { Detail("Last deploy", "${status.deployedJar} at ${DateFormat.getTimeInstance().format(Date(it))}") }
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun NetworkPage(state: AppState) {
    val project = state.project ?: return
    val instances = state.projectInstances.map { it.def }
    val proxy = instances.firstOrNull { it.type == InstanceType.VELOCITY }
    val backends = instances.filter { it.type == InstanceType.PAPER || it.type == InstanceType.FOLIA }.sortedBy { it.name }

    Page("Network", "With a Velocity proxy in the project, forwarding and the server list are configured on every start.", action = {
        Button(onClick = { state.syncNetwork(project.def.name) }, enabled = proxy != null) { Text("Sync now") }
    }) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Online mode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Off lets offline accounts and bots join. Applies to the proxy and backend forwarding.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = project.def.onlineMode, onCheckedChange = { state.setOnlineMode(project.def.name, it) })
            }
        }
        Card {
            if (proxy == null) {
                Text("No Velocity proxy in this project. Add one to build a network.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("${proxy.name}  ·  port ${proxy.port}  ·  modern forwarding", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(8.dp))
                backends.sortedByDescending { it.name.contains("lobby", ignoreCase = true) }.forEachIndexed { index, server ->
                    Detail(server.name, "127.0.0.1:${server.port}" + if (index == 0) "  (join server)" else "")
                }
                if (backends.isEmpty()) Text("No backend servers yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Page(title: String, subtitle: String, action: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action()
        }
        Spacer(Modifier.size(4.dp))
        content()
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
        .padding(16.dp),
    content = content,
)

@Composable
private fun Detail(label: String, value: String) = Row(Modifier.padding(vertical = 2.dp)) {
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(width = 120.dp, height = 18.dp))
    Text(value, style = MaterialTheme.typography.bodySmall)
}
