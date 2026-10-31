# McPluginLab (mpl)

One place to run and test Minecraft plugins: servers, proxies, plugin auto-deploy, bots and a client launcher.
A background daemon owns all processes; the CLI, GUI and MCP server are clients of it.

## Build

```
./gradlew installDist
alias mpl=$PWD/cli/build/install/mpl/bin/mpl
```

Run `mpl shutdown` before rebuilding, so the daemon does not keep using replaced jars.

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
```

Instances are addressed as `project/name`; `-P project` or `MPL_PROJECT` sets the project for bare names
(default `default`). Creating a server accepts the Minecraft EULA and sets `online-mode=false` (use `--online` to keep it).
Existing server directories can be imported with `--dir <path> --jar <file>`.

## Locations

| Path | Contents |
|---|---|
| `~/.local/share/mcpluginlab` (`MPL_HOME`) | projects, instance definitions, console logs, daemon info |
| `~/.cache/mcpluginlab` (`MPL_CACHE`) | downloaded server jars |
| project folder (chosen per project) | server directories, default `~/.local/share/mcpluginlab/servers/<project>` |
