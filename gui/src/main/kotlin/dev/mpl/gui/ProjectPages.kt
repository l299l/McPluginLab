package dev.mpl.gui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mpl.api.Network
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
        link.db?.let { Detail("Database", "${it.file} (${it.values.size} keys)") }
        Detail("Current jar", status.jar?.substringAfterLast('/') ?: "no jar matches yet")
        status.deployedAt?.let { Detail("Last deploy", "${status.deployedJar} at ${DateFormat.getTimeInstance().format(Date(it))}") }
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun NetworkPage(state: AppState) {
    val project = state.project ?: return
    val instances = state.projectInstances.map { it.def }
    val proxy = Network.proxyOf(instances)
    val backends = Network.backendsOf(instances)
    val servers = instances.filter { it.type.isServer }.sortedWith(compareBy({ it !in backends }, { backends.indexOf(it) }, { it.name }))

    Page("Network", "With a proxy in the project, forwarding and the server list are written before every start.", action = {
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
                Text("No proxy in this project. Add a Velocity, Waterfall or BungeeCord instance to build a network.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Card
            }
            val forwarding = if (Network.usesModernForwarding(proxy, backends)) "modern forwarding" else "legacy (BungeeCord) forwarding"
            Text("${proxy.name}  ·  ${proxy.type.name.lowercase()} on port ${proxy.port}  ·  $forwarding", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(8.dp))
            if (servers.isEmpty()) Text("No servers yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            servers.forEach { server ->
                val id = server.id
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(server.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(160.dp))
                    Text(
                        "127.0.0.1:${server.port}" + when {
                            server == backends.firstOrNull() -> "  ·  join server"
                            server !in backends -> "  ·  standalone, join it directly"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text("Behind proxy", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.size(8.dp))
                    Switch(server.proxied, { state.setProxied(id, it) }, enabled = !state.isBusy(id), modifier = Modifier.scale(0.75f))
                }
            }
            Spacer(Modifier.size(4.dp))
            Text(
                "Players join the first server behind the proxy (one named *lobby* comes first). Changes apply to running proxies right away; restart backends to pick up forwarding changes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
