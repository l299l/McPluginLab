package dev.mpl.core.database

import dev.mpl.api.DatabaseInfo
import dev.mpl.api.DbBinding
import dev.mpl.api.InstanceDef
import dev.mpl.api.ProjectDef
import dev.mpl.core.util.PropertiesFile
import dev.mpl.core.util.TomlPatch
import dev.mpl.core.util.YamlPatch
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Writes the project database's connection details into the config files of linked plugins ([dev.mpl.api.PluginLink.db]).
 * Files that do not exist yet are left alone, because creating one would stop the plugin from writing its own defaults;
 * they are filled in on the next start, once the plugin has generated them.
 */
object DatabaseConfigurator {
    fun sync(project: ProjectDef, instances: List<InstanceDef>, info: (InstanceDef) -> DatabaseInfo) {
        val database = instances.firstOrNull { it.type.isDatabase && it.database != null && it.port != null } ?: return
        val connection = info(database)
        for (link in project.plugins) {
            val binding = link.db?.takeIf { it.values.isNotEmpty() } ?: continue
            val targets = instances.filter { if (link.targets.isEmpty()) it.type.isServer else (it.type.isServer || it.type.isProxy) && it.name in link.targets }
            targets.forEach { apply(Path(it.dir), binding, connection) }
        }
    }

    fun render(template: String, info: DatabaseInfo): String = template
        .replace("{host}", info.host).replace("{port}", info.port.toString())
        .replace("{database}", info.database).replace("{user}", info.user)
        .replace("{password}", info.password).replace("{jdbc}", info.jdbc)

    fun apply(serverDir: Path, binding: DbBinding, info: DatabaseInfo) {
        val file = serverDir.resolve(binding.file).normalize()
        require(file.startsWith(serverDir.normalize())) { "Config file ${binding.file} is outside the server folder" }
        if (!file.exists()) return
        val values = binding.values.mapValues { render(it.value, info) }
        when (file.extension.lowercase()) {
            "yml", "yaml" -> file.writeText(values.entries.fold(file.readText()) { text, (key, value) ->
                YamlPatch.set(text, key.split('.'), yamlScalar(value))
            })
            "toml" -> file.writeText(values.entries.fold(file.readText()) { text, (key, value) ->
                TomlPatch.setTopLevel(text, key, tomlScalar(value))
            })
            "properties", "conf", "cfg" -> PropertiesFile.update(file, values)
            else -> throw IllegalArgumentException("Unsupported config format '.${file.extension}' for ${binding.file} (use yml, toml or properties)")
        }
    }

    private fun yamlScalar(value: String) = if (value.matches(Regex("^[0-9]+$|^(true|false)$"))) value else "'" + value.replace("'", "''") + "'"

    private fun tomlScalar(value: String) = if (value.matches(Regex("^[0-9]+$|^(true|false)$"))) value else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
