package dev.mpl.mcp

import dev.mpl.api.BotSettings
import dev.mpl.api.CreateInstanceRequest
import dev.mpl.api.InstanceId
import dev.mpl.api.InstanceInfo
import dev.mpl.api.InstanceType
import dev.mpl.api.LogLine
import dev.mpl.api.MplClient
import dev.mpl.api.PluginStatus

class LabTools(private val client: MplClient) {
    val all: List<Tool> = listOf(
        tool("list_projects", "List projects with their folders and instance counts.") {
            client.projects().joinToString("\n") { "${it.def.name}: ${it.instances} instances, ${it.running} running, folder ${it.def.dir}, online-mode=${it.def.onlineMode}" }
                .ifEmpty { "No projects." }
        },

        tool("list_instances", "List servers, proxies and bot instances with state, port and log cursor.", {
            string("project", "Only this project", required = false)
        }) { args ->
            client.instances(args.optString("project")).joinToString("\n") { it.describe() }.ifEmpty { "No instances." }
        },

        tool("create_instance", "Create a server (paper/folia), proxy (velocity/waterfall) or SoulFire bot instance (soulfire). Downloads the jar.", {
            string("instance", "project/name")
            string("type", "paper, folia, velocity, waterfall or soulfire")
            string("version", "Minecraft/proxy/SoulFire version, default latest stable", required = false)
            integer("port", "Port, default next free")
            string("bot_target", "soulfire: instance name in the project to connect to (proxy or server)", required = false)
            integer("bot_count", "soulfire: number of bots (default 1)")
            string("bot_prefix", "soulfire: bot name prefix (default Bot)", required = false)
            string("jar", "Existing jar path instead of downloading", required = false)
        }) { args ->
            val id = args.instance()
            val type = InstanceType.valueOf(args.string("type").uppercase())
            val bots = args.optString("bot_target")?.let { BotSettings(it, args.optInt("bot_count") ?: 1, args.optString("bot_prefix") ?: "Bot") }
            val request = CreateInstanceRequest(
                project = id.project, name = id.name, type = type, version = args.optString("version"),
                port = args.optInt("port"), jar = args.optString("jar"), offlineMode = true, bots = bots,
            )
            "Created " + client.create(request).describe()
        },

        tool("start", "Start an instance. With wait_ready (default true) returns once it is ready (servers: 'Done', bots: accounts loaded).", {
            string("instance", "project/name")
            boolean("wait_ready", "Wait until ready (default true)")
            integer("timeout_seconds", "Default 180")
        }) { args -> lifecycle(args, "start") },

        tool("restart", "Restart an instance, optionally waiting until ready.", {
            string("instance", "project/name")
            boolean("wait_ready", "Wait until ready (default true)")
            integer("timeout_seconds", "Default 180")
        }) { args -> lifecycle(args, "restart") },

        tool("stop", "Stop an instance gracefully and wait until it exited.", {
            string("instance", "project/name")
        }) { args -> "Stopped " + client.action(args.instance(), "stop").describe() },

        tool("kill", "Kill an instance immediately.", {
            string("instance", "project/name")
        }) { args -> "Killed " + client.action(args.instance(), "kill").describe() },

        tool("send_command", "Send a console command. With `expect`, waits for a matching log line (regex) in `expect_in` (default same instance) that appears after sending.", {
            string("instance", "project/name")
            string("command", "Console command without leading slash")
            string("expect", "Regex to wait for", required = false)
            string("expect_in", "project/name whose log to watch", required = false)
            integer("timeout_seconds", "Default 30")
        }) { args -> sendAndExpect(args, args.string("command")) },

        tool("bot_command", "Send a SoulFire command to bots of a soulfire instance, e.g. `say /bw join`, `say hello`, `move`, `lookat`, `click left`, `useitem`, `inventory`, `respawn`, `health`. Bots must have joined first.", {
            string("instance", "project/name of the soulfire instance")
            string("command", "SoulFire command")
            string("bots", "Comma separated bot names, default all bots", required = false)
            string("expect", "Regex to wait for", required = false)
            string("expect_in", "project/name whose log to watch, e.g. the lobby server", required = false)
            integer("timeout_seconds", "Default 30")
        }) { args ->
            val command = args.string("command")
            sendAndExpect(args, args.optString("bots")?.let { "bot $it $command" } ?: command)
        },

        tool("read_log", "Read console output. Pass `since` (cursor from an earlier call) to get only new lines; otherwise the last `lines` lines.", {
            string("instance", "project/name")
            integer("since", "Log cursor")
            integer("lines", "Number of recent lines when no cursor is given (default 100)")
        }) { args ->
            val since = args.optLong("since") ?: -(args.optLong("lines") ?: 100)
            val page = client.log(args.instance(), since, limit = 1000)
            page.lines.joinToString("\n") { it.text } + "\n[cursor ${page.next}]"
        },

        tool("wait_for_log", "Wait for a console line matching a regex. Without `since` only lines after the call count; pass a cursor to include earlier lines.", {
            string("instance", "project/name")
            string("pattern", "Regex")
            integer("since", "Log cursor")
            integer("timeout_seconds", "Default 60")
        }) { args ->
            val result = client.waitFor(args.instance(), args.string("pattern"), args.optLong("since"), (args.optLong("timeout_seconds") ?: 60) * 1000)
            if (result.matched) "${result.line!!.text}\n[cursor ${result.next}]" else throw IllegalStateException("No match within timeout [cursor ${result.next}]")
        },

        tool("list_plugins", "List plugin links of a project: source, targets, current jar, last deploy.", {
            string("project", "Project")
        }) { args -> client.plugins(args.string("project")).joinToString("\n") { it.describe() }.ifEmpty { "No plugin links." } },

        tool("deploy_plugins", "Copy the newest built jar of linked plugins into their target servers now; running targets restart if the link says so.", {
            string("project", "Project")
            string("plugin", "Only this plugin link", required = false)
        }) { args -> client.deploy(args.string("project"), args.optString("plugin")).joinToString("\n") { it.describe() } },

        tool("sync_network", "Rewrite Velocity and backend forwarding config of a project and reload the proxy.", {
            string("project", "Project")
        }) { args -> client.syncNetwork(args.string("project")).let { "Network config of ${args.string("project")} updated" } },

        tool("list_files", "List files of an instance folder.", {
            string("instance", "project/name")
            string("path", "Folder relative to the instance folder", required = false)
        }) { args ->
            client.files(args.instance(), args.optString("path").orEmpty()).joinToString("\n") { if (it.directory) "${it.path}/" else "${it.path} (${it.size} B)" }
        },

        tool("read_file", "Read a text file of an instance, e.g. server.properties or plugins/MyPlugin/config.yml.", {
            string("instance", "project/name")
            string("path", "File relative to the instance folder")
        }) { args -> client.readFile(args.instance(), args.string("path")).text },

        tool("write_file", "Replace a text file of an instance. Restart the instance for config changes to apply.", {
            string("instance", "project/name")
            string("path", "File relative to the instance folder")
            string("content", "Full new file content")
        }) { args ->
            client.writeFile(args.instance(), args.string("path"), args.string("content"))
            "Wrote ${args.string("path")}"
        },
    )

    private suspend fun lifecycle(args: Args, action: String): String {
        val id = args.instance()
        val since = client.instance(id).nextLogSeq
        val info = client.action(id, action)
        if (!args.bool("wait_ready", true)) return info.describe()
        val result = client.waitFor(id, info.def.effectiveReadyPattern, since, (args.optLong("timeout_seconds") ?: 180) * 1000)
        val current = client.instance(id)
        if (!result.matched) throw IllegalStateException("$id did not become ready: ${current.describe()}\n" + tail(id, since))
        return current.describe()
    }

    private suspend fun sendAndExpect(args: Args, line: String): String {
        val target = args.instance()
        val watched = args.optString("expect_in")?.let { InstanceId.parse(it, target.project) } ?: target
        val since = client.instance(watched).nextLogSeq
        val pattern = args.optString("expect")
        val result = client.sendAndExpect(target, line, watched, pattern, (args.optLong("timeout_seconds") ?: 30) * 1000)
            ?: return "Sent to $target. Log cursor of $watched before sending: $since"
        if (!result.matched) throw IllegalStateException("No line matching '$pattern' in $watched. New output:\n" + tail(watched, since))
        return "${result.line!!.text}\n[cursor ${result.next}]"
    }

    private suspend fun tail(id: InstanceId, since: Long): String =
        client.log(id, since, limit = 40).lines.joinToString("\n", transform = LogLine::text)

    private fun Args.instance(): InstanceId {
        val value = string("instance")
        require('/' in value) { "Instance must be 'project/name', got '$value'" }
        return InstanceId.parse(value, "")
    }

    private fun InstanceInfo.describe() = buildString {
        append("${def.id}: ${def.type.name.lowercase()} ${def.version}, ${state.name.lowercase()}")
        def.port?.let { append(", port $it") }
        def.bots?.let { append(", bots ${it.accountNames.joinToString()} -> ${it.target}") }
        append(", log cursor $nextLogSeq")
    }

    private fun PluginStatus.describe() = buildString {
        append("${link.name}: ${link.source} -> ${link.targets.ifEmpty { listOf("all servers") }.joinToString()}")
        append("; jar ${jar ?: "none"}")
        deployedJar?.let { append("; deployed $it") }
        error?.let { append("; error: $it") }
    }
}
