# AI agents

`mpl mcp` is an [MCP](https://modelcontextprotocol.io) server on stdio. It lets an agent control the same daemon
you use, so you can watch the consoles in the GUI while the agent tests.

## Setup

Claude Code:

```bash
claude mcp add --scope user mpl -- /path/to/McPluginLab/cli/build/install/mpl/bin/mpl mcp
```

Other clients: run `mpl mcp` as a stdio server. It starts the daemon if needed and reconnects by itself when the
daemon restarts.

## Tools

| Tool | Purpose |
|---|---|
| `list_projects`, `list_instances` | What exists, states, ports and log cursors |
| `create_instance` | Servers, proxies, bots and clients |
| `start_project`, `stop_project` | A whole project or some of its instances, in the right order |
| `start`, `restart` | One instance; waits until ready, optionally until a log line (`expect`, `expect_in`) |
| `stop`, `kill` | Stop gracefully or immediately |
| `send_command` | Console command, optionally waiting for a matching log line |
| `bot_command` | SoulFire command for some or all bots, optionally waiting for a log line |
| `read_log` | Recent lines, or only the lines after a cursor |
| `wait_for_log` | Wait for a regex in a log |
| `list_plugins`, `deploy_plugins` | Plugin links and deploying the latest build |
| `sync_network`, `set_proxied` | Network configuration and standalone servers |
| `list_files`, `read_file`, `write_file` | Server folders and configs |

## A typical test loop

1. Build the plugin and call `deploy_plugins`.
2. `start_project` brings up the proxy and servers, then the bots.
3. `bot_command` with `command: "say /bw join"`, `bots: "Bot1"`, `expect: "Bot1 joined the arena"` and
   `expect_in: "demo/game1"`.
4. `read_log` with the returned cursor shows everything that happened after that, including stack traces.
5. Fix, rebuild, and repeat; the auto-deploy restarts the targets.

Every waiting tool records the log position before it acts, so an agent never misses a line that appears
immediately, and it never matches stale lines from before.

## Claude Code skill

`skills/mpl-plugin-test` teaches Claude Code the loop above (build, deploy, start, bots, log assertions) and the
safety rules for a running setup. Install it once:

```bash
ln -s /path/to/McPluginLab/skills/mpl-plugin-test ~/.claude/skills/mpl-plugin-test
```

Then ask "test my plugin" or run `/mpl-plugin-test`.

## Prompt ideas

- "Start the demo project and check that all bots reach the lobby."
- "Have Bot1 and Bot2 join an arena on game1 and report any exceptions in the game1 log."
- "Switch solo out of the proxy, restart it and check the plugin starts in single-server mode."
