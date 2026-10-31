package dev.mpl.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import dev.mpl.api.CreateInstanceRequest
import dev.mpl.api.DaemonConnection
import dev.mpl.api.InstanceInfo
import dev.mpl.api.InstanceType
import dev.mpl.api.MplClient
import dev.mpl.api.MplException
import dev.mpl.daemon.Daemon
import dev.mpl.gui.runGui
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

abstract class ClientCommand(name: String, private val help: String) : CliktCommand(name) {
    override fun help(context: Context) = help

    final override fun run() = runBlocking { DaemonConnection.connect().use { execute(it) } }

    abstract suspend fun execute(client: MplClient)
}

class Mpl : CliktCommand("mpl") {
    override fun help(context: Context) = "McPluginLab: run and test Minecraft servers, proxies and plugins"
    override fun run() = Unit
}

class DaemonCommand : CliktCommand("daemon") {
    override fun help(context: Context) = "Run the daemon in the foreground"
    override fun run() {
        Daemon().run()
        exitProcess(0)
    }
}

class Shutdown : ClientCommand("shutdown", "Stop all instances and the daemon") {
    override suspend fun execute(client: MplClient) = echo(client.shutdown().message)
}

class ListInstances : ClientCommand("ls", "List instances") {
    override suspend fun execute(client: MplClient) {
        val rows = client.instances()
        if (rows.isEmpty()) return echo("No instances. Create one with: mpl create <name> --type paper --accept-eula")
        echo("%-16s %-9s %-10s %-6s %-9s %s".format("NAME", "TYPE", "VERSION", "PORT", "STATE", "PID"))
        rows.forEach { i ->
            echo("%-16s %-9s %-10s %-6s %-9s %s".format(i.def.name, i.def.type.name.lowercase(), i.def.version, i.def.port ?: "-", i.state, i.pid ?: "-"))
        }
    }
}

class Versions : ClientCommand("versions", "List available versions of paper, folia, velocity or waterfall") {
    private val project by argument()
    override suspend fun execute(client: MplClient) = echo(client.versions(project).versions.joinToString("\n"))
}

class Create : ClientCommand("create", "Create an instance, downloading the server jar") {
    private val name by argument()
    private val type by option("-t", "--type").enum<InstanceType> { it.name.lowercase() }.default(InstanceType.PAPER)
    private val version by option("-v", "--version", help = "Defaults to the latest stable version")
    private val build by option("--build").int()
    private val dir by option("--dir", help = "Use an existing directory")
    private val jar by option("--jar", help = "Use an existing jar in --dir instead of downloading")
    private val port by option("-p", "--port").int()
    private val memory by option("-m", "--memory")
    private val java by option("--java", help = "Java executable")
    private val acceptEula by option("--accept-eula", help = "Accept the Minecraft EULA (https://aka.ms/MinecraftEULA)").flag()
    private val offline by option("--offline", help = "Set online-mode=false").flag()

    override suspend fun execute(client: MplClient) {
        echo("Creating $name...")
        val info = client.create(CreateInstanceRequest(name, type, version, build, dir, port, memory, java, jar, acceptEula, offline))
        echo("Created ${info.describe()} in ${info.def.dir}")
    }
}

class Remove : ClientCommand("rm", "Remove an instance") {
    private val name by argument()
    private val deleteFiles by option("--delete-files", help = "Also delete the server directory").flag()
    override suspend fun execute(client: MplClient) {
        client.remove(name, deleteFiles)
        echo("Removed $name")
    }
}

open class ActionCommand(private val action: String, help: String) : ClientCommand(action, help) {
    private val names by argument().multiple(required = true)
    private val wait by option("-w", "--wait", help = "Wait until the server is ready").flag()
    private val timeout by option("--timeout", help = "Seconds to wait").long().default(180)

    override suspend fun execute(client: MplClient) {
        for (name in names) {
            val since = client.instance(name).nextLogSeq
            val info = client.action(name, action)
            if (wait && action in setOf("start", "restart")) {
                val result = client.waitFor(name, info.def.effectiveReadyPattern, since, timeout * 1000)
                if (!result.matched) throw MplException("$name did not become ready (state ${client.instance(name).state})")
            }
            echo(client.instance(name).describe())
        }
    }
}

class Start : ActionCommand("start", "Start instances")
class Stop : ActionCommand("stop", "Stop instances gracefully")
class Restart : ActionCommand("restart", "Restart instances")
class Kill : ActionCommand("kill", "Kill instances")

class Send : ClientCommand("send", "Send a console command") {
    private val name by argument()
    private val command by argument().multiple(required = true)
    override suspend fun execute(client: MplClient) {
        client.command(name, command.joinToString(" "))
    }
}

class Logs : ClientCommand("logs", "Print console output") {
    private val name by argument()
    private val lines by option("-n", "--lines").long().default(50)
    private val follow by option("-f", "--follow").flag()

    override suspend fun execute(client: MplClient) {
        var page = client.log(name, -lines)
        page.lines.forEach { echo(it.text) }
        while (follow) {
            page = client.log(name, page.next, waitMs = 25_000)
            page.lines.forEach { echo(it.text) }
        }
    }
}

class Wait : ClientCommand("wait", "Wait for a console line matching a regex") {
    private val name by argument()
    private val pattern by argument()
    private val timeout by option("--timeout", help = "Seconds").long().default(60)
    private val since by option("--since", help = "Log cursor; defaults to new output only").long()

    override suspend fun execute(client: MplClient) {
        val result = client.waitFor(name, pattern, since, timeout * 1000)
        if (!result.matched) {
            echo("No match within ${timeout}s", err = true)
            throw ProgramResult(1)
        }
        echo(result.line!!.text)
    }
}

class Attach : ClientCommand("attach", "Attach this terminal to an instance console") {
    private val name by argument()
    override suspend fun execute(client: MplClient) = attach(client, name)
}

class Open : ClientCommand("open", "Open an instance console in a new terminal window") {
    private val name by argument()
    private val terminal by option("--terminal", envvar = "MPL_TERMINAL").default("konsole")

    override suspend fun execute(client: MplClient) {
        client.instance(name)
        DaemonConnection.openInTerminal(name, terminal)
    }
}

class Gui : ClientCommand("gui", "Open the McPluginLab window") {
    override suspend fun execute(client: MplClient) = runGui(client)
}

private fun InstanceInfo.describe() =
    "${def.name} (${def.type.name.lowercase()} ${def.version}${def.build?.let { " #$it" } ?: ""}, port ${def.port ?: "-"}): $state"

fun main(args: Array<String>) {
    try {
        Mpl().subcommands(
            DaemonCommand(), Shutdown(), ListInstances(), Versions(), Create(), Remove(),
            Start(), Stop(), Restart(), Kill(), Send(), Logs(), Wait(), Attach(), Open(), Gui(),
        ).main(args)
    } catch (e: MplException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    exitProcess(0)
}
