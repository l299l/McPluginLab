package dev.mpl.core.database

import dev.mpl.core.download.Downloader
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/** The MariaDB programs an instance needs. [basedir] is set for downloaded builds and null for the system install. */
data class DbTools(val server: Path, val installDb: Path, val client: Path, val basedir: Path?)

/** Finds MariaDB on the PATH, or downloads the official Linux tarball into the cache. */
class DatabaseBinaries(private val cacheDir: Path, private val downloader: Downloader) {
    fun systemTools(): DbTools? {
        val server = onPath("mariadbd") ?: return null
        val install = onPath("mariadb-install-db") ?: return null
        val client = onPath("mariadb") ?: return null
        return DbTools(server, install, client, null)
    }

    /** The version a new instance gets when none is asked for: the system install, else [DEFAULT_VERSION]. */
    /** Versions offered in the UI: the system install first when there is one. */
    fun versions(): List<String> = listOfNotNull(SYSTEM.takeIf { systemTools() != null }) + DOWNLOADABLE

    fun defaultVersion(): String = if (systemTools() != null) SYSTEM else DEFAULT_VERSION

    fun resolve(version: String, log: (String) -> Unit = {}): DbTools {
        if (version == SYSTEM || version.isBlank()) {
            return systemTools() ?: error("MariaDB is not installed. Install it with your package manager (mariadb) or create the instance with a version such as $DEFAULT_VERSION to download it.")
        }
        require(VERSION.matches(version)) { "Invalid MariaDB version '$version'" }
        val home = cacheDir.resolve("mariadb-$version")
        if (!toolsIn(home).exists()) download(version, home, log)
        return DbTools(home.resolve("bin/mariadbd"), installScript(home), home.resolve("bin/mariadb"), home)
    }

    private fun toolsIn(home: Path) = home.resolve("bin/mariadbd")

    private fun installScript(home: Path): Path =
        listOf("bin/mariadb-install-db", "scripts/mariadb-install-db").map(home::resolve).firstOrNull { it.exists() }
            ?: error("No mariadb-install-db in $home")

    private fun download(version: String, home: Path, log: (String) -> Unit) {
        val base = "https://archive.mariadb.org/mariadb-$version/bintar-linux-systemd-x86_64"
        val name = "mariadb-$version-linux-systemd-x86_64.tar.gz"
        val sums = runCatching { downloader.text("$base/sha256sums.txt") }.getOrElse { throw IllegalArgumentException("MariaDB $version is not available for download ($base)") }
        val sha = sums.lineSequence().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size == 2 && it[1].removePrefix("*") == name }?.get(0)
            ?: throw IllegalArgumentException("No checksum for $name")
        cacheDir.createDirectories()
        log("downloading MariaDB $version (several hundred MB)")
        val archive = downloader.file("$base/$name", cacheDir.resolve(name), sha256 = sha)
        log("extracting MariaDB $version")
        val staging = cacheDir.resolve("mariadb-$version.extract").also { it.toFile().deleteRecursively(); it.createDirectories() }
        try {
            val tar = ProcessBuilder("tar", "-xzf", archive.toString(), "-C", staging.toString(), "--strip-components=1")
                .redirectErrorStream(true).start()
            val output = tar.inputStream.bufferedReader().readText()
            check(tar.waitFor() == 0) { "Extracting $name failed: $output" }
            home.toFile().deleteRecursively()
            staging.toFile().renameTo(home.toFile()) || error("Could not move MariaDB into $home")
        } finally {
            staging.toFile().deleteRecursively()
            archive.deleteIfExists()
        }
    }

    private fun onPath(program: String): Path? = System.getenv("PATH").orEmpty().split(':').filter { it.isNotBlank() }
        .map { Path(it).resolve(program) }.firstOrNull { it.isRegularFile() && it.isExecutable() }

    companion object {
        const val SYSTEM = "system"
        const val DEFAULT_VERSION = "11.8.3"
        val DOWNLOADABLE = listOf(DEFAULT_VERSION, "11.4.5", "10.11.11")
        private val VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
    }
}
