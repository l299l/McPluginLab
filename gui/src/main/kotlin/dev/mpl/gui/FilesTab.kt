package dev.mpl.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mpl.api.FileEntry
import dev.mpl.api.InstanceId
import dev.mpl.gui.editor.ConfigEditor
import javax.swing.SwingUtilities
import kotlinx.coroutines.launch

@Composable
fun FilesTab(state: AppState, id: InstanceId) {
    val scope = rememberCoroutineScope()
    var dir by remember(id) { mutableStateOf("") }
    var entries by remember(id) { mutableStateOf(emptyList<FileEntry>()) }
    var openPath by remember(id) { mutableStateOf<String?>(null) }
    var saved by remember(id) { mutableStateOf("") }
    var current by remember(id) { mutableStateOf("") }

    fun save() = scope.launch {
        val path = openPath ?: return@launch
        val text = current
        runCatching { state.writeFile(id, path, text) }.onSuccess { saved = text }.onFailure(state::report)
    }

    val editor = remember(id) { ConfigEditor(onChange = { SwingUtilities.invokeLater { current = it } }, onSave = { save() }) }

    fun open(path: String) = scope.launch {
        runCatching { state.readFile(id, path) }
            .onSuccess { text ->
                openPath = path
                saved = text
                current = text
                editor.show(path, text)
            }
            .onFailure(state::report)
    }

    LaunchedEffect(id, dir) {
        runCatching { state.files(id, dir) }.onSuccess { entries = it }.onFailure(state::report)
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(280.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
            Text(
                "/" + dir,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(12.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                if (dir.isNotEmpty()) item { FileRow("..", directory = true) { dir = dir.substringBeforeLast('/', "") } }
                items(entries, key = { it.path }) { entry ->
                    FileRow(entry.path.substringAfterLast('/'), entry.directory, selected = entry.path == openPath) {
                        if (entry.directory) dir = entry.path else open(entry.path)
                    }
                }
            }
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val path = openPath
            if (path == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Select a file to edit", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(path + if (current != saved) "  •" else "", fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    TextButton(onClick = { open(path) }) { Text("Reload") }
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = { save() }, enabled = current != saved) { Text("Save") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (path != null && openDialogs.intValue == 0) SwingPanel(factory = { editor.component }, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun FileRow(name: String, directory: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface
    Text(
        if (directory) "▸ $name" else "   $name",
        style = MaterialTheme.typography.bodyMedium,
        color = if (directory) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().background(background).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
