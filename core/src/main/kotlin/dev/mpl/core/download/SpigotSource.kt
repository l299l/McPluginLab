package dev.mpl.core.download

import dev.mpl.core.java.JavaRequirements
import dev.mpl.core.java.JdkManager
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class SpigotSource(private val downloader: Downloader, private val cache: Path, private val jdks: JdkManager) : ServerSource {
    override fun versions(): List<String> =
        VERSION_FILE.findAll(downloader.text("https://hub.spigotmc.org/versions/"))
            .map { it.groupValues[1] }
            .distinct()
            .sortedWith(compareByDescending<String> { versionKey(it) })
            .toList()

    @Synchronized
    override fun download(version: String?, build: Int?, log: (String) -> Unit): ResolvedJar {
        val resolved = version ?: versions().first()
        val target = cache.resolve("spigot").resolve("spigot-$resolved.jar")
        if (target.exists()) return ResolvedJar(target, resolved, null)

        val work = cache.resolve("buildtools").createDirectories()
        val buildTools = downloader.file(BUILD_TOOLS, work.resolve("BuildTools.jar"))
        val java = jdks.resolve(JavaRequirements.forMinecraft(resolved), log).java
        val logFile = work.resolve("build-$resolved.log").toFile()
        log("Building Spigot $resolved with BuildTools, this takes a few minutes (log: $logFile)")
        val process = ProcessBuilder(java.toString(), "-jar", buildTools.toString(), "--rev", resolved, "--output-dir", work.toString(), "--nogui")
            .directory(work.toFile())
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .start()
        check(process.waitFor() == 0) { "BuildTools failed for $resolved, see $logFile" }
        work.resolve("spigot-$resolved.jar").copyTo(target.also { it.parent.createDirectories() }, overwrite = true)
        return ResolvedJar(target, resolved, null)
    }

    private fun versionKey(version: String) = version.split('.').map { it.toInt() }.let { (it + listOf(0, 0, 0)).take(3) }
        .fold(0L) { acc, part -> acc * 1000 + part }

    private companion object {
        const val BUILD_TOOLS = "https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar"
        val VERSION_FILE = Regex("""href="(\d+\.\d+(?:\.\d+)?)\.json"""")
    }
}
