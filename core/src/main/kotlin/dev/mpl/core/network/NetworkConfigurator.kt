package dev.mpl.core.network

import dev.mpl.api.InstanceDef
import dev.mpl.api.InstanceType
import dev.mpl.api.Network.atLeast
import dev.mpl.api.Network.backendsOf
import dev.mpl.api.Network.proxyOf
import dev.mpl.api.Network.usesModernForwarding
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
    fun sync(project: ProjectDef, instances: List<InstanceDef>) {
        val proxy = proxyOf(instances)
        val backends = if (proxy == null) emptyList() else backendsOf(instances)
        if (proxy != null) {
            val modern = usesModernForwarding(proxy, backends)
            if (proxy.type == InstanceType.VELOCITY) configureVelocity(project, proxy, backends, modern) else configureBungee(project, proxy, backends)
            backends.forEach { configureBackend(project, it, modern) }
        }
        instances.filter { it.type.isServer && it !in backends }.forEach(::configureStandalone)
    }

    fun reloadCommand(proxy: InstanceDef): String = if (proxy.type == InstanceType.VELOCITY) "velocity reload" else "greload"

    private fun configureVelocity(project: ProjectDef, proxy: InstanceDef, backends: List<InstanceDef>, modern: Boolean) {
        val dir = Path(proxy.dir)
        val file = dir.resolve("velocity.toml")
        var config = if (file.exists()) file.readText() else bundledResource(dir.resolve(proxy.jar), "default-velocity.toml")

        proxy.port?.let { config = TomlPatch.setTopLevel(config, "bind", "\"0.0.0.0:$it\"") }
        config = TomlPatch.setTopLevel(config, "online-mode", project.onlineMode.toString())
        config = TomlPatch.setTopLevel(config, "player-info-forwarding-mode", if (modern) "\"MODERN\"" else "\"LEGACY\"")
        config = TomlPatch.setTopLevel(config, "forwarding-secret-file", "\"forwarding.secret\"")
        val servers = backends.map { "${it.name} = \"127.0.0.1:${it.port}\"" } + "try = [${backends.joinToString(", ") { "\"${it.name}\"" }}]"
        config = TomlPatch.replaceSection(config, "servers", servers)
        config = TomlPatch.replaceSection(config, "forced-hosts", emptyList())

        file.writeText(config)
        dir.resolve("forwarding.secret").writeText(project.forwardingSecret)
    }

    private fun configureBungee(project: ProjectDef, proxy: InstanceDef, backends: List<InstanceDef>) {
        val file = Path(proxy.dir).resolve("config.yml")
        val port = proxy.port ?: 25577
        var config = if (file.exists()) file.readText() else ""
        config = YamlPatch.replaceTopLevel(config, "servers", backends.flatMap {
            listOf("  ${it.name}:", "    motd: '${it.name}'", "    address: 127.0.0.1:${it.port}", "    restricted: false")
        })
        config = YamlPatch.replaceTopLevel(config, "listeners", listOf(
            "- host: 0.0.0.0:$port",
            "  query_port: $port",
            "  query_enabled: false",
            "  motd: '${project.name}'",
            "  max_players: 100",
            "  priorities:",
        ) + backends.map { "  - ${it.name}" } + listOf(
            "  force_default_server: true",
            "  forced_hosts: {}",
            "  tab_list: GLOBAL_PING",
            "  tab_size: 60",
            "  bind_local_address: true",
            "  ping_passthrough: false",
            "  proxy_protocol: false",
        ))
        config = YamlPatch.set(config, listOf("ip_forward"), "true")
        config = YamlPatch.set(config, listOf("online_mode"), project.onlineMode.toString())
        file.writeText(config.trimStart('\n') + "\n")
    }

    private fun configureBackend(project: ProjectDef, server: InstanceDef, modern: Boolean) {
        val dir = Path(server.dir)
        PropertiesFile.update(dir.resolve("server.properties"), mapOf("online-mode" to "false"))
        patchYaml(dir.resolve("spigot.yml"), listOf("settings", "bungeecord"), (!modern).toString())
        if (!server.type.isPaperBased) return

        val legacyPaper = dir.resolve("paper.yml")
        val (file, root) = if (!atLeast(server.version, 19) && legacyPaper.exists()) legacyPaper to listOf("settings", "velocity-support")
        else dir.resolve("config").createDirectories().resolve("paper-global.yml") to listOf("proxies", "velocity")
        patchYaml(file, root + "enabled", modern.toString())
        if (modern) {
            patchYaml(file, root + "online-mode", project.onlineMode.toString())
            patchYaml(file, root + "secret", "'${project.forwardingSecret}'")
        }
    }

    private fun configureStandalone(server: InstanceDef) {
        val dir = Path(server.dir)
        if (dir.resolve("spigot.yml").exists()) patchYaml(dir.resolve("spigot.yml"), listOf("settings", "bungeecord"), "false")
        val paperGlobal = dir.resolve("config").resolve("paper-global.yml")
        if (server.type.isPaperBased && paperGlobal.exists()) patchYaml(paperGlobal, listOf("proxies", "velocity", "enabled"), "false")
    }

    private fun patchYaml(file: Path, path: List<String>, value: String) {
        val text = if (file.exists()) file.readText() else ""
        file.writeText(YamlPatch.set(text, path, value).trimStart('\n'))
    }

    private fun bundledResource(jar: Path, name: String): String = ZipFile(jar.toFile()).use { zip ->
        val entry = zip.getEntry(name) ?: error("No $name in $jar")
        zip.getInputStream(entry).bufferedReader().readText()
    }
}
