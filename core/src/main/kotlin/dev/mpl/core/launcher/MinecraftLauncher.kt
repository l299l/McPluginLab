package dev.mpl.core.launcher

import dev.mpl.core.download.Downloader
import dev.mpl.core.util.PropertiesFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

class MinecraftLauncher(private val root: Path, private val downloader: Downloader) {
    private val libraries = root.resolve("libraries")
    private val assets = root.resolve("assets")
    private val versions = root.resolve("versions")

    fun releases(): List<String> = manifest().versions.filter { it.type == "release" }.map { it.id }

    fun latestRelease(): String = manifest().latest.release

    @Synchronized
    fun prepare(versionId: String, log: (String) -> Unit = {}): VersionJson {
        val version = versionJson(versionId)
        val clientJar = versions.resolve(version.id).resolve("${version.id}.jar")
        downloader.file(version.downloads.client.url, clientJar, sha1 = version.downloads.client.sha1)

        val needed = version.libraries.filter { allowed(it.rules) }
        needed.forEach { library -> library.downloads?.artifact?.let { downloader.file(it.url, libraries.resolve(it.path!!), sha1 = it.sha1) } }
        extractNatives(version, needed)
        downloadAssets(version, log)
        return version
    }

    fun command(version: VersionJson, java: String, gameDir: Path, username: String, server: Pair<String, Int>?, memory: String): List<String> {
        PropertiesFile.update(gameDir.createDirectories().resolve("options.txt"), TEST_OPTIONS, separator = ':')
        val nativesDir = nativesDir(version)
        val classpath = (version.libraries.filter { allowed(it.rules) }.mapNotNull { it.downloads?.artifact?.path }.map { libraries.resolve(it).toString() } +
            versions.resolve(version.id).resolve("${version.id}.jar").toString()).distinct()
        val values = mapOf(
            "auth_player_name" to username,
            "version_name" to version.id,
            "game_directory" to gameDir.toString(),
            "assets_root" to assets.toString(),
            "game_assets" to assets.toString(),
            "assets_index_name" to version.assetIndex.id,
            "auth_uuid" to offlineUuid(username),
            "auth_access_token" to "0",
            "auth_session" to "0",
            "clientid" to "0",
            "auth_xuid" to "0",
            "user_type" to "legacy",
            "version_type" to version.type,
            "natives_directory" to nativesDir.toString(),
            "launcher_name" to "McPluginLab",
            "launcher_version" to "0.1",
            "classpath" to classpath.joinToString(File.pathSeparator),
            "classpath_separator" to File.pathSeparator,
            "library_directory" to libraries.toString(),
            "user_properties" to "{}",
        )
        val jvm = version.arguments?.jvm?.let(::flatten)
            ?: listOf("-Djava.library.path=\${natives_directory}", "-cp", "\${classpath}")
        val game = version.arguments?.game?.let(::flatten) ?: version.minecraftArguments.orEmpty().split(' ').filter { it.isNotBlank() }
        val join = server?.let { (host, port) ->
            if (supportsQuickPlay(version)) listOf("--quickPlayMultiplayer", "$host:$port") else listOf("--server", host, "--port", port.toString())
        }.orEmpty()

        return listOf(java, "-Xmx$memory") + jvm.map { substitute(it, values) } + version.mainClass + game.map { substitute(it, values) } + join
    }

    private fun manifest(): VersionManifest = downloader.json(MANIFEST)

    private fun versionJson(versionId: String): VersionJson {
        val file = versions.resolve(versionId).resolve("$versionId.json")
        if (!file.exists()) {
            val entry = manifest().versions.firstOrNull { it.id == versionId } ?: throw IllegalArgumentException("Unknown Minecraft version $versionId")
            downloader.file(entry.url, file, sha1 = entry.sha1)
        }
        return downloader.json.decodeFromString(file.readText())
    }

    private fun extractNatives(version: VersionJson, needed: List<Library>) {
        val target = nativesDir(version).createDirectories()
        needed.forEach { library ->
            val classifier = library.natives?.get("linux")?.replace("\${arch}", "64") ?: return@forEach
            val artifact = library.downloads?.classifiers?.get(classifier) ?: return@forEach
            val jar = downloader.file(artifact.url, libraries.resolve(artifact.path!!), sha1 = artifact.sha1)
            ZipFile(jar.toFile()).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && !it.name.startsWith("META-INF") }.forEach { entry ->
                    val out = target.resolve(entry.name.substringAfterLast('/'))
                    if (!out.exists()) zip.getInputStream(entry).use { input -> out.toFile().outputStream().use { input.copyTo(it) } }
                }
            }
        }
    }

    private fun downloadAssets(version: VersionJson, log: (String) -> Unit) {
        val indexFile = assets.resolve("indexes").resolve("${version.assetIndex.id}.json")
        downloader.file(version.assetIndex.url, indexFile, sha1 = version.assetIndex.sha1)
        val index = downloader.json.decodeFromString<AssetIndex>(indexFile.readText())
        val missing = index.objects.values.distinctBy { it.hash }.filter { !objectPath(it.hash).exists() }
        if (missing.isEmpty()) return
        log("Downloading ${missing.size} Minecraft assets")
        val done = AtomicInteger()
        Executors.newFixedThreadPool(16).use { pool ->
            missing.map { asset ->
                pool.submit {
                    downloader.file("$RESOURCES/${asset.hash.take(2)}/${asset.hash}", objectPath(asset.hash), sha1 = asset.hash)
                    done.incrementAndGet().takeIf { it % 500 == 0 }?.let { log("Assets: $it/${missing.size}") }
                }
            }.forEach { it.get() }
        }
    }

    private fun objectPath(hash: String) = assets.resolve("objects").resolve(hash.take(2)).resolve(hash)

    private fun nativesDir(version: VersionJson) = versions.resolve(version.id).resolve("natives")

    private fun flatten(arguments: List<JsonElement>): List<String> = arguments.flatMap { element ->
        when (element) {
            is JsonPrimitive -> listOf(element.content)
            is JsonObject -> {
                val rules = element["rules"]?.let { downloader.json.decodeFromJsonElement<List<Rule>>(it) }
                if (!allowed(rules)) emptyList()
                else when (val value = element["value"]) {
                    is JsonArray -> value.map { it.jsonPrimitive.content }
                    is JsonPrimitive -> listOf(value.content)
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun supportsQuickPlay(version: VersionJson) =
        version.arguments?.game.orEmpty().any { it is JsonObject && it.toString().contains("is_quick_play_multiplayer") }

    private fun allowed(rules: List<Rule>?): Boolean {
        if (rules.isNullOrEmpty()) return true
        var allowed = false
        rules.forEach { rule ->
            val osMatches = rule.os?.name.let { it == null || it == "linux" } && rule.os?.arch.let { it == null || it == "x86_64" || it == "x64" }
            if (rule.features == null && osMatches) allowed = rule.action == "allow"
        }
        return allowed
    }

    private fun substitute(value: String, values: Map<String, String>) =
        PLACEHOLDER.replace(value) { values[it.groupValues[1]] ?: it.value }

    companion object {
        private const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        private const val RESOURCES = "https://resources.download.minecraft.net"
        private val PLACEHOLDER = Regex("""\$\{([a-z_]+)}""")
        private val TEST_OPTIONS = mapOf(
            "onboardAccessibility" to "false",
            "skipMultiplayerWarning" to "true",
            "joinedFirstServer" to "true",
            "pauseOnLostFocus" to "false",
        )

        fun offlineUuid(name: String): String = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray()).toString().replace("-", "")
    }
}
