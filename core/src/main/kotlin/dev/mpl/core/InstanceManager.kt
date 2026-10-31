package dev.mpl.core

import dev.mpl.api.CreateInstanceRequest
import dev.mpl.api.Event
import dev.mpl.api.InstanceDef
import dev.mpl.api.InstanceInfo
import dev.mpl.api.InstanceType
import dev.mpl.core.download.FillClient
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
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class NotFoundException(message: String) : RuntimeException(message)

class InstanceManager(private val workspace: Workspace, private val scope: CoroutineScope) {
    private val fill = FillClient(workspace.cacheDir.resolve("jars"))
    private val instances = ConcurrentHashMap<String, Instance>()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    class Instance(@Volatile var def: InstanceDef, val process: ManagedProcess)

    init {
        workspace.loadInstances().forEach(::register)
    }

    fun list(): List<InstanceInfo> = instances.values.sortedBy { it.def.name }.map { it.info() }

    fun info(name: String): InstanceInfo = get(name).info()

    fun get(name: String): Instance = instances[name] ?: throw NotFoundException("No instance named '$name'")

    fun versions(project: String): List<String> = fill.versions(project)

    suspend fun create(req: CreateInstanceRequest): InstanceInfo = withContext(Dispatchers.IO) {
        require(NAME.matches(req.name)) { "Invalid name '${req.name}' (letters, digits, . _ -)" }
        require(!instances.containsKey(req.name)) { "Instance '${req.name}' already exists" }
        val dir = req.dir?.let { Path(it).absolute() } ?: workspace.serversDir.resolve(req.name)
        dir.createDirectories()

        var def = InstanceDef(
            name = req.name,
            type = req.type,
            dir = dir.toString(),
            port = req.port ?: nextFreePort(req.type),
            memory = req.memory ?: if (req.type.isProxy) "512M" else "2G",
            java = req.java ?: "java",
        )
        val existingJar = req.jar
        def = if (existingJar != null) {
            require(dir.resolve(existingJar).exists()) { "Jar $existingJar not found in $dir" }
            def.copy(jar = existingJar, version = req.version.orEmpty())
        } else {
            val project = req.type.fillProject ?: throw IllegalArgumentException("Type ${req.type} needs an existing jar")
            val version = req.version ?: fill.latestStableVersion(project)
            val jar = fill.download(project, version, req.build)
            jar.path.copyTo(dir.resolve(def.jar), overwrite = true)
            def.copy(version = jar.version, build = jar.build)
        }

        if (!req.type.isProxy && req.type != InstanceType.CUSTOM) {
            val eula = dir.resolve("eula.txt")
            if (req.acceptEula) eula.writeText("eula=true\n")
            require(eula.exists()) { "Minecraft EULA must be accepted (https://aka.ms/MinecraftEULA): pass acceptEula" }
            if (req.offlineMode) PropertiesFile.update(dir.resolve("server.properties"), mapOf("online-mode" to "false"))
        }

        workspace.save(def)
        register(def)
        _events.emit(Event.Created(def.name))
        info(def.name)
    }

    fun start(name: String): InstanceInfo {
        val instance = get(name)
        instance.process.start(launchSpec(instance.def))
        return instance.info()
    }

    suspend fun stop(name: String): InstanceInfo {
        get(name).process.stop()
        return info(name)
    }

    suspend fun kill(name: String): InstanceInfo {
        get(name).process.kill()
        return info(name)
    }

    suspend fun restart(name: String): InstanceInfo {
        stop(name)
        return start(name)
    }

    fun sendCommand(name: String, command: String) = get(name).process.sendCommand(command)

    suspend fun remove(name: String, deleteFiles: Boolean) {
        val instance = get(name)
        instance.process.stop()
        instances.remove(name)
        workspace.delete(name)
        if (deleteFiles) {
            val dir = Path(instance.def.dir)
            require(dir.startsWith(workspace.serversDir)) { "Refusing to delete files outside ${workspace.serversDir}" }
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
        }
        _events.emit(Event.Removed(name))
    }

    suspend fun stopAll() = coroutineScope {
        instances.values.map { async { it.process.stop() } }.awaitAll()
    }

    private fun register(def: InstanceDef) {
        val process = ManagedProcess(def.name, workspace.instanceLog(def.name), scope)
        instances[def.name] = Instance(def, process)
        process.state.drop(1)
            .onEach { _events.emit(Event.StateChanged(def.name, it, process.exitCode)) }
            .launchIn(scope)
    }

    private fun launchSpec(def: InstanceDef): LaunchSpec {
        val dir = Path(def.dir)
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

    private fun nextFreePort(type: InstanceType): Int {
        val used = instances.values.mapNotNull { it.def.port }.toSet()
        return generateSequence(if (type.isProxy) 25577 else 25565) { it + 1 }.first { it !in used }
    }

    private fun Instance.info() = InstanceInfo(def, process.state.value, process.pid, process.exitCode, process.log.nextSeq)

    private fun Path.deleteRecursively() = toFile().deleteRecursively()

    companion object {
        private val NAME = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,31}$")
    }
}
