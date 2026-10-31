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

## Server software and Java

| Type | Source |
|---|---|
| `paper`, `folia`, `velocity`, `waterfall` | PaperMC Fill API (sha256 verified) |
| `purpur` | Purpur API |
| `spigot` | BuildTools, built once per version into the jar cache (takes a few minutes) |
| `bungeecord` | md_5 Jenkins |
| `soulfire` | GitHub releases, or `--jar` |
| `client` | Mojang version manifest (built-in launcher) |

Java is chosen per instance (`java: auto`): Paper-family servers use the Fill API minimum, other servers their
Minecraft version (≤1.16 → 8, 1.17–1.20.4 → 17, 1.20.5+ → 21, 26.x → 25), proxies 21, SoulFire 25, clients Mojang's
`javaVersion`. An exact installed match from `~/.jdks`, `/usr/lib/jvm`, sdkman or the cache is used; otherwise Temurin is
downloaded from Adoptium. `mpl java` lists what is available.

Networks work with every proxy: Velocity uses modern forwarding when all backends are Paper-based 1.13+, otherwise
legacy forwarding with `bungeecord: true` in `spigot.yml`. BungeeCord/Waterfall get `servers`, `listeners` (priorities,
`force_default_server`) and `ip_forward` written into `config.yml`.

## Minecraft client

```
mpl create nbw/me -t client -v 26.3 --username Leon --target proxy
mpl start nbw/me                          # downloads client, libraries, assets and Java, then joins the proxy
```

Clients are offline accounts with their own game folder per instance; versions, libraries and assets are shared in
the cache. `options.txt` is set up for testing (no onboarding or multiplayer warning, no pause on focus loss).
Stopping a client closes the game.

## Bots (SoulFire)

```
mpl create nbw/bots -t soulfire --target proxy --count 4 --prefix Bot     # or --jar ~/SoulFireCLI-2.10.6.jar
mpl start nbw/bots --expect 'Bot1 joined the game' --expect-in nbw/lobby
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
| `~/.cache/mcpluginlab` (`MPL_CACHE`) | server jars, JDKs, Minecraft client files, daemon runtime copies |
| project folder (chosen per project) | server directories, default `~/.local/share/mcpluginlab/servers/<project>` |
