package dev.mpl.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import dev.mpl.api.BotSettings
import dev.mpl.api.ClientSettings
import dev.mpl.api.CreateInstanceRequest
import dev.mpl.api.CreateProjectRequest
import dev.mpl.api.DaemonConnection
import dev.mpl.api.InstanceId
import dev.mpl.api.InstanceInfo
import dev.mpl.api.InstanceType
import dev.mpl.api.MplClient
import dev.mpl.api.MplException
import dev.mpl.api.PluginLink
import dev.mpl.api.UpdateInstanceRequest
import dev.mpl.api.UpdateProjectRequest
import dev.mpl.daemon.Daemon
import dev.mpl.gui.runGui
import dev.mpl.mcp.LabTools
import dev.mpl.mcp.McpServer
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

abstract class ClientCommand(name: String, private val help: String) : CliktCommand(name) {
    protected val project by option("-P", "--project", envvar = "MPL_PROJECT", help = "Project of the instances").default("default")

    override fun help(context: Context) = help

    final override fun run() = runBlocking {
        DaemonConnection.connect().use { client ->
            if (client.isOutdated && this@ClientCommand !is Shutdown) echo(OUTDATED_WARNING, err = true)
            execute(client)
        }
    }

    abstract suspend fun execute(client: MplClient)

    protected fun ref(value: String) = InstanceId.parse(value, project)
}

const val OUTDATED_WARNING = "The daemon runs an older build of McPluginLab; run 'mpl shutdown' to restart it with this one."

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
    override suspend fun execute(client: MplClient) {
        echo(client.shutdown().message)
        ProcessHandle.of(client.info.pid).ifPresent { it.onExit().get(2, TimeUnit.MINUTES) }
    }
}

class Mcp : ClientCommand("mcp", "Run an MCP server on stdio for AI agents (claude mcp add mpl -- mpl mcp)") {
    override suspend fun execute(client: MplClient) = McpServer(LabTools(client) { DaemonConnection.connect() }.all, System.`in`.bufferedReader(), System.out).run()
}

class Gui : CliktCommand("gui") {
    override fun help(context: Context) = "Open the McPluginLab window"
    override fun run() {
        DaemonConnection.runFromRuntimeCopy("gui")?.let { exitProcess(it) }
        runBlocking { DaemonConnection.connect().use { client -> runGui(client) } }
    }
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

class ProjectSet : ClientCommand("set", "Change project settings") {
    private val name by argument()
    private val onlineMode by option("--online-mode", help = "Proxy and forwarding online mode (true/false)").boolean()
    private val dir by option("--dir", help = "Move the project's server folders here (instances must be stopped)")
    override suspend fun execute(client: MplClient) {
        val updated = client.updateProject(name, UpdateProjectRequest(onlineMode, dir))
        echo("${updated.def.name}: online-mode=${updated.def.onlineMode}, folder ${updated.def.dir}")
    }
}

class GroupCommand(name: String, private val help: String) : CliktCommand(name) {
    override fun help(context: Context) = help
    override fun run() = Unit
}

class PluginList : ClientCommand("ls", "List plugin links of a project") {
    override suspend fun execute(client: MplClient) {
        client.plugins(project).forEach { status ->
            val link = status.link
            echo("${link.name}: ${link.source} -> ${link.targets.ifEmpty { listOf("all servers") }.joinToString()}" +
                (if (link.autoDeploy) " [auto]" else "") + (if (link.restart) " [restart]" else ""))
            echo("    jar: ${status.jar ?: "none found"}" + (status.deployedJar?.let { ", deployed $it" } ?: "") + (status.error?.let { ", error: $it" } ?: ""))
        }
    }
}

class PluginAdd : ClientCommand("add", "Link a plugin jar (path, glob like target/MyPlugin-*.jar, or folder) to servers") {
    private val name by argument()
    private val source by argument()
    private val targets by option("-t", "--target", help = "Instance name; repeat for more (default: all servers)").multiple()
    private val manual by option("--manual", help = "Do not deploy automatically when the jar changes").flag()
    private val noRestart by option("--no-restart", help = "Do not restart running servers after deploying").flag()

    override suspend fun execute(client: MplClient) {
        val absolute = if (source.startsWith("/") || source.startsWith("~")) source else java.io.File(source).absolutePath
        client.savePlugin(project, PluginLink(name, absolute, targets, autoDeploy = !manual, restart = !noRestart))
        echo("Linked $name in $project")
    }
}

class PluginRemove : ClientCommand("rm", "Remove a plugin link") {
    private val name by argument()
    override suspend fun execute(client: MplClient) {
        client.removePlugin(project, name)
        echo("Removed $name from $project")
    }
}

class Deploy : ClientCommand("deploy", "Deploy linked plugins of a project now") {
    private val plugin by argument().optional()
    override suspend fun execute(client: MplClient) {
        client.deploy(project, plugin).forEach { echo("${it.link.name}: " + (it.error ?: "deployed ${it.deployedJar}")) }
    }
}

class NetworkSync : ClientCommand("sync", "Write proxy and backend forwarding config for a project") {
    override suspend fun execute(client: MplClient) {
        client.syncNetwork(project)
        echo("Network config of $project updated")
    }
}

class FileList : ClientCommand("ls", "List files of an instance") {
    private val name by argument(help = "[project/]name")
    private val path by argument().default("")
    override suspend fun execute(client: MplClient) =
        client.files(ref(name), path).forEach { echo(if (it.directory) "${it.path}/" else it.path) }
}

class FileRead : ClientCommand("cat", "Print a file of an instance") {
    private val name by argument(help = "[project/]name")
    private val path by argument()
    override suspend fun execute(client: MplClient) = echo(client.readFile(ref(name), path).text, trailingNewline = false)
}

class FileWrite : ClientCommand("write", "Replace a file of an instance with stdin") {
    private val name by argument(help = "[project/]name")
    private val path by argument()
    override suspend fun execute(client: MplClient) {
        client.writeFile(ref(name), path, System.`in`.readBytes().decodeToString())
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

class JavaList : ClientCommand("java", "List Java installations found or downloaded (Java is picked per instance automatically)") {
    override suspend fun execute(client: MplClient) = echo(client.javaInstallations().joinToString("\n").ifEmpty { "No Java installations found" })
}

class Versions : ClientCommand("versions", "List versions of paper, folia, purpur, spigot, velocity, waterfall, bungeecord, soulfire or minecraft") {
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
    private val standalone by option("--standalone", help = "Keep this server out of the project's proxy network").flag()
    private val target by option("--target", help = "Bots/client: instance to connect to (proxy or server)")
    private val username by option("--username", help = "Client: offline player name")
    private val count by option("--count", help = "Bots: number of bots").int().default(1)
    private val prefix by option("--prefix", help = "Bots: name prefix").default("Bot")
    private val accounts by option("--accounts", help = "Bots: comma separated names instead of prefix+count")
    private val protocol by option("--protocol", help = "Bots: protocol version (default: target server version)")

    override suspend fun execute(client: MplClient) {
        val id = ref(name)
        echo("Creating $id...")
        val bots = target?.takeIf { type == InstanceType.SOULFIRE }?.let { BotSettings(it, count, prefix, accounts?.split(',')?.map(String::trim).orEmpty(), protocol) }
        val player = if (type == InstanceType.CLIENT) ClientSettings(username ?: id.name, target) else null
        val request = CreateInstanceRequest(
            project = id.project, name = id.name, type = type, version = version, build = build, dir = dir,
            port = port, memory = memory, java = java, jar = jar, acceptEula = true, offlineMode = !online, bots = bots, client = player, proxied = !standalone,
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
    private val wait by option("-w", "--wait", help = "Wait until the instance is ready").flag()
    private val expect by option("-e", "--expect", help = "Also wait for a log line matching this regex")
    private val expectIn by option("--expect-in", help = "[project/]name whose log to watch for --expect (default: the instance)")
    private val timeout by option("--timeout", help = "Seconds to wait").long().default(180)

    override suspend fun execute(client: MplClient) {
        for (id in names.map(::ref)) {
            val watched = expectIn?.let(::ref) ?: id
            val since = client.instance(id).nextLogSeq
            val expectSince = client.instance(watched).nextLogSeq
            val info = client.action(id, action)
            if ((wait || expect != null) && action in setOf("start", "restart")) {
                val ready = client.waitFor(id, info.def.effectiveReadyPattern, since, timeout * 1000)
                if (!ready.matched) throw MplException("$id did not become ready (state ${client.instance(id).state})")
            }
            echo(client.instance(id).describe())
            expect?.let { pattern ->
                val result = client.waitFor(watched, pattern, expectSince, timeout * 1000)
                if (!result.matched) throw MplException("No line matching '$pattern' in $watched within ${timeout}s")
                echo(result.line!!.text)
            }
        }
    }
}

open class ProjectActionCommand(name: String, private val action: String, help: String) : ClientCommand(name, help) {
    private val name by argument(help = "Project")
    private val only by argument(help = "Only these instances (default: all)").multiple()
    private val noWait by option("--no-wait", help = "Return immediately").flag()

    override suspend fun execute(client: MplClient) {
        client.group(name, action, only, wait = !noWait)
            .forEach { if (only.isEmpty() || it.def.name in only) echo(it.describe()) }
    }
}

class Up : ProjectActionCommand("up", "start", "Start a project: proxies and servers first, bots and clients once those are ready")
class Down : ProjectActionCommand("down", "stop", "Stop a project: bots and clients first, then proxies, then servers")

class SetInstance : ClientCommand("set", "Change instance settings") {
    private val name by argument(help = "[project/]name")
    private val proxied by option("--proxied", help = "Include the server in the project's proxy network (true/false)").boolean()
    private val port by option("-p", "--port").int()
    private val memory by option("-m", "--memory")
    override suspend fun execute(client: MplClient) {
        val info = client.updateInstance(ref(name), UpdateInstanceRequest(proxied, port, memory))
        echo("${info.describe()}  memory=${info.def.memory}  proxied=${info.def.proxied}")
    }
}

class Start : ActionCommand("start", "Start instances")
class Stop : ActionCommand("stop", "Stop instances gracefully")
class Restart : ActionCommand("restart", "Restart instances")
class Kill : ActionCommand("kill", "Kill instances")

abstract class SendingCommand(name: String, help: String) : ClientCommand(name, help) {
    private val expect by option("-e", "--expect", help = "Wait for a log line matching this regex after sending")
    private val expectIn by option("--expect-in", help = "[project/]name whose log to watch (default: the target)")
    private val timeout by option("--timeout", help = "Seconds to wait for --expect").long().default(30)

    protected suspend fun sendAndExpect(client: MplClient, target: InstanceId, line: String) {
        val watched = expectIn?.let(::ref) ?: target
        val result = client.sendAndExpect(target, line, watched, expect, timeout * 1000) ?: return
        if (!result.matched) throw MplException("No line matching '$expect' in $watched within ${timeout}s")
        echo(result.line!!.text)
    }
}

class Send : SendingCommand("send", "Send a console command") {
    private val name by argument(help = "[project/]name")
    private val command by argument().multiple(required = true)
    override suspend fun execute(client: MplClient) = sendAndExpect(client, ref(name), command.joinToString(" "))
}

class Bot : SendingCommand("bot", "Send a SoulFire command to bots, e.g. mpl bot nbw/bots -b Bot1 say /bw join") {
    private val name by argument(help = "[project/]name of the bot instance")
    private val bots by option("-b", "--bots", help = "Comma separated bot names (default: all)")
    private val command by argument().multiple(required = true)
    override suspend fun execute(client: MplClient) {
        val line = command.joinToString(" ")
        sendAndExpect(client, ref(name), bots?.let { "bot $it $line" } ?: line)
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
    DaemonConnection.markRuntimeInUse()
    try {
        Mpl().subcommands(
            DaemonCommand(), Shutdown(), Gui(), Mcp(),
            GroupCommand("project", "Manage projects").subcommands(ProjectList(), ProjectCreate(), ProjectRemove(), ProjectSet()),
            GroupCommand("plugin", "Manage plugin links (use -P project)").subcommands(PluginList(), PluginAdd(), PluginRemove()),
            Deploy(),
            GroupCommand("network", "Proxy network configuration (use -P project)").subcommands(NetworkSync()),
            GroupCommand("file", "Read and write instance files").subcommands(FileList(), FileRead(), FileWrite()),
            ListInstances(), Versions(), JavaList(), Create(), Remove(), SetInstance(), Up(), Down(),
            Start(), Stop(), Restart(), Kill(), Send(), Bot(), Logs(), Wait(), Attach(), Open(),
        ).main(args)
    } catch (e: MplException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    exitProcess(0)
}
