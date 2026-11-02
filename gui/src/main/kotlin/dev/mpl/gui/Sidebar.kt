package dev.mpl.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mpl.api.InstanceInfo
import dev.mpl.api.InstanceState
import dev.mpl.api.InstanceType

@Composable
fun Sidebar(state: AppState, onCreate: () -> Unit, modifier: Modifier = Modifier) {
    val proxies = state.projectInstances.filter { it.def.type.isProxy }
    val bots = state.projectInstances.filter { it.def.type == InstanceType.SOULFIRE }
    val clients = state.projectInstances.filter { it.def.type == InstanceType.CLIENT }
    val databases = state.projectInstances.filter { it.def.type.isDatabase }
    val servers = state.projectInstances - (proxies + databases + bots + clients).toSet()
    val project = state.project ?: return

    Column(modifier.background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
        TextButton(onClick = { state.openProject = null }) { Text("← Projects") }
        Text(project.def.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
        Text(
            project.def.dir.shortPath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp).clickable { state.openFolder(project.def.dir) },
        )
        Spacer(Modifier.size(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val running = state.projectInstances.count { it.state == InstanceState.RUNNING }
            item { NavRow("Overview", "$running of ${state.projectInstances.size} running", state.page == AppState.ProjectPage.OVERVIEW) { state.page = AppState.ProjectPage.OVERVIEW } }
            item { NavRow("Plugins", "${state.plugins.size} linked", state.page == AppState.ProjectPage.PLUGINS) { state.page = AppState.ProjectPage.PLUGINS } }
            item { NavRow("Network", proxies.firstOrNull()?.def?.type?.name?.lowercase() ?: "no proxy", state.page == AppState.ProjectPage.NETWORK) { state.page = AppState.ProjectPage.NETWORK } }
            section("Proxies", proxies, state)
            section("Databases", databases, state)
            section("Servers", servers, state)
            section("Bots", bots, state)
            section("Clients", clients, state)
        }
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Add instance") }
    }
}

private fun LazyListScope.section(title: String, rows: List<InstanceInfo>, state: AppState) {
    if (rows.isEmpty()) return
    item { SectionLabel(title, Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp)) }
    items(rows, key = { it.def.id.toString() }) { info ->
        InstanceRow(info, selected = state.page == AppState.ProjectPage.INSTANCE && info.def.id == state.selected, onClick = { state.select(info.def.id) })
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(background).clickable(onClick = onClick).padding(10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InstanceRow(info: InstanceInfo, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(background).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateDot(info.state)
        Spacer(Modifier.size(10.dp))
        Column {
            Text(info.def.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(info.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
