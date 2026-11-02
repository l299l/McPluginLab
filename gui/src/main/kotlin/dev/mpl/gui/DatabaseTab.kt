package dev.mpl.gui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.mpl.api.DatabaseInfo
import dev.mpl.api.InstanceInfo
import dev.mpl.api.InstanceState
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun DatabaseTab(state: AppState, info: InstanceInfo) {
    val id = info.def.id
    var connection by remember(id) { mutableStateOf<DatabaseInfo?>(null) }
    var sql by remember(id) { mutableStateOf("SHOW TABLES") }
    var output by remember(id) { mutableStateOf<String?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    var tables by remember(id) { mutableStateOf(emptyList<String>()) }
    var tablesVersion by remember(id) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val running = info.state == InstanceState.RUNNING

    LaunchedEffect(id) { connection = runCatching { state.databaseInfo(id) }.getOrNull() }
    LaunchedEffect(id, running, tablesVersion) {
        tables = if (running) runCatching { state.sql(id, "SHOW TABLES").lines().drop(1).filter { it.isNotBlank() } }.getOrDefault(emptyList()) else emptyList()
    }

    fun run(query: String) = scope.launch {
        runCatching { state.sql(id, query) }
            .onSuccess { output = it; failed = false }
            .onFailure { output = it.message.orEmpty(); failed = true }
        tablesVersion++
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        connection?.let { c ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { copy(c.jdbc) }) { Text("Copy JDBC URL") }
                    TextButton(onClick = { copy(c.password) }) { Text("Copy password") }
                }
                Detail("Host", "${c.host}:${c.port}" + if (c.bindAll) "  (all interfaces)" else "")
                Detail("Database", c.database)
                Detail("User", c.user)
                Detail("Password", c.password)
                Detail("JDBC", c.jdbc)
                Text(
                    "Plugins linked with a database binding get these values written into their config on every start.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tables", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { tablesVersion++ }, enabled = running) { Text("Refresh") }
            }
            if (tables.isEmpty()) Text(
                if (running) "No tables yet." else "Start the database to browse its tables.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            ) else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tables.forEach { table ->
                    FilterChip(selected = sql == selectAll(table), onClick = { sql = selectAll(table); run(sql) }, label = { Text(table, fontFamily = FontFamily.Monospace) })
                }
            }
        }
        Card {
            Text("Run SQL", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(sql, { sql = it }, label = { Text("As ${connection?.user ?: "project user"} on ${connection?.database.orEmpty()}") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = running && sql.isNotBlank(), onClick = { run(sql) }) { Text("Run") }
                OutlinedButton(enabled = running && !state.isBusy("db:$id"), onClick = { state.resetDatabase(id); output = null; failed = false; tablesVersion++ }) { Text("Reset database") }
            }
            if (!running) Text("Start the database to run queries.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            val table = if (failed) null else output?.let(SqlTable::parse)
            if (failed) Text(output.orEmpty().trim(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            else if (table != null) ResultTable(table, Modifier.padding(top = 12.dp))
            else if (output != null) Text("OK", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

private fun selectAll(table: String) = "SELECT * FROM `$table` LIMIT 200"

private fun copy(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
