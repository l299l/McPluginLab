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
    var creating by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Sidebar(state, onCreate = { creating = true }, modifier = Modifier.width(272.dp).fillMaxHeight())
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f)) {
                val selected = state.selectedInstance
                if (selected != null) InstancePane(state, selected) else EmptyState()
            }
            SnackbarHost(state.snackbar)
        }
    }

    if (creating) CreateInstanceDialog(state, onClose = { creating = false })
}

@Composable
private fun EmptyState() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text("Create a server or proxy to get started", color = MaterialTheme.colorScheme.onSurfaceVariant)
}
