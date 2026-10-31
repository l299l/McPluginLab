# McPluginLab (mpl)

One place to run and test Minecraft plugins: servers, proxies, plugin auto-deploy, bots and a client launcher.
A background daemon owns all processes; the CLI, GUI and MCP server are clients of it.

## Build

```
./gradlew installDist
alias mpl=$PWD/cli/build/install/mpl/bin/mpl
```

The daemon runs from a copy of the jars, so rebuilding is safe; `mpl shutdown` restarts it on the new build.

## Usage

```
mpl gui                                   # projects home page, consoles, actions

mpl project create nbw --dir /mnt/data/Codding/NewBedwars/Servers
mpl create nbw/lobby --type paper --version 26.3 -p 25566
mpl create nbw/proxy --type velocity -p 25577
mpl start nbw/lobby nbw/proxy --wait
mpl send nbw/lobby say hi
mpl wait nbw/lobby 'joined the game' --timeout 60
mpl logs nbw/lobby -f
mpl attach nbw/lobby                      # Ctrl+] detaches
mpl open nbw/lobby                        # console in a new terminal window (MPL_TERMINAL, default konsole)
mpl stop nbw/lobby nbw/proxy
mpl ls
mpl project ls

mpl plugin add -P nbw nbw ~/NewBedwars-Free/newbedwars-plugin/target/NewBedwars-*.jar -t lobby -t game1
mpl plugin add -P nbw nbw-proxy ~/NewBedwars-Free/newbedwars-proxy/target/NewBedwars-Proxy-*.jar -t proxy
mpl deploy -P nbw                         # deploy now; linked jars also deploy automatically when they change
mpl network sync -P nbw                   # rewrite proxy/backend forwarding config
mpl project set nbw --online-mode false
mpl file cat nbw/lobby server.properties
mpl file write nbw/lobby config/paper-global.yml < paper-global.yml
```

Instances are addressed as `project/name`; `-P project` or `MPL_PROJECT` sets the project for bare names
(default `default`). Creating a server accepts the Minecraft EULA and sets `online-mode=false` (use `--online` to keep it).
Existing server directories can be imported with `--dir <path> --jar <file>`.

**Networks:** when a project contains a Velocity proxy, every start writes `velocity.toml` (modern forwarding, a per-project
secret, `[servers]` from the project's Paper servers, `try` = lobby first) and the backends' `paper-global.yml` and
`server.properties`. Adding or removing a server while the proxy runs triggers `velocity reload`.

**Plugin links:** a link points at a jar, a glob like `target/MyPlugin-*.jar` or a folder. The newest matching jar
(ignoring `-sources`, `-javadoc`, `original-`) is copied into the target servers' `plugins/`, replacing older versions,
and running targets are restarted. Auto-deploy polls every 2 s and deploys once the jar is stable.

## Bots (SoulFire)

```
mpl create nbw/bots -t soulfire --target proxy --count 4 --prefix Bot     # or --jar ~/SoulFireCLI-2.10.6.jar
mpl start nbw/bots --wait
mpl bot nbw/bots -b Bot1 say /bw join --expect 'Bot1 issued server command: /bw join' --expect-in nbw/lobby
mpl send nbw/lobby op Bot1 --expect 'Made Bot1'
```

Each bot instance runs SoulFire CLI with its own home folder (sessions, database) while the large runtime
folders are shared with `~/.soulfire`. Accounts are offline names from prefix+count or `--accounts a,b,c`.
The protocol version defaults to the target server's version (or the project's first server behind a proxy).
`--expect` records the log position before sending, so fast responses are never missed, and skips the echoed command.

## AI agents (MCP)

```
claude mcp add mpl -- /mnt/data/Codding/McPluginLab/cli/build/install/mpl/bin/mpl mcp
```

Tools: `list_projects`, `list_instances`, `create_instance`, `start`/`restart` (wait until ready), `stop`, `kill`,
`send_command` and `bot_command` (with `expect`), `read_log` (cursor based), `wait_for_log`, `list_plugins`,
`deploy_plugins`, `sync_network`, `list_files`, `read_file`, `write_file`.

## Locations

| Path | Contents |
|---|---|
| `~/.local/share/mcpluginlab` (`MPL_HOME`) | projects, instance definitions, console logs, daemon info |
| `~/.cache/mcpluginlab` (`MPL_CACHE`) | downloaded server jars |
| project folder (chosen per project) | server directories, default `~/.local/share/mcpluginlab/servers/<project>` |
