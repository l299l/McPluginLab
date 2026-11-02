package dev.mpl.core

import dev.mpl.api.CreateInstanceRequest
import dev.mpl.api.CreateProjectRequest
import dev.mpl.api.Event
import dev.mpl.api.InstanceDef
import dev.mpl.api.InstanceId
import dev.mpl.api.InstanceInfo
import dev.mpl.api.InstanceState
import dev.mpl.api.InstanceType
import dev.mpl.api.Network
import dev.mpl.api.ProjectDef
import dev.mpl.api.PluginLink
import dev.mpl.api.PluginStatus
import dev.mpl.api.ProjectInfo
import dev.mpl.api.UpdateInstanceRequest
import dev.mpl.api.UpdateProjectRequest
import dev.mpl.core.bots.SoulFire
import dev.mpl.core.database.DatabaseBinaries
import dev.mpl.core.database.DatabaseConfigurator
import dev.mpl.core.database.DatabaseService
import dev.mpl.api.DatabaseInfo
import dev.mpl.api.DatabaseSettings
import dev.mpl.core.download.BungeeCordSource
import dev.mpl.core.download.Downloader
import dev.mpl.core.download.FillSource
import dev.mpl.core.download.PurpurSource
import dev.mpl.core.download.ServerSource
import dev.mpl.core.download.SpigotSource
import dev.mpl.core.java.JavaRequirements
import dev.mpl.core.java.JdkManager
import dev.mpl.core.launcher.MinecraftLauncher
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.absolute
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

class NotFoundException(message: String) : RuntimeException(message)

class Lab(private val workspace: Workspace, private val scope: CoroutineScope) {
    private val downloader = Downloader()
    private val jdks = JdkManager(workspace.jdkDir, downloader)
    private val soulFire = SoulFire(workspace.jarCache, workspace.soulFireRuntime)
    private val launcher = MinecraftLauncher(workspace.minecraftDir, downloader)
    private val databaseBinaries = DatabaseBinaries(workspace.databaseDir, downloader)
    private val databases = DatabaseService(databaseBinaries)
    private val sources: Map<String, ServerSource> = buildMap {
        listOf("paper", "folia", "velocity", "waterfall").forEach { put(it, FillSource(it, downloader, workspace.jarCache)) }
        put("purpur", PurpurSource(downloader, workspace.jarCache))
        put("bungeecord", BungeeCordSource(downloader, workspace.jarCache))
        put("spigot", SpigotSource(downloader, workspace.jarCache, jdks))
    }
    private val projects = ConcurrentHashMap<String, ProjectDef>()
    private val instances = ConcurrentHashMap<InstanceId, Instance>()
    private val configLocks = ConcurrentHashMap<String, Any>()
    private val pendingRestarts = ConcurrentHashMap.newKeySet<InstanceId>()
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
        req.dir?.let { moveProject(name, Path(it).absolute().normalize()) }
        val updated = updateProjectDef(name) { project -> req.onlineMode?.let { project.copy(onlineMode = it) } ?: project }
        syncNetwork(name, reloadProxy = false)
        _events.emit(Event.ProjectsChanged)
        return updated.info()
    }

    @OptIn(ExperimentalPathApi::class)
    private suspend fun moveProject(name: String, target: Path) = withContext(Dispatchers.IO) {
        val from = Path(project(name).dir)
        if (from == target) return@withContext
        require(instanceDefs(name).none { isAlive(it.id) }) { "Stop all instances of $name before moving it" }
        require(!target.startsWith(from)) { "Cannot move $name into its own folder" }
        require(!target.exists() || target.listDirectoryEntries().isEmpty()) { "$target is not empty" }
        target.deleteIfExists()
        target.parent.createDirectories()
        try {
            Files.move(from, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            from.copyToRecursively(target, followLinks = false, overwrite = false)
            from.deleteRecursively()
        }
        instances.values.filter { it.def.project == name && Path(it.def.dir).startsWith(from) }.forEach { instance ->
            val def = instance.def.copy(dir = target.resolve(from.relativize(Path(instance.def.dir))).toString())
            workspace.save(def)
            instances[def.id] = Instance(def, instance.process)
        }
        updateProjectDef(name) { it.copy(dir = target.toString()) }
        _events.emit(Event.InstancesChanged(name))
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
        synchronized(configLock(project)) {
            NetworkConfigurator.sync(project(project), defs)
            DatabaseConfigurator.sync(project(project), defs, databases::info)
        }
        val proxy = Network.proxyOf(defs) ?: return
        if (reloadProxy && isAlive(proxy.id)) sendCommand(proxy.id, NetworkConfigurator.reloadCommand(proxy))
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

    fun versions(software: String): List<String> = when (software) {
        "soulfire" -> soulFire.versions()
        "minecraft" -> launcher.releases()
        "mariadb" -> databaseBinaries.versions()
        else -> source(software).versions()
    }

    fun javaInstallations() = jdks.installed().sortedBy { it.feature }.map { "${it.feature}: ${it.home}" }

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
            port = if (req.type.isServer || req.type.isProxy || req.type.isDatabase) req.port ?: nextFreePort(req.type) else null,
            memory = req.memory ?: if (req.type.isProxy || req.type.isDatabase) "512M" else "2G",
            java = req.java ?: InstanceDef.AUTO_JAVA,
            proxied = req.proxied,
        )
        val existingJar = req.jar
        def = if (req.type.isDatabase) {
            val name = req.databaseName ?: req.project.replace(Regex("[^A-Za-z0-9_]"), "_").take(48)
            require(DatabaseService.IDENTIFIER.matches(name)) { "Invalid database name '$name' (letters, digits, _)" }
            val version = req.version ?: databaseBinaries.defaultVersion()
            databaseBinaries.resolve(version) { println("[$id] $it") }
            def.copy(jar = "", version = version, database = DatabaseSettings(name, password = newSecret(), bindAll = req.bindAll))
        } else if (req.type == InstanceType.SOULFIRE) {
            val bots = requireNotNull(req.bots) { "Bot instances need bot settings (target, count)" }
            require(instances.containsKey(InstanceId(req.project, bots.target))) { "No instance '${bots.target}' in ${req.project} to connect bots to" }
            val jar = existingJar?.let { dir.resolve(it) }?.also { require(it.exists()) { "Jar $it not found" } } ?: soulFire.download(req.version)
            val version = req.version ?: jar.fileName.toString().removePrefix("SoulFireCLI-").removeSuffix(".jar")
            def.copy(jar = jar.toString(), version = version, bots = bots, javaVersion = JavaRequirements.forInstance(req.type, version))
        } else if (req.type == InstanceType.CLIENT) {
            val client = requireNotNull(req.client) { "Client instances need a username" }
            require(USERNAME.matches(client.username)) { "Invalid Minecraft username '${client.username}'" }
            client.target?.let { require(instances.containsKey(InstanceId(req.project, it))) { "No instance '$it' in ${req.project} to join" } }
            val version = launcher.prepare(req.version ?: launcher.latestRelease()) { println("[$id] $it") }
            def.copy(jar = "", version = version.id, client = client, javaVersion = version.javaVersion?.majorVersion ?: 8)
        } else if (existingJar != null) {
            require(dir.resolve(existingJar).exists()) { "Jar $existingJar not found in $dir" }
            val version = req.version.orEmpty()
            def.copy(jar = existingJar, version = version, javaVersion = version.takeIf { it.isNotBlank() }?.let { JavaRequirements.forInstance(req.type, it) })
        } else {
            val software = req.type.software ?: throw IllegalArgumentException("Type ${req.type} needs an existing jar")
            val jar = source(software).download(req.version, req.build) { println("[$id] $it") }
            jar.path.copyTo(dir.resolve(def.jar), overwrite = true)
            def.copy(version = jar.version, build = jar.build, javaVersion = jar.javaVersion ?: JavaRequirements.forInstance(req.type, jar.version))
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

    suspend fun updateInstance(id: InstanceId, req: UpdateInstanceRequest): InstanceInfo {
        req.port?.let { port -> require(instances.values.none { it.def.id != id && it.def.port == port }) { "Port $port is already used" } }
        val current = get(id)
        val def = current.def.copy(
            proxied = req.proxied ?: current.def.proxied,
            port = req.port ?: current.def.port,
            memory = req.memory ?: current.def.memory,
            jvmArgs = req.jvmArgs ?: current.def.jvmArgs,
        )
        workspace.save(def)
        instances[id] = Instance(def, current.process)
        withContext(Dispatchers.IO) { syncNetwork(id.project) }
        _events.emit(Event.InstancesChanged(id.project))
        return info(id)
    }

    suspend fun startGroup(project: String, names: List<String>): List<InstanceInfo> {
        val targets = group(project, names)
        val databases = targets.filter { it.def.type.isDatabase }
        val late = targets.filter { it.def.type.isPlayer }
        val early = targets - databases.toSet() - late.toSet()
        listOf(databases, early, late).forEach { phase ->
            coroutineScope {
                phase.filter { !it.process.isAlive }.map { instance -> async { startAndAwaitReady(instance) } }.awaitAll()
            }
        }
        return list(project)
    }

    suspend fun stopGroup(project: String, names: List<String>): List<InstanceInfo> {
        val targets = group(project, names)
        val phases = listOf(
            targets.filter { it.def.type.isPlayer },
            targets.filter { it.def.type.isProxy },
            targets.filter { !it.def.type.isPlayer && !it.def.type.isProxy && !it.def.type.isDatabase },
            targets.filter { it.def.type.isDatabase },
        )
        phases.forEach { phase -> coroutineScope { phase.map { async { stop(it.def.id) } }.awaitAll() } }
        return list(project)
    }

    suspend fun restartGroup(project: String, names: List<String>): List<InstanceInfo> {
        stopGroup(project, names)
        return startGroup(project, names)
    }

    private fun group(project: String, names: List<String>): List<Instance> {
        project(project)
        names.forEach { get(InstanceId(project, it)) }
        return instances.values.filter { it.def.project == project && (names.isEmpty() || it.def.name in names) }
    }

    private suspend fun startAndAwaitReady(instance: Instance) {
        runCatching { start(instance.def.id) }.onFailure {
            note(instance.def.id, "start failed: ${it.message}")
            return
        }
        withTimeoutOrNull(READY_TIMEOUT_MS) { instance.process.state.first { it != InstanceState.STARTING } }
        if (instance.def.type.isDatabase) bootstrapDatabase(instance)
    }

    /** Creates the project database and user once the server accepts connections. Safe to repeat. */
    private fun bootstrapDatabase(instance: Instance) {
        if (instance.process.state.value != InstanceState.RUNNING) return
        runCatching { databases.bootstrap(instance.def) }
            .onSuccess { note(instance.def.id, "database ${instance.def.database?.database} is ready") }
            .onFailure { note(instance.def.id, "database setup failed: ${it.message}") }
    }

    fun databaseInfo(id: InstanceId): DatabaseInfo = databases.info(database(id))

    suspend fun sql(id: InstanceId, sql: String, asRoot: Boolean): String = withContext(Dispatchers.IO) {
        val def = database(id)
        check(get(id).process.state.value == InstanceState.RUNNING) { "$id is not running" }
        databases.execute(def, sql, asRoot)
    }

    suspend fun resetDatabase(id: InstanceId) = withContext(Dispatchers.IO) {
        val def = database(id)
        check(get(id).process.state.value == InstanceState.RUNNING) { "$id is not running" }
        databases.reset(def)
        note(id, "database ${def.database?.database} was reset")
    }

    private fun database(id: InstanceId): InstanceDef = get(id).def.also {
        require(it.type.isDatabase) { "$id is not a database instance" }
    }

    suspend fun start(id: InstanceId): InstanceInfo = withContext(Dispatchers.IO) {
        val instance = get(id)
        check(!instance.process.isAlive) { "$id is already running" }
        syncNetwork(id.project, reloadProxy = false)
        val java = if (instance.def.type.isDatabase) "" else javaFor(instance.def)
        instance.process.start(launchSpec(instance.def, java))
        instance.info()
    }

    private fun javaFor(def: InstanceDef): String {
        if (def.java != InstanceDef.AUTO_JAVA) return def.java
        val feature = def.javaVersion ?: JavaRequirements.forInstance(def.type, def.version)
        return jdks.resolve(feature) { note(def.id, it) }.java.toString()
    }

    private fun source(software: String) = sources[software] ?: throw IllegalArgumentException("Unknown server software '$software'")

    suspend fun stop(id: InstanceId): InstanceInfo {
        pendingRestarts.remove(id)
        get(id).process.stop()
        return info(id)
    }

    fun requestStop(id: InstanceId): InstanceInfo {
        pendingRestarts.remove(id)
        get(id).process.requestStop()
        return info(id)
    }

    fun requestRestart(id: InstanceId): InstanceInfo {
        val process = get(id).process
        pendingRestarts.add(id)
        process.requestStop()
        scope.launch {
            process.stop()
            if (pendingRestarts.remove(id)) runCatching { start(id) }.onFailure { process.log.append("[mpl] restart failed: ${it.message}") }
        }
        return info(id)
    }

    suspend fun kill(id: InstanceId): InstanceInfo {
        pendingRestarts.remove(id)
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
            .onEach {
                _events.emit(Event.StateChanged(def.id, it, process.exitCode))
                if (def.type.isDatabase && it == InstanceState.RUNNING) withContext(Dispatchers.IO) { bootstrapDatabase(instances.getValue(def.id)) }
            }
            .launchIn(scope)
    }

    private fun configLock(project: String): Any = configLocks.computeIfAbsent(project) { Any() }

    private fun launchSpec(def: InstanceDef, java: String): LaunchSpec {
        val dir = Path(def.dir)
        if (def.type.isDatabase) {
            val command = databases.launchCommand(def) { note(def.id, it) }
            return LaunchSpec(command, dir, def.env, def.effectiveStopCommand, Regex(def.effectiveReadyPattern))
        }
        if (def.type == InstanceType.SOULFIRE) return botLaunchSpec(def, java)
        if (def.type == InstanceType.CLIENT) return clientLaunchSpec(def, java)
        val portArgs = def.port?.let { port ->
            when (def.type) {
                InstanceType.PAPER, InstanceType.FOLIA, InstanceType.PURPUR, InstanceType.SPIGOT -> {
                    synchronized(configLock(def.project)) { PropertiesFile.update(dir.resolve("server.properties"), mapOf("server-port" to port.toString())) }
                    emptyList()
                }
                InstanceType.VELOCITY -> listOf("--port", port.toString())
                else -> emptyList()
            }
        }.orEmpty()

        val command = buildList {
            add(java)
            add("-Xmx${def.memory}")
            addAll(def.jvmArgs)
            add("-jar")
            add(def.jar)
            if (def.type.isServer) add("--nogui")
            addAll(portArgs)
            addAll(def.args)
        }
        return LaunchSpec(command, dir, def.env, def.effectiveStopCommand, Regex(def.effectiveReadyPattern))
    }

    private fun botLaunchSpec(def: InstanceDef, java: String): LaunchSpec {
        val bots = requireNotNull(def.bots) { "${def.id} has no bot settings" }
        val target = get(InstanceId(def.project, bots.target)).def
        val port = requireNotNull(target.port) { "${target.id} has no port" }
        val version = bots.protocolVersion
            ?: target.version.takeIf { target.type.isServer }
            ?: instanceDefs(def.project).firstOrNull { it.type.isServer }?.version
            ?: throw IllegalArgumentException("Set a protocol version for ${def.id}")
        val command = soulFire.command(def, java, bots, "127.0.0.1:$port", version)
        return LaunchSpec(command, Path(def.dir), def.env, def.effectiveStopCommand, Regex(def.effectiveReadyPattern))
    }

    private fun clientLaunchSpec(def: InstanceDef, java: String): LaunchSpec {
        val client = requireNotNull(def.client) { "${def.id} has no client settings" }
        val version = launcher.prepare(def.version) { note(def.id, it) }
        val server = client.target?.let { target -> get(InstanceId(def.project, target)).def.port?.let { "127.0.0.1" to it } }
        val command = launcher.command(version, java, Path(def.dir), client.username, server, def.memory)
        return LaunchSpec(command, Path(def.dir), def.env, def.effectiveStopCommand, Regex(def.effectiveReadyPattern))
    }

    private fun nextFreePort(type: InstanceType): Int {
        val used = instances.values.mapNotNull { it.def.port }.toSet()
        return generateSequence(if (type.isProxy) 25577 else if (type.isDatabase) 3307 else 25565) { it + 1 }.first { it !in used }
    }

    private fun ProjectDef.info(): ProjectInfo {
        val own = instances.values.filter { it.def.project == name }
        return ProjectInfo(this, own.size, own.count { it.process.state.value == InstanceState.RUNNING })
    }

    private fun Instance.info() = InstanceInfo(def, process.state.value, process.pid, process.exitCode, process.log.nextSeq)

    private companion object {
        val NAME = Regex("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,31}$")
        val USERNAME = Regex("^[A-Za-z0-9_]{3,16}$")
        const val READY_TIMEOUT_MS = 5 * 60_000L
    }
}
