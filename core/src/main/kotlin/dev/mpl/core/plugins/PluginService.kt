package dev.mpl.core.plugins

import dev.mpl.api.InstanceDef
import dev.mpl.api.PluginLink
import dev.mpl.api.PluginStatus
import dev.mpl.core.Lab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

class PluginService(private val lab: Lab, private val scope: CoroutineScope) {
    private data class Key(val project: String, val name: String)
    private data class Signature(val path: Path, val size: Long, val modified: Long)

    private val statuses = ConcurrentHashMap<Key, PluginStatus>()
    private val lastSeen = ConcurrentHashMap<Key, Signature>()

    fun start() = scope.launch(Dispatchers.IO) {
        while (isActive) {
            lab.projects().forEach { project ->
                project.def.plugins.filter { it.autoDeploy }.forEach { link -> runCatching { poll(project.def.name, link) } }
            }
            delay(POLL_MS)
        }
    }

    fun statuses(project: String, links: List<PluginLink>): List<PluginStatus> = links.map { link ->
        val jar = resolve(link.source)
        (statuses[Key(project, link.name)] ?: PluginStatus(link)).copy(link = link, jar = jar?.toString())
    }

    fun forget(project: String, name: String) {
        statuses.remove(Key(project, name))
        lastSeen.remove(Key(project, name))
    }

    fun deploy(project: String, link: PluginLink): PluginStatus {
        val key = Key(project, link.name)
        val status = runCatching {
            val jar = resolve(link.source) ?: throw IllegalArgumentException("No jar matches ${link.source}")
            val targets = targets(project, link)
            require(targets.isNotEmpty()) { "Plugin ${link.name} has no target servers" }
            targets.forEach { install(jar, link.source, it) }
            targets.filter { link.restart && lab.isAlive(it.id) }.forEach { lab.requestRestart(it.id) }
            targets.forEach { lab.note(it.id, "deployed ${jar.name}") }
            PluginStatus(link, jar.toString(), jar.name, System.currentTimeMillis())
        }.getOrElse { PluginStatus(link, error = it.message ?: it.toString()) }
        statuses[key] = status
        lab.pluginsChanged(project)
        return status
    }

    private fun poll(project: String, link: PluginLink) {
        val key = Key(project, link.name)
        val jar = resolve(link.source) ?: return
        val signature = Signature(jar, jar.fileSize(), jar.getLastModifiedTime().toMillis())
        val stable = lastSeen.put(key, signature) == signature
        if (stable && !isDeployed(signature, targets(project, link))) deploy(project, link)
    }

    private fun targets(project: String, link: PluginLink): List<InstanceDef> {
        val instances = lab.instanceDefs(project)
        return if (link.targets.isEmpty()) instances.filter { !it.type.isProxy } else instances.filter { it.name in link.targets }
    }

    private fun isDeployed(jar: Signature, targets: List<InstanceDef>) = targets.all { target ->
        val installed = Path(target.dir).resolve("plugins").resolve(jar.path.name)
        installed.exists() && installed.fileSize() == jar.size && installed.getLastModifiedTime().toMillis() == jar.modified
    }

    private fun install(jar: Path, source: String, target: InstanceDef) {
        val plugins = Path(target.dir).resolve("plugins").createDirectories()
        val pattern = globRegex(Path(source).name.takeIf { it.endsWith(".jar") } ?: jar.name)
        plugins.listDirectoryEntries("*.jar").filter { it.name != jar.name && pattern.matches(it.name) }.forEach { it.deleteIfExists() }
        jar.copyTo(plugins.resolve(jar.name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
    }

    companion object {
        private const val POLL_MS = 2_000L
        private val IGNORED = Regex("""(-sources|-javadoc|-tests)\.jar$|^original-""")

        fun resolve(source: String): Path? {
            val path = Path(source.replaceFirst(Regex("^~"), System.getProperty("user.home")))
            val (dir, pattern) = when {
                path.isDirectory() -> path to Regex(""".*\.jar""")
                '*' in path.name -> path.parent to globRegex(path.name)
                else -> return path.takeIf { it.exists() }
            }
            if (dir == null || !dir.isDirectory()) return null
            return dir.listDirectoryEntries()
                .filter { pattern.matches(it.name) && !IGNORED.containsMatchIn(it.name) }
                .maxByOrNull { it.getLastModifiedTime() }
        }

        private fun globRegex(glob: String) = Regex(glob.split('*').joinToString(".*") { Regex.escape(it) })
    }
}
