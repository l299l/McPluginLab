package dev.mpl.core.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class FillClient(private val cacheDir: Path) {
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class ProjectResponse(val versions: Map<String, List<String>>)
    @Serializable private data class Checksums(val sha256: String)
    @Serializable private data class Download(val name: String, val checksums: Checksums, val url: String)
    @Serializable private data class Build(val id: Int, val channel: String, val downloads: Map<String, Download>)
    @Serializable private data class JavaVersion(val minimum: Int)
    @Serializable private data class JavaInfo(val version: JavaVersion)
    @Serializable private data class VersionInfo(val java: JavaInfo? = null)
    @Serializable private data class VersionResponse(val version: VersionInfo)

    data class ResolvedJar(val path: Path, val version: String, val build: Int)

    fun versions(project: String): List<String> =
        get<ProjectResponse>("projects/$project").versions.values.flatten()

    fun latestStableVersion(project: String): String =
        versions(project).first { v -> v.none { it.isLetter() } }

    fun minimumJava(project: String, version: String): Int? =
        runCatching { get<VersionResponse>("projects/$project/versions/$version").version.java?.version?.minimum }.getOrNull()

    fun download(project: String, version: String, build: Int?): ResolvedJar {
        val buildPath = build?.toString() ?: "latest"
        val info = get<Build>("projects/$project/versions/$version/builds/$buildPath")
        val file = info.downloads["server:default"] ?: error("No server download for $project $version build ${info.id}")
        val target = cacheDir.resolve(project).resolve(file.name)
        if (!target.exists() || sha256(target) != file.checksums.sha256) {
            target.parent.createDirectories()
            val tmp = Files.createTempFile(target.parent, file.name, ".part")
            http.send(request(file.url), HttpResponse.BodyHandlers.ofFile(tmp))
            check(sha256(tmp) == file.checksums.sha256) { "Checksum mismatch for ${file.name}" }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return ResolvedJar(target, version, info.id)
    }

    private inline fun <reified T> get(path: String): T {
        val response = http.send(request("$API/$path"), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Fill API ${response.statusCode()} for $path: ${response.body().take(200)}" }
        return json.decodeFromString(response.body())
    }

    private fun request(url: String) = HttpRequest.newBuilder(URI(url)).header("User-Agent", USER_AGENT).build()

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        path.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHexString()
    }

    companion object {
        private const val API = "https://fill.papermc.io/v3"
        private const val USER_AGENT = "McPluginLab/0.1"
    }
}
