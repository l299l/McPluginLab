package dev.mpl.gui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mpl.api.CreateInstanceRequest
import dev.mpl.api.CreateProjectRequest
import dev.mpl.api.DatabaseInfo
import dev.mpl.api.DaemonConnection
import dev.mpl.api.FileEntry
import dev.mpl.api.InstanceId
import dev.mpl.api.InstanceInfo
import dev.mpl.api.MplClient
import dev.mpl.api.MplException
import dev.mpl.api.PluginLink
import dev.mpl.api.PluginStatus
import dev.mpl.api.ProjectInfo
import dev.mpl.api.UpdateInstanceRequest
import dev.mpl.api.UpdateProjectRequest
import dev.mpl.gui.terminal.ConsoleTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppState(initialClient: MplClient, private val scope: CoroutineScope) {
    var client = initialClient
        private set
    var connection by mutableIntStateOf(0)
        private set
    var projects by mutableStateOf<List<ProjectInfo>>(emptyList())
        private set
    var instances by mutableStateOf<List<InstanceInfo>>(emptyList())
        private set
    var openProject by mutableStateOf<String?>(null)
    var selected by mutableStateOf<InstanceId?>(null)
    var page by mutableStateOf(ProjectPage.OVERVIEW)
    var plugins by mutableStateOf<List<PluginStatus>>(emptyList())
        private set
    var busy by mutableStateOf<Set<String>>(emptySet())
        private set
    val snackbar = SnackbarHostState()
    var closeRequested by mutableStateOf(false)
    var quitting by mutableStateOf(false)
        private set

    private val consoles = mutableMapOf<InstanceId, ConsoleTerminal>()

    val project: ProjectInfo? get() = projects.firstOrNull { it.def.name == openProject }
    val projectInstances: List<InstanceInfo> get() = instances.filter { it.def.project == openProject }
    val selectedInstance: InstanceInfo? get() = projectInstances.firstOrNull { it.def.id == selected }

    init {
        scope.launch { watchEvents() }
        if (client.isOutdated) offerDaemonRestart()
    }

    fun open(project: String) {
        openProject = project
        page = ProjectPage.OVERVIEW
        selected = projectInstances.firstOrNull()?.def?.id
        launchFor(project) {}
    }

    fun select(id: InstanceId) {
        selected = id
        page = ProjectPage.INSTANCE
    }

    fun savePlugin(link: PluginLink, onDone: () -> Unit) = launchFor("plugin:${link.name}") {
        client.savePlugin(openProject!!, link)
        onDone()
    }

    fun removePlugin(name: String) = launchFor("plugin:$name") { client.removePlugin(openProject!!, name) }

    fun deploy(name: String?) = launchFor("plugin:${name ?: "*"}") {
        client.deploy(openProject!!, name).firstOrNull { it.error != null }?.let { throw MplException(it.error!!) }
    }

    fun group(action: String, names: List<String> = emptyList()) {
        val project = openProject ?: return
        launchFor("group:$project") { client.group(project, action, names) }
    }

    fun setProxied(id: InstanceId, proxied: Boolean) = launchFor(id.toString()) { client.updateInstance(id, UpdateInstanceRequest(proxied = proxied)) }

    fun setOnlineMode(project: String, online: Boolean) = launchFor(project) { client.updateProject(project, UpdateProjectRequest(online)) }

    fun syncNetwork(project: String) = launchFor("network:$project") { client.syncNetwork(project) }

    suspend fun databaseInfo(id: InstanceId): DatabaseInfo = client.databaseInfo(id)

    suspend fun sql(id: InstanceId, sql: String): String = client.sql(id, sql).output

    fun resetDatabase(id: InstanceId) = launchFor("db:$id") { client.resetDatabase(id) }

    suspend fun files(id: InstanceId, path: String): List<FileEntry> = client.files(id, path)

    suspend fun readFile(id: InstanceId, path: String): String = client.readFile(id, path).text

    suspend fun writeFile(id: InstanceId, path: String, text: String) = client.writeFile(id, path, text)

    val aliveInstances: List<InstanceInfo> get() = instances.filter { it.state.isAlive }

    fun requestClose(exit: () -> Unit) {
        if (aliveInstances.isEmpty()) quit(exit) else closeRequested = true
    }

    fun quit(exit: () -> Unit) {
        quitting = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { DaemonConnection.shutdown(client) } }
            exit()
        }
    }

    private fun offerDaemonRestart() = scope.launch {
        val result = snackbar.showSnackbar(
            "The daemon runs an older build of McPluginLab. Restarting it stops the running instances.",
            actionLabel = "Restart daemon",
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) launchFor("daemon") {
            val old = client
            useClient(withContext(Dispatchers.IO) {
                DaemonConnection.shutdown(old)
                DaemonConnection.connect(restartOutdated = false)
            })
        }
    }

    private fun useClient(fresh: MplClient) {
        if (fresh.info.token == client.info.token) return
        consoles.values.forEach { it.close() }
        consoles.clear()
        client = fresh
        connection++
    }

    fun report(e: Throwable) {
        scope.launch { snackbar.showSnackbar(e.message ?: e.toString()) }
    }

    fun console(id: InstanceId): ConsoleTerminal = consoles.getOrPut(id) { ConsoleTerminal(client, id) }

    fun action(id: InstanceId, action: String) = launchFor(id.toString()) { client.action(id, action, wait = false) }

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

    enum class ProjectPage { OVERVIEW, INSTANCE, PLUGINS, NETWORK }

    fun isBusy(key: Any) = key.toString() in busy

    private suspend fun refresh() {
        projects = client.projects()
        instances = client.instances()
        if (openProject != null && project == null) openProject = null
        if (selectedInstance == null) selected = projectInstances.firstOrNull()?.def?.id
        plugins = openProject?.let { client.plugins(it) }.orEmpty()
    }

    private suspend fun watchEvents() {
        while (scope.isActive) {
            runCatching {
                refresh()
                client.events { for (frame in incoming) refresh() }
            }
            delay(1_000)
            if (!quitting) runCatching { useClient(withContext(Dispatchers.IO) { DaemonConnection.connect(restartOutdated = false) }) }
        }
    }

    private fun launchFor(key: String, block: suspend () -> Unit) = scope.launch {
        busy = busy + key
        try {
            block()
            refresh()
        } catch (e: Exception) {
            report(e)
        } finally {
            busy = busy - key
        }
    }

}
