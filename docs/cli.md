# CLI reference

Instances are addressed as `project/name`. Commands that take instance names also accept bare names together
with `-P project` or the `MPL_PROJECT` variable. `mpl <command> --help` shows every option.

## Daemon and apps

| Command | |
|---|---|
| `mpl gui` | Open the desktop app |
| `mpl mcp` | Run the MCP server on stdio |
| `mpl daemon` | Run the daemon in the foreground (normally started automatically) |
| `mpl shutdown` | Stop all instances and the daemon |

## Projects

| Command | |
|---|---|
| `mpl project ls` | List projects |
| `mpl project create <name> [--dir <folder>]` | Create a project |
| `mpl project set <name> [--online-mode true\|false] [--dir <folder>]` | Change online mode or move the project's folders |
| `mpl project rm <name> [--delete-files]` | Remove a project and its instances |
| `mpl up <project> [instances...] [--no-wait]` | Start proxies and servers, then bots and clients once the servers are ready |
| `mpl down <project> [instances...] [--no-wait]` | Stop bots and clients, then proxies, then servers |

## Instances

```
mpl create <project/name> [options]
```

| Option | |
|---|---|
| `-t, --type` | `paper` (default), `folia`, `purpur`, `spigot`, `velocity`, `waterfall`, `bungeecord`, `soulfire`, `client` |
| `-v, --version`, `--build` | Default: latest stable |
| `-p, --port`, `-m, --memory` | Default: next free port; 2G for servers, 512M for proxies |
| `--dir`, `--jar` | Use an existing folder and jar instead of downloading |
| `--java` | Pin a Java executable instead of automatic selection |
| `--online` | Keep `online-mode=true` |
| `--standalone` | Keep the server out of the project's proxy network |
| `--target` | Bots and clients: instance to connect to |
| `--count`, `--prefix`, `--accounts`, `--protocol` | Bots: how many, name prefix, explicit names, protocol version |
| `--username` | Client: offline player name |

| Command | |
|---|---|
| `mpl ls` | All instances with type, version, port, state and pid |
| `mpl set <instance> [--proxied true\|false] [-p port] [-m memory]` | Change instance settings |
| `mpl rm <instance> [--delete-files]` | Remove an instance |
| `mpl start\|restart <instances...> [-w] [-e regex] [--expect-in instance] [--timeout s]` | Start; `-w` waits until ready, `-e` also waits for a log line |
| `mpl stop\|kill <instances...>` | Stop gracefully or kill |
| `mpl versions <software>` | Available versions of a server, proxy, `soulfire` or `minecraft` |
| `mpl java` | Java installations found or downloaded |

## Consoles and logs

| Command | |
|---|---|
| `mpl send <instance> <command> [-e regex] [--expect-in instance] [--timeout s]` | Send a console command and optionally wait for a reply |
| `mpl bot <bots-instance> <command> [-b Bot1,Bot2] [-e regex] [--expect-in instance]` | Send a SoulFire command to bots |
| `mpl logs <instance> [-n lines] [-f]` | Print or follow console output |
| `mpl wait <instance> <regex> [--since cursor] [--timeout s]` | Wait for a log line |
| `mpl attach <instance>` | Attach this terminal to the console; `Ctrl+]` detaches |
| `mpl open <instance> [--terminal konsole]` | Open the console in a new terminal window |

## Plugins, network and files

| Command | |
|---|---|
| `mpl plugin ls -P <project>` | Plugin links with current and deployed jar |
| `mpl plugin add -P <project> <name> <source> [-t target]... [--manual] [--no-restart]` | Link a jar, glob or folder |
| `mpl plugin rm -P <project> <name>` | Remove a link |
| `mpl deploy -P <project> [plugin]` | Deploy now |
| `mpl network sync -P <project>` | Rewrite proxy and backend config and reload the proxy |
| `mpl file ls <instance> [path]` | List files |
| `mpl file cat <instance> <path>` | Print a file |
| `mpl file write <instance> <path> < file` | Replace a file with stdin |

## Environment

| Variable | |
|---|---|
| `MPL_PROJECT` | Default project for bare instance names |
| `MPL_HOME`, `MPL_CACHE` | State and cache folders |
| `MPL_TERMINAL` | Terminal used by **Pop out** and `mpl open` (default `konsole`) |
