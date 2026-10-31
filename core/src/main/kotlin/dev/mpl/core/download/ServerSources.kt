package dev.mpl.core.download

import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.exists

data class ResolvedJar(val path: Path, val version: String, val build: Int?, val javaVersion: Int? = null)

interface ServerSource {
    fun versions(): List<String>
    fun download(version: String?, build: Int?, log: (String) -> Unit = {}): ResolvedJar
}

fun latestStable(versions: List<String>): String =
    versions.firstOrNull { v -> v.none { it.isLetter() } } ?: versions.first()

class FillSource(private val project: String, private val downloader: Downloader, private val cache: Path) : ServerSource {
    @Serializable private data class ProjectResponse(val versions: Map<String, List<String>>)
    @Serializable private data class Checksums(val sha256: String)
    @Serializable private data class Download(val name: String, val checksums: Checksums, val url: String)
    @Serializable private data class Build(val id: Int, val downloads: Map<String, Download>)
    @Serializable private data class JavaVersion(val minimum: Int)
    @Serializable private data class JavaInfo(val version: JavaVersion)
    @Serializable private data class VersionInfo(val java: JavaInfo? = null)
    @Serializable private data class VersionResponse(val version: VersionInfo)

    override fun versions(): List<String> = downloader.json<ProjectResponse>("$API/projects/$project").versions.values.flatten()

    override fun download(version: String?, build: Int?, log: (String) -> Unit): ResolvedJar {
        val resolved = version ?: latestStable(versions())
        val info = downloader.json<Build>("$API/projects/$project/versions/$resolved/builds/${build ?: "latest"}")
        val file = info.downloads["server:default"] ?: error("No server download for $project $resolved build ${info.id}")
        val path = downloader.file(file.url, cache.resolve(project).resolve(file.name), sha256 = file.checksums.sha256)
        val java = runCatching { downloader.json<VersionResponse>("$API/projects/$project/versions/$resolved").version.java?.version?.minimum }.getOrNull()
        return ResolvedJar(path, resolved, info.id, java)
    }

    private companion object {
        const val API = "https://fill.papermc.io/v3"
    }
}

class PurpurSource(private val downloader: Downloader, private val cache: Path) : ServerSource {
    @Serializable private data class Project(val versions: List<String>)
    @Serializable private data class Builds(val latest: String)
    @Serializable private data class Version(val builds: Builds)

    override fun versions(): List<String> = downloader.json<Project>(API).versions.reversed()

    override fun download(version: String?, build: Int?, log: (String) -> Unit): ResolvedJar {
        val resolved = version ?: versions().first()
        val buildId = build?.toString() ?: downloader.json<Version>("$API/$resolved").builds.latest
        val target = cache.resolve("purpur").resolve("purpur-$resolved-$buildId.jar")
        if (!target.exists()) downloader.file("$API/$resolved/$buildId/download", target)
        return ResolvedJar(target, resolved, buildId.toIntOrNull())
    }

    private companion object {
        const val API = "https://api.purpurmc.org/v2/purpur"
    }
}

class BungeeCordSource(private val downloader: Downloader, private val cache: Path) : ServerSource {
    @Serializable private data class Build(val number: Int)

    override fun versions(): List<String> = listOf("latest")

    override fun download(version: String?, build: Int?, log: (String) -> Unit): ResolvedJar {
        val number = build ?: downloader.json<Build>("$JOB/lastSuccessfulBuild/api/json").number
        val target = cache.resolve("bungeecord").resolve("BungeeCord-$number.jar")
        if (!target.exists()) downloader.file("$JOB/$number/artifact/bootstrap/target/BungeeCord.jar", target)
        return ResolvedJar(target, "latest", number)
    }

    private companion object {
        const val JOB = "https://ci.md-5.net/job/BungeeCord"
    }
}
