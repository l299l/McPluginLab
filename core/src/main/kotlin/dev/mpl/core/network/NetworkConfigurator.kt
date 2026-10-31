package dev.mpl.core.network

import dev.mpl.api.InstanceDef
import dev.mpl.api.InstanceType
import dev.mpl.api.ProjectDef
import dev.mpl.core.util.PropertiesFile
import dev.mpl.core.util.TomlPatch
import dev.mpl.core.util.YamlPatch
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

object NetworkConfigurator {
    fun proxyOf(instances: List<InstanceDef>): InstanceDef? = instances.firstOrNull { it.type == InstanceType.VELOCITY }

    fun backendsOf(instances: List<InstanceDef>): List<InstanceDef> =
        instances.filter { it.type == InstanceType.PAPER || it.type == InstanceType.FOLIA }.sortedBy { it.name }

    fun sync(project: ProjectDef, instances: List<InstanceDef>) {
        val proxy = proxyOf(instances) ?: return
        val backends = backendsOf(instances)
        configureVelocity(project, proxy, backends)
        backends.forEach { configurePaper(project, it) }
    }

    private fun configureVelocity(project: ProjectDef, proxy: InstanceDef, backends: List<InstanceDef>) {
        val dir = Path(proxy.dir)
        val configFile = dir.resolve("velocity.toml")
        var config = if (configFile.exists()) configFile.readText() else defaultVelocityConfig(dir.resolve(proxy.jar))

        proxy.port?.let { config = TomlPatch.setTopLevel(config, "bind", "\"0.0.0.0:$it\"") }
        config = TomlPatch.setTopLevel(config, "online-mode", project.onlineMode.toString())
        config = TomlPatch.setTopLevel(config, "player-info-forwarding-mode", "\"MODERN\"")
        config = TomlPatch.setTopLevel(config, "forwarding-secret-file", "\"forwarding.secret\"")

        val tryOrder = backends.sortedByDescending { it.name.contains("lobby", ignoreCase = true) }.map { "\"${it.name}\"" }
        val servers = backends.map { "${it.name} = \"127.0.0.1:${it.port}\"" } + "try = [${tryOrder.joinToString(", ")}]"
        config = TomlPatch.replaceSection(config, "servers", servers)
        config = TomlPatch.replaceSection(config, "forced-hosts", emptyList())

        configFile.writeText(config)
        dir.resolve("forwarding.secret").writeText(project.forwardingSecret)
    }

    private fun configurePaper(project: ProjectDef, server: InstanceDef) {
        val dir = Path(server.dir)
        PropertiesFile.update(dir.resolve("server.properties"), mapOf("online-mode" to "false"))

        val legacy = dir.resolve("paper.yml")
        val (file, root) = if (!isModernPaper(server.version) && legacy.exists()) legacy to listOf("settings", "velocity-support")
        else dir.resolve("config").createDirectories().resolve("paper-global.yml") to listOf("proxies", "velocity")

        var yaml = if (file.exists()) file.readText() else ""
        yaml = YamlPatch.set(yaml, root + "enabled", "true")
        yaml = YamlPatch.set(yaml, root + "online-mode", project.onlineMode.toString())
        yaml = YamlPatch.set(yaml, root + "secret", "'${project.forwardingSecret}'")
        file.writeText(yaml.trimStart('\n'))
    }

    private fun isModernPaper(version: String): Boolean {
        val parts = version.split('.', '-').mapNotNull { it.toIntOrNull() }
        val (major, minor) = (parts.getOrNull(0) ?: return true) to (parts.getOrNull(1) ?: 0)
        return major > 1 || minor >= 19
    }

    private fun defaultVelocityConfig(jar: Path): String = ZipFile(jar.toFile()).use { zip ->
        val entry = zip.getEntry("default-velocity.toml") ?: error("No default-velocity.toml in $jar")
        zip.getInputStream(entry).bufferedReader().readText()
    }
}
