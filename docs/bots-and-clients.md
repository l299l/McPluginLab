# Bots and clients

## SoulFire bots

A `soulfire` instance runs [SoulFire](https://github.com/AlexProgrammerDE/SoulFire) in CLI mode with a group of
offline bots that join a server or proxy of the project.

```bash
mpl create demo/bots -t soulfire --target proxy --count 4 --prefix Bot
mpl create demo/named -t soulfire --target lobby --accounts Alice,Bob
mpl create demo/pinned -t soulfire --jar ~/SoulFireCLI-2.10.6.jar --target proxy
```

The instance is ready once the accounts are loaded; the bots then connect. The protocol version follows the
target server, or the project's first server when the bots join through a proxy (`--protocol` overrides it).

Send SoulFire commands to all bots or to some of them:

```bash
mpl bot demo/bots say hello
mpl bot demo/bots -b Bot1 say /spawn
mpl bot demo/bots -b Bot1,Bot2 say /party join --expect 'joined the party' --expect-in demo/lobby
```

`--expect` waits for a matching line in any instance's log, starting from the moment the command is sent, so
fast replies are never missed.

Each bot instance keeps its own SoulFire home (sessions, database) in its folder, while the large SoulFire
runtime files are shared with `~/.soulfire`.

## Built-in Minecraft client

A `client` instance is a real Minecraft game with an offline account.

```bash
mpl create demo/me -t client -v 26.3 --username Steve --target proxy
mpl start demo/me
```

On first start, the client, libraries, natives and assets are downloaded from Mojang (sha1 verified) into a
shared cache, together with the Java version the game needs. Each client instance has its own game folder.

With `--target` the game joins that proxy or server directly on launch. `options.txt` is set up for testing:
no first-run onboarding, no multiplayer warning and no pause when the window loses focus. Stopping the instance
closes the game.

Microsoft accounts are not supported yet; servers in the project run in offline mode by default, so offline
names work.
