package dev.mpl.gui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mpl.api.CreateInstanceRequest
import dev.mpl.api.DaemonConnection
import dev.mpl.api.InstanceInfo
import dev.mpl.api.MplClient
import dev.mpl.gui.terminal.ConsoleTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppState(val client: MplClient, private val scope: CoroutineScope) {
    var instances by mutableStateOf<List<InstanceInfo>>(emptyList())
        private set
    var selected by mutableStateOf<String?>(null)
    var busy by mutableStateOf<Set<String>>(emptySet())
        private set
    val snackbar = SnackbarHostState()

    private val consoles = mutableMapOf<String, ConsoleTerminal>()

    val selectedInstance: InstanceInfo? get() = instances.firstOrNull { it.def.name == selected }

    init {
        scope.launch { watchEvents() }
    }

    fun console(name: String): ConsoleTerminal = consoles.getOrPut(name) { ConsoleTerminal(client, name) }

    fun action(name: String, action: String) = launchFor(name) { client.action(name, action) }

    fun openInTerminal(name: String) = launchFor(name) { DaemonConnection.openInTerminal(name) }

    fun create(request: CreateInstanceRequest, onDone: () -> Unit) = launchFor(request.name) {
        client.create(request)
        selected = request.name
        onDone()
    }

    fun remove(name: String, deleteFiles: Boolean) = launchFor(name) {
        client.remove(name, deleteFiles)
        consoles.remove(name)?.close()
        if (selected == name) selected = null
    }

    suspend fun versions(project: String): List<String> = runCatching { client.versions(project).versions }.getOrElse {
        report(it)
        emptyList()
    }

    private suspend fun refresh() {
        instances = client.instances()
        if (selected == null || instances.none { it.def.name == selected }) selected = instances.firstOrNull()?.def?.name
    }

    private suspend fun watchEvents() {
        while (scope.isActive) {
            runCatching {
                refresh()
                client.events { for (frame in incoming) refresh() }
            }
            delay(1_000)
        }
    }

    private fun launchFor(name: String, block: suspend () -> Unit) = scope.launch {
        busy = busy + name
        try {
            block()
            refresh()
        } catch (e: Exception) {
            scope.launch { report(e) }
        } finally {
            busy = busy - name
        }
    }

    private suspend fun report(e: Throwable) {
        snackbar.showSnackbar(e.message ?: e.toString())
    }
}
