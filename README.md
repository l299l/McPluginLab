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
mpl create lobby --type paper --version 26.3 --accept-eula --offline -p 25566
mpl create proxy --type velocity -p 25577
mpl start lobby proxy --wait
mpl send lobby say hi
mpl wait lobby 'joined the game' --timeout 60
mpl logs lobby -f
mpl attach lobby          # Ctrl+] detaches
mpl open lobby            # console in a new terminal window (MPL_TERMINAL, default konsole)
mpl stop lobby proxy
mpl ls
mpl gui                   # window with all consoles
```

Existing server directories can be imported with `--dir <path> --jar <file>`.

The workspace lives in `~/McPluginLab` (override with `MPL_HOME`): instance definitions in `instances/`,
server files in `servers/`, jar cache in `cache/`, console logs in `logs/`.
