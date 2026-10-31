package dev.mpl.cli

import dev.mpl.api.MplClient
import dev.mpl.api.MplHome
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createDirectories

object Connection {
    fun client(): MplClient = runBlocking {
        MplClient.fromHome()?.takeIf { it.ping() }?.let { return@runBlocking it }
        startDaemon()
        repeat(60) {
            delay(250)
            MplClient.fromHome()?.takeIf { it.ping() }?.let { return@runBlocking it }
        }
        throw CliError("Daemon did not start, see ${MplHome.path.resolve("logs/daemon.log")}")
    }

    fun selfCommand(vararg args: String): List<String> {
        val java = ProcessHandle.current().info().command().orElse("java")
        return listOf(java, "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), "dev.mpl.cli.MainKt", *args)
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

class CliError(message: String) : RuntimeException(message)
