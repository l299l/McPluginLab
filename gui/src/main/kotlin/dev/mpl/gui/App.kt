package dev.mpl.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun App(state: AppState) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f)) {
            val project = state.project
            if (project == null) HomeScreen(state) else ProjectScreen(state, project.def.name)
        }
        SnackbarHost(state.snackbar)
    }
}

@Composable
private fun ProjectScreen(state: AppState, project: String) {
    var creating by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize()) {
        Sidebar(state, onCreate = { creating = true }, modifier = Modifier.width(272.dp).fillMaxHeight())
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (state.page) {
                AppState.ProjectPage.OVERVIEW -> OverviewPage(state)
                AppState.ProjectPage.PLUGINS -> PluginsPage(state)
                AppState.ProjectPage.NETWORK -> NetworkPage(state)
                AppState.ProjectPage.INSTANCE -> state.selectedInstance?.let { InstancePane(state, it) } ?: EmptyState()
            }
        }
    }

    if (creating) CreateInstanceDialog(state, project, onClose = { creating = false })
}

@Composable
private fun EmptyState() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text("Add a server or proxy to this project", color = MaterialTheme.colorScheme.onSurfaceVariant)
}
