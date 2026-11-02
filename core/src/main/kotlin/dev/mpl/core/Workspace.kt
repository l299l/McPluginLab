package dev.mpl.core

import com.charleskorn.kaml.Yaml
import dev.mpl.api.InstanceDef
import dev.mpl.api.InstanceId
import dev.mpl.api.ProjectDef
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

class Workspace(home: Path, cache: Path) {
    val root: Path = home.toAbsolutePath().normalize()
    val jarCache: Path = cache.toAbsolutePath().normalize().resolve("jars")
    val soulFireRuntime: Path = cache.toAbsolutePath().normalize().resolve("soulfire")
    val jdkDir: Path = cache.toAbsolutePath().normalize().resolve("jdks")
    val minecraftDir: Path = cache.toAbsolutePath().normalize().resolve("minecraft")
    val databaseDir: Path = cache.toAbsolutePath().normalize().resolve("db")
    private val projectsDir: Path = root.resolve("projects")
    private val serversDir: Path = root.resolve("servers")
    private val logsDir: Path = root.resolve("logs")

    init {
        listOf(projectsDir, serversDir, logsDir, jarCache).forEach { it.createDirectories() }
    }

    fun defaultProjectDir(project: String): Path = serversDir.resolve(project)

    fun loadProjects(): List<ProjectDef> = projectsDir.listDirectoryEntries()
        .map { it.resolve(PROJECT_FILE) }
        .filter { it.exists() }
        .map { Yaml.default.decodeFromString(ProjectDef.serializer(), it.readText()) }

    fun loadInstances(project: String): List<InstanceDef> {
        val dir = instancesDir(project).takeIf { it.isDirectory() } ?: return emptyList()
        return dir.listDirectoryEntries()
            .filter { it.extension == "yaml" }
            .map { Yaml.default.decodeFromString(InstanceDef.serializer(), it.readText()) }
    }

    fun save(project: ProjectDef) {
        instancesDir(project.name).createDirectories()
        projectsDir.resolve(project.name).resolve(PROJECT_FILE).writeText(Yaml.default.encodeToString(ProjectDef.serializer(), project))
    }

    fun save(instance: InstanceDef) {
        val file = instancesDir(instance.project).createDirectories().resolve("${instance.name}.yaml")
        file.writeText(Yaml.default.encodeToString(InstanceDef.serializer(), instance))
    }

    fun deleteProject(project: String) = projectsDir.resolve(project).toFile().deleteRecursively()

    fun deleteInstance(id: InstanceId) = instancesDir(id.project).resolve("${id.name}.yaml").deleteIfExists()

    fun instanceLog(id: InstanceId): Path = logsDir.resolve(id.project).createDirectories().resolve("${id.name}.log")

    private fun instancesDir(project: String) = projectsDir.resolve(project).resolve("instances")

    private companion object {
        const val PROJECT_FILE = "project.yaml"
    }
}
