---
name: mpl-plugin-test
description: Test a Minecraft plugin with McPluginLab (mpl MCP) - build, deploy, start servers/proxy, join with bots, run commands and assert on server logs. Use when asked to test, try, debug or verify a Minecraft/Paper/Velocity plugin.
allowed-tools: mcp__mpl__*, Bash, Read, Edit, Grep, Glob
---

# Test a Minecraft plugin with McPluginLab

Drive the `mpl` MCP server (`mcp__mpl__*` tools) through one loop: **build -> deploy -> start -> exercise -> assert -> iterate**.
Report results as a table (step | expectation | evidence log line | pass/fail).

## 1. Preflight
- If no `mcp__mpl__*` tools are available, tell the user to run
  `claude mcp add --scope user mpl -- /path/to/McPluginLab/cli/build/install/mpl/bin/mpl mcp` (or `mpl mcp` from a release) and stop.
- `list_projects` / `list_instances` to find the project and its servers, proxy and soulfire (bot) instances. If several projects fit, ask which one.
- Note each instance's `state` and log cursor before you touch anything. Anything already running was started by the user.

## 2. Safety rules
- Do not stop, kill, restart or recreate instances you did not need for this test; never use `stop_project` on a project the user was already running. Prefer `restart` of just the plugin's target servers.
- Never create instances on ports 25565-25568 or 25577 (the user's live setups). For a throwaway setup create a new project and pass `port` in the 30000+ range.
- `kill` only if `stop` hangs. Don't edit server files unrelated to the plugin under test.

## 3. Build
Detect from the plugin repo: `pom.xml` -> `mvn -q package`; `build.gradle(.kts)` -> `./gradlew build`.
On failure fix the compile error first; do not deploy a stale jar.

## 4. Deploy
`list_plugins(project)` shows links (source, targets, current jar, last deploy). Then `deploy_plugins(project[, plugin])`;
running targets restart if the link says so. If the plugin has no link, ask the user for the jar/target rather than guessing
(`mpl plugin add -P <project> <name> <jar>` is the CLI way).

## 5. Start
- Whole project: `start_project(project[, instances])` - proxy and servers first, bots after the servers are ready.
- Single instance: `start` / `restart` with `wait_ready` (default true); add `expect` (regex) and optionally `expect_in`
  to also wait for a line, e.g. `expect: "MyPlugin v.* enabled"`, `expect_in: "demo/lobby"`.
- Confirm the plugin loaded: `read_log` and look for its enable line; `Could not load` / `Error occurred while enabling` means fail.
- Missing instances: `create_instance(instance="project/name", type=paper|folia|purpur|spigot|velocity|waterfall|bungeecord|mariadb|soulfire|client, version?, port?)`;
  bots need `bot_target` (name of proxy/server), `bot_count`, `bot_prefix`.

- Plugin needs a database: `create_instance(instance="project/db", type=mariadb)` (once per project), start it, start the
  server once so the plugin writes its config, then `bind_plugin_database(project, plugin, file="plugins/X/config.yml",
  values="storage.host={host}\nstorage.port={port}\n...")` and restart the server. Templates: `{host} {port} {database} {user} {password} {jdbc}`.
  Assert on stored data with `db_query(instance, sql)`; `db_reset` gives the next test a clean database.

## 6. Exercise
- Console: `send_command(instance, command)` (no leading slash).
- Players: `bot_command(instance=<soulfire>, command="say /mycmd arg", bots="Bot1", expect=..., expect_in="project/server")`.
  Other SoulFire commands: `say hello`, `move`, `lookat`, `click left`, `useitem`, `inventory`, `respawn`, `health`. Bots must have joined first.
- Always pass `expect` (+ `expect_in` for the server whose log shows the result) so the call asserts for you.
  The cursor is recorded *before* acting, so `expect` never misses an immediate line and never matches stale ones.

## 7. Assert
- Positive: `wait_for_log(instance, pattern[, since][, timeout_seconds])` - Java regex. Without `since` only new lines count.
- Negative: `read_log(instance, since=<cursor>)` after the scenario and scan for `Exception|SEVERE|ERROR|Caused by|Could not|Unknown command`.
- Configs and crash data: `read_file` / `list_files` (e.g. `plugins/MyPlugin/config.yml`, `logs/latest.log`). `write_file` replaces a whole file;
  restart the instance for the change to apply.
- Cursors are per instance. Keep the `[cursor N]` returned by each call for the next `read_log`.

## 8. Iterate
On failure: read the stack trace, fix the code, rebuild (auto-deploy restarts linked targets, else call `deploy_plugins`), repeat from step 6.
Stop after about 3 failed rounds and report what you know instead of looping.

## 9. Cleanup
Stop (`stop` / `stop_project`) only what you started for this test, bots and clients first. Leave the user's pre-existing instances running.

## Recipes
- **Join flow:** `start_project` -> `bot_command` `say /join` with `expect` the arena/join message in the game server.
- **Config reload:** `write_file` the config -> `send_command` the plugin reload command (or `restart`) with `expect` -> `read_log`.
- **Standalone mode:** `set_proxied(instance, false)` -> `sync_network(project)` -> `restart` -> verify the plugin starts without the proxy; revert with `set_proxied(..., true)`.
- **Other versions:** `create_instance` a second server with another `version` (port 30xxx), link/deploy the plugin to it, compare enable logs.
- **Proxy plugins:** target the proxy instance (velocity/waterfall/bungeecord) in `deploy_plugins` and read its log.

## Tools
`list_projects` `list_instances` `create_instance` `start` `restart` `stop` `kill` `start_project` `stop_project` `send_command`
`bot_command` `read_log` `wait_for_log` `list_plugins` `deploy_plugins` `sync_network` `set_proxied` `list_files` `read_file` `write_file`.
Details: `docs/ai-agents.md` in the McPluginLab repo.
