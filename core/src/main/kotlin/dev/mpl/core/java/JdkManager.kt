package dev.mpl.core.java

import dev.mpl.core.download.Downloader
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines

data class Jdk(val feature: Int, val home: Path) {
    val java: Path get() = home.resolve("bin").resolve("java")
}

class JdkManager(private val dir: Path, private val downloader: Downloader) {
    @Serializable private data class Package(val name: String, val link: String, val checksum: String)
    @Serializable private data class Binary(val `package`: Package)
    @Serializable private data class Asset(val binary: Binary, val release_name: String)

    fun installed(): List<Jdk> = searchRoots()
        .filter { it.isDirectory() }
        .flatMap { root -> root.listDirectoryEntries() }
        .mapNotNull(::inspect)
        .distinctBy { it.home.toRealPath() }

    @Synchronized
    fun resolve(feature: Int, log: (String) -> Unit = {}): Jdk =
        installed().firstOrNull { it.feature == feature } ?: install(feature, log)

    private fun install(feature: Int, log: (String) -> Unit): Jdk {
        val asset = downloader.json<List<Asset>>("$API/assets/latest/$feature/hotspot?os=linux&architecture=$arch&image_type=jdk").first()
        val pkg = asset.binary.`package`
        log("Downloading Java $feature (${asset.release_name})")
        val archive = downloader.file(pkg.link, dir.resolve("downloads").resolve(pkg.name), sha256 = pkg.checksum)
        val target = dir.resolve("temurin-$feature").createDirectories()
        val process = ProcessBuilder("tar", "-xzf", archive.toString(), "-C", target.toString(), "--strip-components=1")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "Extracting Java $feature failed: $output" }
        Files.deleteIfExists(archive)
        log("Installed Java $feature in $target")
        return inspect(target) ?: error("Java $feature in $target is not usable")
    }

    private fun searchRoots(): List<Path> {
        val home = System.getProperty("user.home")
        return listOf(dir, Path(home, ".jdks"), Path("/usr/lib/jvm"), Path(home, ".sdkman/candidates/java"))
    }

    private fun inspect(home: Path): Jdk? {
        val release = home.resolve("release")
        if (!release.exists() || !home.resolve("bin/java").isExecutable()) return null
        val version = release.readLines().firstOrNull { it.startsWith("JAVA_VERSION=") }?.substringAfter('=')?.trim('"') ?: return null
        return Jdk(featureOf(version), home)
    }

    private companion object {
        const val API = "https://api.adoptium.net/v3"

        val arch: String
            get() = when (val name = System.getProperty("os.arch")) {
                "amd64", "x86_64" -> "x64"
                "aarch64", "arm64" -> "aarch64"
                else -> name
            }

        fun featureOf(version: String): Int {
            val parts = version.split('.', '_', '+', '-')
            return if (parts[0] == "1") parts[1].toInt() else parts[0].toInt()
        }
    }
}
