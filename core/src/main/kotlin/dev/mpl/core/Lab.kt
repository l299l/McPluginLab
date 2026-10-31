package dev.mpl.core

import dev.mpl.api.CreateInstanceRequest
import dev.mpl.api.CreateProjectRequest
import dev.mpl.api.Event
import dev.mpl.api.InstanceDef
import dev.mpl.api.InstanceId
import dev.mpl.api.InstanceInfo
import dev.mpl.api.InstanceState
import dev.mpl.api.InstanceType
import dev.mpl.api.ProjectDef
import dev.mpl.api.PluginLink
import dev.mpl.api.PluginStatus
import dev.mpl.api.ProjectInfo
import dev.mpl.api.UpdateProjectRequest
import dev.mpl.core.bots.SoulFire
import dev.mpl.core.download.FillClient
import dev.mpl.core.files.InstanceFiles
import dev.mpl.core.network.NetworkConfigurator
import dev.mpl.core.plugins.PluginService
import dev.mpl.core.process.LaunchSpec
import dev.mpl.core.process.ManagedProcess
import dev.mpl.core.util.PropertiesFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

class NotFoundException(message: String) : RuntimeException(message)

class Lab(private val workspace: Workspace, private val scope: CoroutineScope) {
    private val fill = FillClient(workspace.jarCache)
    private val soulFire = SoulFire(workspace.jarCache, workspace.soulFireRuntime)
    private val projects = ConcurrentHashMap<String, ProjectDef>()
    private val instances = ConcurrentHashMap<InstanceId, Instance>()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    class Instance(val def: InstanceDef, val process: ManagedProcess)

    val plugins = PluginService(this, scope)

    init {
        workspace.loadProjects().forEach { loaded ->
            val project = if (loaded.forwardingSecret.isBlank()) loaded.copy(forwardingSecret = newSecret()).also(workspace::save) else loaded
            projects[project.name] = project
            workspace.loadInstances(project.name).forEach(::register)
        }
        plugins.start()
    }

    fun projects(): List<ProjectInfo> = projects.values.sortedBy { it.name }.map { it.info() }

    suspend fun createProject(req: CreateProjectRequest): ProjectInfo = withContext(Dispatchers.IO) {
        require(NAME.matches(req.name)) { "Invalid project name '${req.name}' (letters, digits, _ -)" }
        require(!projects.containsKey(req.name)) { "Project '${req.name}' already exists" }
        val dir = req.dir?.takeIf { it.isNotBlank() }?.let { Path(it).absolute().normalize() } ?: workspace.defaultProjectDir(req.name)
        dir.createDirectories()
        val project = ProjectDef(req.name, dir.toString(), forwardingSecret = newSecret())
        workspace.save(project)
        projects[project.name] = project
        _events.emit(Event.ProjectsChanged)
        project.info()
    }

    suspend fun removeProject(name: String, deleteFiles: Boolean) {
        val project = project(name)
        instances.keys.filter { it.project == name }.forEach { remove(it, deleteFiles) }
        projects.remove(name)
        workspace.deleteProject(name)
        if (deleteFiles) {
            val dir = Path(project.dir)
            if (dir.exists() && dir.listDirectoryEntries().isEmpty()) dir.toFile().delete()
        }
        _events.emit(Event.ProjectsChanged)
    }

    suspend fun updateProject(name: String, req: UpdateProjectRequest): ProjectInfo {
        val updated = updateProjectDef(name) { project -> req.onlineMode?.let { project.copy(onlineMode = it) } ?: project }
        syncNetwork(name, reloadProxy = false)
        _events.emit(Event.ProjectsChanged)
        return updated.info()
    }

    suspend fun savePlugin(project: String, link: PluginLink): List<PluginStatus> {
        require(NAME.matches(link.name)) { "Invalid plugin name '${link.name}'" }
        require(link.source.isNotBlank()) { "Plugin source is required" }
        updateProjectDef(project) { def -> def.copy(plugins = def.plugins.filter { it.name != link.name } + link) }
        pluginsChanged(project)
        return pluginStatuses(project)
    }

    suspend fun removePlugin(project: String, name: String) {
        updateProjectDef(project) { def -> def.copy(plugins = def.plugins.filter { it.name != name }) }
        plugins.forget(project, name)
        pluginsChanged(project)
    }

    fun pluginStatuses(project: String): List<PluginStatus> = plugins.statuses(project, project(project).plugins)

    fun deployPlugins(project: String, name: String?): List<PluginStatus> {
        val links = project(project).plugins.filter { name == null || it.name == name }
        if (name != null && links.isEmpty()) throw NotFoundException("No plugin '$name' in $project")
        return links.map { plugins.deploy(project, it) }
    }

    fun files(id: InstanceId) = InstanceFiles(get(id).def.dir)

    fun syncNetwork(project: String, reloadProxy: Boolean = true) {
        val defs = instanceDefs(project)
        NetworkConfigurator.sync(project(project), defs)
        val proxy = NetworkConfigurator.proxyOf(defs) ?: return
        if (reloadProxy && isAlive(proxy.id)) sendCommand(proxy.id, "velocity reload")
    }

    fun instanceDefs(project: String): List<InstanceDef> = instances.values.map { it.def }.filter { it.project == project }

    fun isAlive(id: InstanceId): Boolean = instances[id]?.process?.isAlive == true

    fun note(id: InstanceId, message: String) {
        instances[id]?.process?.log?.append("[mpl] $message")
    }

    fun pluginsChanged(project: String) {
        _events.tryEmit(Event.PluginsChanged(project))
    }

    fun list(project: String? = null): List<InstanceInfo> = instances.values
        .filter { project == null || it.def.project == project }
        .sortedWith(compareBy({ it.def.project }, { !it.def.type.isProxy }, { it.def.name }))
        .map { it.info() }

    fun info(id: InstanceId): InstanceInfo = get(id).info()

    fun get(id: InstanceId): Instance = instances[id] ?: throw NotFoundException("No instance '$id'")

    fun versions(software: String): List<String> = if (software == "soulfire") soulFire.versions() else fill.versions(software)

    suspend fun create(req: CreateInstanceRequest): InstanceInfo = withContext(Dispatchers.IO) {
        val id = InstanceId(req.project, req.name)
        require(NAME.matches(req.name)) { "Invalid name '${req.name}' (letters, digits, _ -)" }
        require(!instances.containsKey(id)) { "Instance '$id' already exists" }
        val project = projects[req.project] ?: createProject(CreateProjectRequest(req.project)).def
        val dir = req.dir?.let { Path(it).absolute().normalize() } ?: Path(project.dir).resolve(req.name)
        dir.createDirectories()

        var def = InstanceDef(
            project = req.project,
            name = req.name,
            type = req.type,
            dir = dir.toString(),
            port = if (req.type == InstanceType.SOULFIRE) null else req.port ?: nextFreePort(req.type),
            memory = req.memory ?: if (req.type.isProxy) "512M" else "2G",
            java = req.java ?: "java",
        )
        val existingJar = req.jar
        def = if (req.type == InstanceType.SOULFIRE) {
            val bots = requireNotNull(req.bots) { "Bot instances need bot settings (target, count)" }
            require(instances.containsKey(InstanceId(req.project, bots.target))) { "No instance '${bots.target}' in ${req.project} to connect bots to" }
            val jar = existingJar?.let { dir.resolve(it) }?.also { require(it.exists()) { "Jar $it not found" } } ?: soulFire.download(req.version)
            def.copy(jar = jar.toString(), version = req.version ?: jar.fileName.toString().removePrefix("SoulFireCLI-").removeSuffix(".jar"), bots = bots)
        } else if (existingJar != null) {
            require(dir.resolve(existingJar).exists()) { "Jar $existingJar not found in $dir" }
            def.copy(jar = existingJar, version = req.version.orEmpty())
        } else {
            val fillProject = req.type.fillProject ?: throw IllegalArgumentException("Type ${req.type} needs an existing jar")
            val version = req.version ?: fill.latestStableVersion(fillProject)
            val jar = fill.download(fillProject, version, req.build)
            jar.path.copyTo(dir.resolve(def.jar), overwrite = true)
            def.copy(version = jar.version, build = jar.build)
        }

        if (req.type.isServer) {
            val eula = dir.resolve("eula.txt")
            if (req.acceptEula) eula.writeText("eula=true\n")
            require(eula.exists()) { "The Minecraft EULA (https://aka.ms/MinecraftEULA) must be accepted" }
            val properties = mapOf("white-list" to "false") + if (req.offlineMode) mapOf("online-mode" to "false") else emptyMap()
            PropertiesFile.update(dir.resolve("server.properties"), properties)
        }

        workspace.save(def)
        register(def)
        syncNetwork(def.project)
        _events.emit(Event.InstancesChanged(def.project))
        info(id)
    }

    fun start(id: InstanceId): InstanceInfo {
        val instance = get(id)
        syncNetwork(id.project, reloadProxy = false)
        instance.process.start(launchSpec(instance.def))
        return instance.info()
    }

    suspend fun stop(id: InstanceId): InstanceInfo {
        get(id).process.stop()
        return info(id)
    }

    fun requestStop(id: InstanceId): InstanceInfo {
        get(id).process.requestStop()
        return info(id)
    }

    fun requestRestart(id: InstanceId): InstanceInfo {
        val process = get(id).process
        process.requestStop()
        scope.launch {
            runCatching { restart(id) }.onFailure { process.log.append("[mpl] restart failed: ${it.message}") }
        }
        return info(id)
    }

    suspend fun kill(id: InstanceId): InstanceInfo {
        get(id).process.kill()
        return info(id)
    }

    suspend fun restart(id: InstanceId): InstanceInfo {
        stop(id)
        return start(id)
    }

    fun sendCommand(id: InstanceId, command: String) = get(id).process.sendCommand(command)

    suspend fun remove(id: InstanceId, deleteFiles: Boolean) {
        val instance = get(id)
        instance.process.stop()
        instances.remove(id)
        workspace.deleteInstance(id)
        if (deleteFiles) {
            val dir = Path(instance.def.dir)
            val projectDir = Path(project(id.project).dir)
            require(dir.startsWith(projectDir) && dir != projectDir) { "Refusing to delete $dir: it is outside the project folder" }
            withContext(Dispatchers.IO) { dir.toFile().deleteRecursively() }
        }
        if (projects.containsKey(id.project)) syncNetwork(id.project)
        _events.emit(Event.InstancesChanged(id.project))
    }

    suspend fun stopAll() = coroutineScope {
        instances.values.map { async { it.process.stop() } }.awaitAll()
    }

    private fun project(name: String) = projects[name] ?: throw NotFoundException("No project '$name'")

    private fun updateProjectDef(name: String, change: (ProjectDef) -> ProjectDef): ProjectDef {
        val updated = projects.compute(name) { _, current -> change(current ?: throw NotFoundException("No project '$name'")) }!!
        workspace.save(updated)
        return updated
    }

    private fun newSecret() = ByteArray(16).also(SecureRandom()::nextBytes).toHexString()

    private fun register(def: InstanceDef) {
        val process = ManagedProcess(def.id.toString(), workspace.instanceLog(def.id), scope)
        instances[def.id] = Instance(def, process)
        process.state.drop(1)
            .onEach { _events.emit(Event.StateChanged(def.id, it, process.exitCode)) }
            .launchIn(scope)
    }

    private fun launchSpec(def: InstanceDef): LaunchSpec {
        val dir = Path(def.dir)
        if (def.type == InstanceType.SOULFIRE) return botLaunchSpec(def)
        val portArgs = def.port?.let { port ->
            when (def.type) {
                InstanceType.PAPER, InstanceType.FOLIA -> {
                    PropertiesFile.update(dir.resolve("server.properties"), mapOf("server-port" to port.toString()))
                    emptyList()
                }
                InstanceType.VELOCITY -> listOf("--port", port.toString())
                else -> emptyList()
            }
        }.orEmpty()

        val command = buildList {
            add(def.java)
            add("-Xmx${def.memory}")
            addAll(def.jvmArgs)
            add("-jar")
            add(def.jar)
            if (def.type == InstanceType.PAPER || def.type == InstanceType.FOLIA) add("--nogui")
            addAll(portArgs)
            addAll(def.args)
        }
        return LaunchSpec(command, dir, def.env, def.effectiveStopCommand, Regex(def.effectiveReadyPattern))
    }

    private fun botLaunchSpec(def: InstanceDef): LaunchSpec {
        val bots = requireNotNull(def.bots) { "${def.id} has no bot settings" }
        val target = get(InstanceId(def.project, bots.target)).def
        val port = requireNotNull(target.port) { "${target.id} has no port" }
        val version = bots.protocolVersion
            ?: target.version.takeIf { target.type.isServer }
            ?: instanceDefs(def.project).firstOrNull { it.type.isServer }?.version
            ?: throw IllegalArgumentException("Set a protocol version for ${def.id}")
        val command = soulFire.command(def, bots, "127.0.0.1:$port", version)
        return LaunchSpec(command, Path(def.dir), def.env, def.effectiveStopCommand, Regex(def.effectiveReadyPattern))
    }

    private fun nextFreePort(type: InstanceType): Int {
        val used = instances.values.mapNotNull { it.def.port }.toSet()
        return generateSequence(if (type.isProxy) 25577 else 25565) { it + 1 }.first { it !in used }
    }

    private fun ProjectDef.info(): ProjectInfo {
        val own = instances.values.filter { it.def.project == name }
        return ProjectInfo(this, own.size, own.count { it.process.state.value == InstanceState.RUNNING })
    }

    private fun Instance.info() = InstanceInfo(def, process.state.value, process.pid, process.exitCode, process.log.nextSeq)

    private companion object {
        val NAME = Regex("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,31}$")
    }
}
