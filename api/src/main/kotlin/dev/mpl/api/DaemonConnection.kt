package dev.mpl.api

import kotlinx.coroutines.delay
import java.io.File
import kotlin.io.path.createDirectories

object DaemonConnection {
    private const val MAIN_CLASS = "dev.mpl.cli.MainKt"

    suspend fun connect(): MplClient {
        MplClient.fromHome()?.takeIf { it.ping() }?.let { return it }
        startDaemon()
        repeat(60) {
            delay(250)
            MplClient.fromHome()?.takeIf { it.ping() }?.let { return it }
        }
        throw MplException("Daemon did not start, see ${MplHome.path.resolve("logs/daemon.log")}")
    }

    fun selfCommand(vararg args: String): List<String> {
        val java = ProcessHandle.current().info().command().orElse("java")
        return listOf(java, "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), MAIN_CLASS, *args)
    }

    fun openInTerminal(name: String, terminal: String = System.getenv("MPL_TERMINAL") ?: "konsole") {
        ProcessBuilder(listOf("setsid", terminal, "-e") + selfCommand("attach", name))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectErrorStream(true)
            .start()
    }

    private fun startDaemon() {
        val logs = MplHome.path.resolve("logs").createDirectories()
        ProcessBuilder(listOf("setsid") + selfCommand("daemon"))
            .redirectInput(File("/dev/null"))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve("daemon.log").toFile()))
            .redirectErrorStream(true)
            .start()
    }
}
