package dev.mpl.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mpl.api.InstanceInfo

@Composable
fun Sidebar(state: AppState, onCreate: () -> Unit, modifier: Modifier = Modifier) {
    val (proxies, servers) = state.instances.partition { it.def.type.isProxy }

    Column(modifier.background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
        Text("McPluginLab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(8.dp))
        Spacer(Modifier.size(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            section("Proxies", proxies, state)
            section("Servers", servers, state)
        }
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("New instance") }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, rows: List<InstanceInfo>, state: AppState) {
    if (rows.isEmpty()) return
    item {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
        )
    }
    items(rows, key = { it.def.name }) { info ->
        InstanceRow(info, selected = info.def.name == state.selected, onClick = { state.selected = info.def.name })
    }
}

@Composable
private fun InstanceRow(info: InstanceInfo, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(background).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(info.state.color))
        Spacer(Modifier.size(10.dp))
        Column {
            Text(info.def.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${info.def.type.name.lowercase()} ${info.def.version}" + (info.def.port?.let { "  ·  :$it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
