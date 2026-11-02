package dev.mpl.gui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mpl.api.InstanceInfo
import dev.mpl.api.InstanceState
import dev.mpl.api.InstanceType
import dev.mpl.api.Network

@Composable
fun OverviewPage(state: AppState) {
    val project = state.project ?: return
    val instances = state.projectInstances
    var selected by remember(project.def.name) { mutableStateOf(emptySet<String>()) }
    val chosen = selected intersect instances.map { it.def.name }.toSet()
    val busy = state.isBusy("group:${project.def.name}")
    val proxy = Network.proxyOf(instances.map { it.def })
    val running = instances.count { it.state == InstanceState.RUNNING }
    val subtitle = listOfNotNull(
        "$running of ${instances.size} running",
        proxy?.let { "${it.type.name.lowercase()} proxy on port ${it.port}" },
    ).joinToString("  ·  ")

    Page("Overview", subtitle, action = {
        val names = chosen.toList()
        val enabled = !busy && instances.isNotEmpty()
        if (names.isNotEmpty()) TextButton(onClick = { selected = emptySet() }) { Text("Clear selection") }
        val suffix = if (names.isEmpty()) "all" else names.size.toString()
        OutlinedButton(onClick = { state.group("restart", names) }, enabled = enabled) { Text("Restart $suffix") }
        OutlinedButton(onClick = { state.group("stop", names) }, enabled = enabled) { Text("Stop $suffix") }
        Button(onClick = { state.group("start", names) }, enabled = enabled) { Text("Start $suffix") }
    }) {
        if (instances.isEmpty()) {
            Text("No instances yet. Use “Add instance” to create a proxy, servers, bots or a client.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SECTIONS.forEach { (title, roles) ->
            val rows = instances.filter { it.def.type.role in roles }
            if (rows.isEmpty()) return@forEach
            SectionLabel(title, Modifier.padding(start = 4.dp, top = 8.dp))
            Card(padding = 0.dp) {
                rows.forEachIndexed { index, info ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InstanceLine(
                        state, info,
                        checked = info.def.name in chosen,
                        onCheck = { selected = if (it) selected + info.def.name else selected - info.def.name },
                        showProxyToggle = proxy != null && info.def.type.isServer,
                    )
                }
            }
        }
        if (instances.isNotEmpty()) Text(
            "Start brings up proxies and servers first, then bots and clients once the servers are ready. Stop goes the other way round.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun InstanceLine(state: AppState, info: InstanceInfo, checked: Boolean, onCheck: (Boolean) -> Unit, showProxyToggle: Boolean) {
    val id = info.def.id
    Row(
        Modifier.fillMaxWidth().clickable { state.select(id) }.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheck)
        StateDot(info.state)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(info.def.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(info.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(info.state.name.lowercase(), color = info.state.color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(80.dp))
        Box(Modifier.width(150.dp)) {
            if (showProxyToggle) Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(info.def.proxied, { state.setProxied(id, it) }, enabled = !state.isBusy(id), modifier = Modifier.scale(0.75f))
                Text(if (info.def.proxied) "behind proxy" else "standalone", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.width(170.dp), horizontalArrangement = Arrangement.End) {
            when (info.state) {
                InstanceState.STARTING, InstanceState.RUNNING -> {
                    TextButton(onClick = { state.action(id, "restart") }) { Text("Restart") }
                    TextButton(onClick = { state.action(id, "stop") }) { Text("Stop") }
                }
                InstanceState.STOPPING -> TextButton(onClick = { state.action(id, "kill") }) { Text("Kill") }
                InstanceState.STOPPED, InstanceState.CRASHED -> TextButton(onClick = { state.action(id, "start") }, enabled = !state.isBusy(id)) { Text("Start") }
            }
        }
    }
}

private val SECTIONS = listOf(
    "Proxies" to setOf(InstanceType.Role.PROXY),
    "Databases" to setOf(InstanceType.Role.DATABASE),
    "Servers" to setOf(InstanceType.Role.SERVER, InstanceType.Role.OTHER),
    "Bots" to setOf(InstanceType.Role.BOTS),
    "Clients" to setOf(InstanceType.Role.CLIENT),
)
