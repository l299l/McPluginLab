package dev.mpl.core.plugins

import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream

object PluginInstaller {
    const val RECORD_FILE = ".mpl-deployed.properties"

    private val DESCRIPTORS = listOf("paper-plugin.yml", "plugin.yml", "velocity-plugin.json", "bungee.yml")
    private val YAML_NAME = Regex("""^name\s*:\s*(.+)$""", RegexOption.MULTILINE)
    private val JSON_ID = Regex(""""id"\s*:\s*"([^"]+)"""")

    fun install(jar: Path, link: String, plugins: Path) {
        plugins.createDirectories()
        val record = plugins.resolve(RECORD_FILE)
        val deployed = load(record)
        val previous = deployed.getProperty(link)
        val name = pluginName(jar)
        plugins.listDirectoryEntries("*.jar")
            .filter { it.name != jar.name && (it.name == previous || name != null && name.equals(pluginName(it), ignoreCase = true)) }
            .forEach { it.deleteIfExists() }
        jar.copyTo(plugins.resolve(jar.name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        deployed.setProperty(link, jar.name)
        record.outputStream().use { deployed.store(it, null) }
    }

    fun pluginName(jar: Path): String? = runCatching {
        ZipFile(jar.toFile()).use { zip ->
            DESCRIPTORS.firstNotNullOfOrNull { entry ->
                val text = zip.getEntry(entry)?.let { e -> zip.getInputStream(e).use { it.readBytes().decodeToString() } } ?: return@firstNotNullOfOrNull null
                if (entry.endsWith(".json")) JSON_ID.find(text)?.groupValues?.get(1)
                else YAML_NAME.find(text)?.groupValues?.get(1)?.let(::yamlScalar)
            }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun yamlScalar(raw: String): String {
        val value = raw.trim()
        return when {
            value.length >= 2 && value[0] in "'\"" -> value.substring(1).substringBefore(value[0])
            else -> value.substringBefore(" #").trim()
        }
    }

    private fun load(record: Path) = Properties().apply {
        if (record.exists()) runCatching { record.inputStream().use { load(it) } }
    }
}
