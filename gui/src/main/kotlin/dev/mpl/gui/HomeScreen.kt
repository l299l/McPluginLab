package dev.mpl.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mpl.api.InstanceState
import dev.mpl.api.MplHome
import dev.mpl.api.ProjectInfo

@Composable
fun HomeScreen(state: AppState) {
    var creating by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("McPluginLab", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("Projects", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { creating = true }) { Text("New project") }
        }
        Spacer(Modifier.size(24.dp))

        if (state.projects.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No projects yet. A project groups the servers and proxies you test a plugin on.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                GridCells.Adaptive(340.dp),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.projects, key = { it.def.name }) { project ->
                    ProjectCard(state, project, onRemove = { removing = project.def.name })
                }
            }
        }

        Text(
            "State ${MplHome.path.toString().shortPath}   ·   Jar cache ${MplHome.cache.toString().shortPath}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }

    if (creating) CreateProjectDialog(state, onClose = { creating = false })
    removing?.let { name ->
        RemoveDialog(
            title = "Remove project $name?",
            detail = "Stops and removes all of its servers.",
            deleteLabel = "Also delete server folders",
            onClose = { removing = null },
            onConfirm = { deleteFiles ->
                removing = null
                state.removeProject(name, deleteFiles)
            },
        )
    }
}

@Composable
private fun ProjectCard(state: AppState, project: ProjectInfo, onRemove: () -> Unit) {
    val name = project.def.name
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable { state.open(name) }
            .padding(18.dp),
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(4.dp))
        Text(
            project.def.dir.shortPath,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val runningState = if (project.running > 0) InstanceState.RUNNING else InstanceState.STOPPED
            Box(Modifier.size(8.dp).clip(CircleShape).background(runningState.color))
            Spacer(Modifier.size(8.dp))
            Text(
                "${project.instances} ${if (project.instances == 1) "instance" else "instances"}  ·  ${project.running} running",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { state.openFolder(project.def.dir) }) { Text("Folder") }
            TextButton(onClick = onRemove, enabled = !state.isBusy(name)) { Text("Remove") }
        }
    }
}
