package dev.mpl.mcp

import dev.mpl.api.BotSettings
import dev.mpl.api.ClientSettings
import dev.mpl.api.CreateInstanceRequest
import dev.mpl.api.DbBinding
import dev.mpl.api.InstanceId
import dev.mpl.api.InstanceInfo
import dev.mpl.api.InstanceType
import dev.mpl.api.LogLine
import dev.mpl.api.MplClient
import dev.mpl.api.MplException
import dev.mpl.api.PluginStatus
import dev.mpl.api.UpdateInstanceRequest
import java.net.ConnectException

class LabTools(private var client: MplClient, private val reconnect: suspend () -> MplClient = { client }) {
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

        tool("create_instance", "Create a server (paper/folia/purpur/spigot), proxy (velocity/waterfall/bungeecord), MariaDB database (mariadb, one per project: plugins get its connection written into their config via bind_plugin_database), SoulFire bots (soulfire) or a Minecraft client (client). Downloads what is needed.", {
            string("instance", "project/name")
            string("type", "paper, folia, purpur, spigot, velocity, waterfall, bungeecord, mariadb, soulfire or client")
            string("version", "Minecraft/proxy/SoulFire version, default latest stable", required = false)
            integer("port", "Port, default next free")
            string("bot_target", "soulfire/client: instance name in the project to connect to (proxy or server)", required = false)
            string("username", "client: offline player name (default: instance name)", required = false)
            integer("bot_count", "soulfire: number of bots (default 1)")
            string("bot_prefix", "soulfire: bot name prefix (default Bot)", required = false)
            string("jar", "Existing jar path instead of downloading", required = false)
            string("database", "mariadb: database name (default: the project name)", required = false)
        }) { args ->
            val id = args.instance()
            val type = InstanceType.valueOf(args.string("type").uppercase())
            val target = args.optString("bot_target")
            val bots = target?.takeIf { type == InstanceType.SOULFIRE }?.let { BotSettings(it, args.optInt("bot_count") ?: 1, args.optString("bot_prefix") ?: "Bot") }
            val player = if (type == InstanceType.CLIENT) ClientSettings(args.optString("username") ?: id.name, target) else null
            val request = CreateInstanceRequest(
                project = id.project, name = id.name, type = type, version = args.optString("version"),
                port = args.optInt("port"), jar = args.optString("jar"), offlineMode = true, bots = bots, client = player,
                databaseName = args.optString("database"),
            )
            "Created " + client.create(request).describe()
        },

        tool("start", "Start an instance. With wait_ready (default true) returns once it is ready (servers: 'Done', bots: accounts loaded).", {
            string("instance", "project/name")
            boolean("wait_ready", "Wait until ready (default true)")
            string("expect", "Also wait for a log line matching this regex, e.g. 'Bot1 joined the game'", required = false)
            string("expect_in", "project/name whose log to watch for expect (default: the instance)", required = false)
            integer("timeout_seconds", "Default 180")
        }) { args -> lifecycle(args, "start") },

        tool("restart", "Restart an instance, optionally waiting until ready.", {
            string("instance", "project/name")
            boolean("wait_ready", "Wait until ready (default true)")
            string("expect", "Also wait for a log line matching this regex, e.g. 'Bot1 joined the game'", required = false)
            string("expect_in", "project/name whose log to watch for expect (default: the instance)", required = false)
            integer("timeout_seconds", "Default 180")
        }) { args -> lifecycle(args, "restart") },

        tool("start_project", "Start a whole project (or some instances of it) and wait: proxies and servers first, then bots and clients once the servers are ready.", {
            string("project", "Project")
            string("instances", "Comma separated instance names, default all", required = false)
        }) { args -> group(args, "start") },

        tool("stop_project", "Stop a whole project (or some instances of it) and wait: bots and clients first, then proxies, then servers.", {
            string("project", "Project")
            string("instances", "Comma separated instance names, default all", required = false)
        }) { args -> group(args, "stop") },

        tool("set_proxied", "Include a server in the project's proxy network or exclude it so it runs standalone (own port, no forwarding).", {
            string("instance", "project/name of a server")
            boolean("proxied", "true to include, false to exclude", required = true)
        }) { args ->
            val proxied = args.optString("proxied")?.toBooleanStrict() ?: throw IllegalArgumentException("Missing argument 'proxied'")
            client.updateInstance(args.instance(), UpdateInstanceRequest(proxied = proxied)).let { "${it.describe()}, proxied=${it.def.proxied}" }
        },

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

        tool("bind_plugin_database", "Make a linked plugin use the project's MariaDB: on every start/sync the connection is written into the plugin's config file (the file must already exist, so start the server once first). Pass an empty values string to remove the binding.", {
            string("project", "Project")
            string("plugin", "Plugin link name")
            string("file", "Config file relative to the server folder, e.g. plugins/Shop/config.yml (.yml, .toml or .properties)")
            string("values", "One key=template per line, e.g. storage.host={host}. Templates: {host} {port} {database} {user} {password} {jdbc}. YAML keys may be dotted paths.")
        }) { args ->
            val project = args.string("project")
            val link = client.plugins(project).firstOrNull { it.link.name == args.string("plugin") }?.link
                ?: throw IllegalArgumentException("No plugin link '${args.string("plugin")}' in $project")
            val values = args.string("values").lines().filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            client.savePlugin(project, link.copy(db = if (values.isEmpty()) null else DbBinding(args.string("file"), values)))
            if (values.isEmpty()) "Removed database binding of ${link.name}" else "Bound ${link.name} to the database (${values.size} keys); applied on the next start or sync_network"
        },

        tool("db_info", "Connection details of a project's MariaDB instance: host, port, database, user, password and JDBC URL.", {
            string("instance", "project/name of the mariadb instance")
        }) { args -> client.databaseInfo(args.instance()).let { "host ${it.host}, port ${it.port}, database ${it.database}, user ${it.user}, password ${it.password}\njdbc ${it.jdbc}" } },

        tool("db_query", "Run SQL on a running MariaDB instance as the project user (tab separated output with a header row). Use it to assert on what a plugin stored.", {
            string("instance", "project/name of the mariadb instance")
            string("sql", "SQL statement(s)")
            boolean("as_root", "Run as root instead of the project user (default false)")
        }) { args -> client.sql(args.instance(), args.string("sql"), args.bool("as_root", false)).output.trimEnd().ifEmpty { "OK" } },

        tool("db_reset", "Drop and recreate the project database so the next test starts clean.", {
            string("instance", "project/name of the mariadb instance")
        }) { args -> client.resetDatabase(args.instance()).let { "Database of ${args.string("instance")} was reset" } },

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
    ).map { tool -> tool.wrap(::reconnecting) }

    private suspend fun lifecycle(args: Args, action: String): String {
        val id = args.instance()
        val watched = args.optString("expect_in")?.let { InstanceId.parse(it, id.project) } ?: id
        val timeoutMs = (args.optLong("timeout_seconds") ?: 180) * 1000
        val since = client.instance(id).nextLogSeq
        val expectSince = client.instance(watched).nextLogSeq
        val info = client.action(id, action)
        val expect = args.optString("expect")
        if (!args.bool("wait_ready", true) && expect == null) return info.describe()
        val ready = client.waitFor(id, info.def.effectiveReadyPattern, since, timeoutMs)
        val current = client.instance(id)
        if (!ready.matched) throw IllegalStateException("$id did not become ready: ${current.describe()}\n" + tail(id, since))
        expect ?: return current.describe()
        val result = client.waitFor(watched, expect, expectSince, timeoutMs)
        if (!result.matched) throw IllegalStateException("$id is ready but no line matching '$expect' appeared in $watched\n" + tail(watched, expectSince))
        return "${current.describe()}\n${result.line!!.text}\n[cursor ${result.next} in $watched]"
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

    private suspend fun reconnecting(call: suspend () -> String): String = try {
        call()
    } catch (e: Exception) {
        if (e !is ConnectException && (e as? MplException)?.message != "Invalid token") throw e
        client = reconnect()
        call()
    }

    private suspend fun group(args: Args, action: String): String {
        val only = args.optString("instances")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        return client.group(args.string("project"), action, only, wait = true)
            .filter { only.isEmpty() || it.def.name in only }
            .joinToString("\n") { it.describe() }
    }

    private fun Args.instance(): InstanceId {
        val value = string("instance")
        require('/' in value) { "Instance must be 'project/name', got '$value'" }
        return InstanceId.parse(value, "")
    }

    private fun InstanceInfo.describe() = buildString {
        append("${def.id}: ${def.type.name.lowercase()} ${def.version}, ${state.name.lowercase()}")
        def.port?.let { append(", port $it") }
        if (def.type.isServer && !def.proxied) append(", standalone (not behind the proxy)")
        def.bots?.let { append(", bots ${it.accountNames.joinToString()} -> ${it.target}") }
        def.client?.let { append(", player ${it.username}" + (it.target?.let { target -> " -> $target" } ?: "")) }
        append(", log cursor $nextLogSeq")
    }

    private fun PluginStatus.describe() = buildString {
        append("${link.name}: ${link.source} -> ${link.targets.ifEmpty { listOf("all servers") }.joinToString()}")
        append("; jar ${jar ?: "none"}")
        deployedJar?.let { append("; deployed $it") }
        error?.let { append("; error: $it") }
    }
}
