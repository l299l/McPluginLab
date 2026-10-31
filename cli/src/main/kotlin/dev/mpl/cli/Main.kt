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
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import dev.mpl.api.CreateInstanceRequest
import dev.mpl.api.CreateProjectRequest
import dev.mpl.api.DaemonConnection
import dev.mpl.api.InstanceId
import dev.mpl.api.InstanceInfo
import dev.mpl.api.InstanceType
import dev.mpl.api.MplClient
import dev.mpl.api.MplException
import dev.mpl.daemon.Daemon
import dev.mpl.gui.runGui
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

abstract class ClientCommand(name: String, private val help: String) : CliktCommand(name) {
    protected val project by option("-P", "--project", envvar = "MPL_PROJECT", help = "Project of the instances").default("default")

    override fun help(context: Context) = help

    final override fun run() = runBlocking { DaemonConnection.connect().use { execute(it) } }

    abstract suspend fun execute(client: MplClient)

    protected fun ref(value: String) = InstanceId.parse(value, project)
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

class Gui : ClientCommand("gui", "Open the McPluginLab window") {
    override suspend fun execute(client: MplClient) = runGui(client)
}

class ProjectCommand : CliktCommand("project") {
    override fun help(context: Context) = "Manage projects"
    override fun run() = Unit
}

class ProjectList : ClientCommand("ls", "List projects") {
    override suspend fun execute(client: MplClient) {
        echo("%-20s %-10s %-8s %s".format("PROJECT", "INSTANCES", "RUNNING", "FOLDER"))
        client.projects().forEach { echo("%-20s %-10d %-8d %s".format(it.def.name, it.instances, it.running, it.def.dir)) }
    }
}

class ProjectCreate : ClientCommand("create", "Create a project") {
    private val name by argument()
    private val dir by option("--dir", help = "Folder for the project's servers")
    override suspend fun execute(client: MplClient) {
        val created = client.createProject(CreateProjectRequest(name, dir))
        echo("Created project ${created.def.name} in ${created.def.dir}")
    }
}

class ProjectRemove : ClientCommand("rm", "Remove a project and its instances") {
    private val name by argument()
    private val deleteFiles by option("--delete-files", help = "Also delete the server folders").flag()
    override suspend fun execute(client: MplClient) {
        client.removeProject(name, deleteFiles)
        echo("Removed project $name")
    }
}

class ListInstances : ClientCommand("ls", "List instances of all projects") {
    override suspend fun execute(client: MplClient) {
        val rows = client.instances()
        if (rows.isEmpty()) return echo("No instances. Create one with: mpl create <project>/<name> --type paper")
        echo("%-28s %-9s %-10s %-6s %-9s %s".format("INSTANCE", "TYPE", "VERSION", "PORT", "STATE", "PID"))
        rows.forEach { i ->
            echo("%-28s %-9s %-10s %-6s %-9s %s".format(i.def.id, i.def.type.name.lowercase(), i.def.version, i.def.port ?: "-", i.state, i.pid ?: "-"))
        }
    }
}

class Versions : ClientCommand("versions", "List available versions of paper, folia, velocity or waterfall") {
    private val software by argument()
    override suspend fun execute(client: MplClient) = echo(client.versions(software).versions.joinToString("\n"))
}

class Create : ClientCommand("create", "Create an instance, downloading the server jar (accepts the Minecraft EULA)") {
    private val name by argument(help = "[project/]name")
    private val type by option("-t", "--type").enum<InstanceType> { it.name.lowercase() }.default(InstanceType.PAPER)
    private val version by option("-v", "--version", help = "Defaults to the latest stable version")
    private val build by option("--build").int()
    private val dir by option("--dir", help = "Use an existing directory")
    private val jar by option("--jar", help = "Use an existing jar in --dir instead of downloading")
    private val port by option("-p", "--port").int()
    private val memory by option("-m", "--memory")
    private val java by option("--java", help = "Java executable")
    private val online by option("--online", help = "Keep online-mode=true").flag()

    override suspend fun execute(client: MplClient) {
        val id = ref(name)
        echo("Creating $id...")
        val request = CreateInstanceRequest(
            project = id.project, name = id.name, type = type, version = version, build = build, dir = dir,
            port = port, memory = memory, java = java, jar = jar, acceptEula = true, offlineMode = !online,
        )
        val info = client.create(request)
        echo("Created ${info.describe()} in ${info.def.dir}")
    }
}

class Remove : ClientCommand("rm", "Remove an instance") {
    private val name by argument(help = "[project/]name")
    private val deleteFiles by option("--delete-files", help = "Also delete the server directory").flag()
    override suspend fun execute(client: MplClient) {
        client.remove(ref(name), deleteFiles)
        echo("Removed ${ref(name)}")
    }
}

open class ActionCommand(private val action: String, help: String) : ClientCommand(action, help) {
    private val names by argument(help = "[project/]name").multiple(required = true)
    private val wait by option("-w", "--wait", help = "Wait until the server is ready").flag()
    private val timeout by option("--timeout", help = "Seconds to wait").long().default(180)

    override suspend fun execute(client: MplClient) {
        for (id in names.map(::ref)) {
            val since = client.instance(id).nextLogSeq
            val info = client.action(id, action)
            if (wait && action in setOf("start", "restart")) {
                val result = client.waitFor(id, info.def.effectiveReadyPattern, since, timeout * 1000)
                if (!result.matched) throw MplException("$id did not become ready (state ${client.instance(id).state})")
            }
            echo(client.instance(id).describe())
        }
    }
}

class Start : ActionCommand("start", "Start instances")
class Stop : ActionCommand("stop", "Stop instances gracefully")
class Restart : ActionCommand("restart", "Restart instances")
class Kill : ActionCommand("kill", "Kill instances")

class Send : ClientCommand("send", "Send a console command") {
    private val name by argument(help = "[project/]name")
    private val command by argument().multiple(required = true)
    override suspend fun execute(client: MplClient) {
        client.command(ref(name), command.joinToString(" "))
    }
}

class Logs : ClientCommand("logs", "Print console output") {
    private val name by argument(help = "[project/]name")
    private val lines by option("-n", "--lines").long().default(50)
    private val follow by option("-f", "--follow").flag()

    override suspend fun execute(client: MplClient) {
        val id = ref(name)
        var page = client.log(id, -lines)
        page.lines.forEach { echo(it.text) }
        while (follow) {
            page = client.log(id, page.next, waitMs = 25_000)
            page.lines.forEach { echo(it.text) }
        }
    }
}

class Wait : ClientCommand("wait", "Wait for a console line matching a regex") {
    private val name by argument(help = "[project/]name")
    private val pattern by argument()
    private val timeout by option("--timeout", help = "Seconds").long().default(60)
    private val since by option("--since", help = "Log cursor; defaults to new output only").long()

    override suspend fun execute(client: MplClient) {
        val result = client.waitFor(ref(name), pattern, since, timeout * 1000)
        if (!result.matched) {
            echo("No match within ${timeout}s", err = true)
            throw ProgramResult(1)
        }
        echo(result.line!!.text)
    }
}

class Attach : ClientCommand("attach", "Attach this terminal to an instance console") {
    private val name by argument(help = "[project/]name")
    override suspend fun execute(client: MplClient) = attach(client, ref(name))
}

class Open : ClientCommand("open", "Open an instance console in a new terminal window") {
    private val name by argument(help = "[project/]name")
    private val terminal by option("--terminal", envvar = "MPL_TERMINAL").default("konsole")

    override suspend fun execute(client: MplClient) {
        client.instance(ref(name))
        DaemonConnection.openInTerminal(ref(name), terminal)
    }
}

private fun InstanceInfo.describe() =
    "${def.id} (${def.type.name.lowercase()} ${def.version}${def.build?.let { " #$it" } ?: ""}, port ${def.port ?: "-"}): $state"

fun main(args: Array<String>) {
    try {
        Mpl().subcommands(
            DaemonCommand(), Shutdown(), Gui(),
            ProjectCommand().subcommands(ProjectList(), ProjectCreate(), ProjectRemove()),
            ListInstances(), Versions(), Create(), Remove(),
            Start(), Stop(), Restart(), Kill(), Send(), Logs(), Wait(), Attach(), Open(),
        ).main(args)
    } catch (e: MplException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    exitProcess(0)
}
