package dev.mpl.core

import com.charleskorn.kaml.Yaml
import dev.mpl.api.InstanceDef
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

class Workspace(path: Path) {
    val root: Path = path.toAbsolutePath().normalize()
    val instancesDir: Path = root.resolve("instances")
    val serversDir: Path = root.resolve("servers")
    val cacheDir: Path = root.resolve("cache")
    val logsDir: Path = root.resolve("logs")

    init {
        listOf(instancesDir, serversDir, cacheDir, logsDir).forEach { it.createDirectories() }
    }

    fun loadInstances(): List<InstanceDef> = instancesDir.listDirectoryEntries()
        .filter { it.extension == "yaml" }
        .map { Yaml.default.decodeFromString(InstanceDef.serializer(), it.readText()) }

    fun save(def: InstanceDef) = instanceFile(def.name).writeText(Yaml.default.encodeToString(InstanceDef.serializer(), def))

    fun delete(name: String) = instanceFile(name).deleteIfExists()

    fun instanceLog(name: String): Path = logsDir.resolve("$name.log")

    private fun instanceFile(name: String) = instancesDir.resolve("$name.yaml")
}
