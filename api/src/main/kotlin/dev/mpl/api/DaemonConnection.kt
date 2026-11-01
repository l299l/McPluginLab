package dev.mpl.api

import kotlinx.coroutines.delay
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

object DaemonConnection {
    private const val MAIN_CLASS = "dev.mpl.cli.MainKt"
    private const val KEPT_RUNTIMES = 3

    private val classpath: List<Path> by lazy { System.getProperty("java.class.path").split(File.pathSeparator).map { Path(it) } }

    val build: String by lazy {
        System.getProperty("mpl.build")?.let { return@lazy it }
        val digest = MessageDigest.getInstance("SHA-256")
        classpath.filter { it.isRegularFile() }.forEach {
            digest.update("${it.fileName}:${it.fileSize()}:${it.getLastModifiedTime().toMillis()}".toByteArray())
        }
        digest.digest().toHexString().take(16)
    }

    suspend fun connect(): MplClient {
        MplClient.fromHome()?.takeIf { it.ping() }?.let { return it }
        startDaemon()
        repeat(60) {
            delay(250)
            MplClient.fromHome()?.takeIf { it.ping() }?.let { return it }
        }
        throw MplException("Daemon did not start, see ${MplHome.path.resolve("logs/daemon.log")}")
    }

    fun selfCommand(vararg args: String, classpath: String = System.getProperty("java.class.path")): List<String> {
        val java = ProcessHandle.current().info().command().orElse("java")
        return listOf(java, "--enable-native-access=ALL-UNNAMED", "-Dmpl.build=$build", "-cp", classpath, MAIN_CLASS, *args)
    }

    fun runFromRuntimeCopy(vararg args: String): Int? {
        if (runtimeDir() != null) return null
        return ProcessBuilder(selfCommand(*args, classpath = runtimeClasspath())).inheritIO().start().waitFor()
    }

    fun markRuntimeInUse() {
        runtimeDir()?.resolve(".pid-${ProcessHandle.current().pid()}")?.let { if (!it.exists()) it.createFile() }
    }

    private fun runtimeDir(): Path? = classpath.firstOrNull()?.parent?.takeIf { it.parent == MplHome.cache.resolve("runtime") }

    fun openInTerminal(id: InstanceId, terminal: String = System.getenv("MPL_TERMINAL") ?: "konsole") {
        ProcessBuilder(listOf("setsid", terminal, "-e") + selfCommand("attach", id.toString()))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectErrorStream(true)
            .start()
    }

    private fun startDaemon() {
        val logs = MplHome.path.resolve("logs").createDirectories()
        ProcessBuilder(listOf("setsid") + selfCommand("daemon", classpath = runtimeClasspath()))
            .redirectInput(File("/dev/null"))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve("daemon.log").toFile()))
            .redirectErrorStream(true)
            .start()
    }

    private fun runtimeClasspath(): String {
        val runtimes = MplHome.cache.resolve("runtime")
        val dir = runtimes.resolve(build)
        val marker = dir.resolve(".complete")
        if (!marker.exists()) {
            dir.createDirectories()
            classpath.filter { it.isRegularFile() }.forEach { it.copyTo(dir.resolve(it.fileName), overwrite = true) }
            marker.createFile()
        }
        pruneRuntimes(runtimes)
        return classpath.joinToString(File.pathSeparator) { if (it.isRegularFile()) dir.resolve(it.fileName).toString() else it.toString() }
    }

    private fun pruneRuntimes(runtimes: Path) {
        runtimes.listDirectoryEntries()
            .sortedByDescending { it.getLastModifiedTime() }
            .drop(KEPT_RUNTIMES)
            .filterNot(::inUse)
            .forEach { it.toFile().deleteRecursively() }
    }

    private fun inUse(runtime: Path): Boolean = runtime.listDirectoryEntries(".pid-*").any { marker ->
        val alive = marker.fileName.toString().removePrefix(".pid-").toLongOrNull()?.let { ProcessHandle.of(it).isPresent } == true
        alive.also { if (!it) marker.deleteIfExists() }
    }
}
