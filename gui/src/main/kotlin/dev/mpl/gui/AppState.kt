package dev.mpl.gui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mpl.api.CreateInstanceRequest
import dev.mpl.api.CreateProjectRequest
import dev.mpl.api.DaemonConnection
import dev.mpl.api.InstanceId
import dev.mpl.api.InstanceInfo
import dev.mpl.api.MplClient
import dev.mpl.api.ProjectInfo
import dev.mpl.gui.terminal.ConsoleTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppState(val client: MplClient, private val scope: CoroutineScope) {
    var projects by mutableStateOf<List<ProjectInfo>>(emptyList())
        private set
    var instances by mutableStateOf<List<InstanceInfo>>(emptyList())
        private set
    var openProject by mutableStateOf<String?>(null)
    var selected by mutableStateOf<InstanceId?>(null)
    var busy by mutableStateOf<Set<String>>(emptySet())
        private set
    val snackbar = SnackbarHostState()

    private val consoles = mutableMapOf<InstanceId, ConsoleTerminal>()

    val project: ProjectInfo? get() = projects.firstOrNull { it.def.name == openProject }
    val projectInstances: List<InstanceInfo> get() = instances.filter { it.def.project == openProject }
    val selectedInstance: InstanceInfo? get() = projectInstances.firstOrNull { it.def.id == selected }

    init {
        scope.launch { watchEvents() }
    }

    fun open(project: String) {
        openProject = project
        selected = projectInstances.firstOrNull()?.def?.id
    }

    fun console(id: InstanceId): ConsoleTerminal = consoles.getOrPut(id) { ConsoleTerminal(client, id) }

    fun action(id: InstanceId, action: String) = launchFor(id.toString()) { client.action(id, action) }

    fun openInTerminal(id: InstanceId) = launchFor(id.toString()) { DaemonConnection.openInTerminal(id) }

    fun openFolder(path: String) = launchFor(path) { withContext(Dispatchers.IO) { ProcessBuilder("xdg-open", path).start() } }

    fun createProject(request: CreateProjectRequest, onDone: () -> Unit) = launchFor(request.name) {
        client.createProject(request)
        onDone()
    }

    fun removeProject(name: String, deleteFiles: Boolean) = launchFor(name) {
        client.removeProject(name, deleteFiles)
        consoles.keys.filter { it.project == name }.forEach { consoles.remove(it)?.close() }
        if (openProject == name) openProject = null
    }

    fun createInstance(request: CreateInstanceRequest, onDone: () -> Unit) = launchFor(request.name) {
        val created = client.create(request)
        refresh()
        selected = created.def.id
        onDone()
    }

    fun removeInstance(id: InstanceId, deleteFiles: Boolean) = launchFor(id.toString()) {
        client.remove(id, deleteFiles)
        consoles.remove(id)?.close()
        if (selected == id) selected = null
    }

    suspend fun versions(project: String): List<String> = runCatching { client.versions(project).versions }.getOrElse {
        report(it)
        emptyList()
    }

    fun isBusy(key: Any) = key.toString() in busy

    private suspend fun refresh() {
        projects = client.projects()
        instances = client.instances()
        if (openProject != null && project == null) openProject = null
        if (selectedInstance == null) selected = projectInstances.firstOrNull()?.def?.id
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

    private fun launchFor(key: String, block: suspend () -> Unit) = scope.launch {
        busy = busy + key
        try {
            block()
            refresh()
        } catch (e: Exception) {
            scope.launch { report(e) }
        } finally {
            busy = busy - key
        }
    }

    private suspend fun report(e: Throwable) {
        snackbar.showSnackbar(e.message ?: e.toString())
    }
}
