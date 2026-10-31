package dev.mpl.core.download

import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class Downloader {
    val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    fun text(url: String): String {
        val response = http.send(request(url), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()} for $url" }
        return response.body()
    }

    inline fun <reified T> json(url: String): T = json.decodeFromString(text(url))

    fun file(url: String, target: Path, sha256: String? = null, sha1: String? = null): Path {
        if (target.exists() && verified(target, sha256, sha1) && (sha256 != null || sha1 != null)) return target
        target.parent.createDirectories()
        val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".part")
        try {
            val response = http.send(request(url), HttpResponse.BodyHandlers.ofFile(tmp))
            check(response.statusCode() == 200) { "HTTP ${response.statusCode()} for $url" }
            check(verified(tmp, sha256, sha1)) { "Checksum mismatch for $url" }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
        return target
    }

    fun verified(path: Path, sha256: String?, sha1: String?): Boolean = when {
        sha256 != null -> digest(path, "SHA-256") == sha256.lowercase()
        sha1 != null -> digest(path, "SHA-1") == sha1.lowercase()
        else -> true
    }

    private fun digest(path: Path, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
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

    private fun request(url: String) = HttpRequest.newBuilder(URI(url)).header("User-Agent", USER_AGENT).build()

    private companion object {
        const val USER_AGENT = "McPluginLab/0.1"
    }
}
