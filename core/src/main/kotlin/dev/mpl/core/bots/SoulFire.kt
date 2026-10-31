package dev.mpl.core.bots

import dev.mpl.api.BotSettings
import dev.mpl.api.InstanceDef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.writeLines

class SoulFire(private val jarCache: Path, private val runtimeCache: Path) {
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class Release(val tag_name: String, val prerelease: Boolean = false)

    fun versions(): List<String> = get<List<Release>>("$API/releases?per_page=30").filter { !it.prerelease }.map { it.tag_name }

    fun download(version: String?): Path {
        val tag = version ?: get<Release>("$API/releases/latest").tag_name
        val target = jarCache.resolve("soulfire").createDirectories().resolve("SoulFireCLI-$tag.jar")
        if (target.exists()) return target
        val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".part")
        val url = "https://github.com/$REPO/releases/download/$tag/SoulFireCLI-$tag.jar"
        val response = http.send(request(url), HttpResponse.BodyHandlers.ofFile(tmp))
        check(response.statusCode() == 200) { "SoulFire download failed (${response.statusCode()}): $url" }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return target
    }

    fun command(def: InstanceDef, java: String, settings: BotSettings, address: String, protocolVersion: String): List<String> {
        val dir = Path(def.dir)
        val home = prepareHome(dir.resolve("home"))
        val accounts = dir.resolve("accounts.txt").also { it.writeLines(settings.accountNames) }
        return buildList {
            add(java)
            add("-Xmx${def.memory}")
            add("-Dsf.cliHomeDir=$home")
            add("-Dterminal.jline=false")
            add("-Dterminal.ansi=false")
            add("-Dsf.flags.v2=true")
            addAll(def.jvmArgs)
            add("-jar")
            add(def.jar)
            add("-s")
            add("--bot-address=$address")
            add("--bot-protocol-version=$protocolVersion")
            add("--account-file=$accounts")
            add("--account-type=OFFLINE")
            addAll(def.args)
        }
    }

    private fun prepareHome(home: Path): Path {
        home.createDirectories()
        val shared = sharedRuntime()
        RUNTIME_DIRS.forEach { name ->
            val link = home.resolve(name)
            if (!link.exists() && !link.isSymbolicLink()) link.createSymbolicLinkPointingTo(shared.resolve(name).createDirectories())
        }
        return home
    }

    private fun sharedRuntime(): Path {
        val userHome = Path(System.getProperty("user.home"), ".soulfire")
        return if (userHome.resolve("libraries").isDirectory()) userHome else runtimeCache
    }

    private inline fun <reified T> get(url: String): T {
        val response = http.send(request(url), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "GitHub API ${response.statusCode()} for $url" }
        return json.decodeFromString(response.body())
    }

    private fun request(url: String) = HttpRequest.newBuilder(URI(url)).header("User-Agent", "McPluginLab/0.1").build()

    private companion object {
        const val REPO = "soulfiremc-com/SoulFire"
        const val API = "https://api.github.com/repos/$REPO"
        val RUNTIME_DIRS = listOf("assets", "libraries", "mc-downloads", "minecraft", "natives")
    }
}
