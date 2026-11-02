# Changelog

All notable changes to McPluginLab are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.1] - 2026-11-02

Databases: a project can now have its own MariaDB, started with the network and wired into plugin configs.

### Added

- **MariaDB instance type.** `mpl create demo/db --type mariadb` (or *Add instance → Database → mariadb* in the GUI).
  The first start creates the data directory, a database named after the project and a user `mpl` with a generated
  password.
  - Uses the `mariadbd` on your `PATH` when there is one. Otherwise MariaDB 11.8.3, 11.4.5 or 10.11.11 is downloaded
    once, checksum verified, and cached.
  - Listens on `127.0.0.1` only. `--bind-all` opens it to other machines; `root` is never reachable over the network.
  - `mpl up` starts the database before servers and proxies, and `mpl down` stops it last.
- **Plugin database binding.** `mpl plugin bind` writes the database connection into a plugin's config on every start
  and network sync, for the servers (and proxies) the plugin is linked to:
  ```bash
  mpl plugin bind -P demo Shop -f plugins/Shop/config.yml \
    -s storage.host={host} -s storage.port={port} -s storage.database={database} \
    -s storage.user={user} -s storage.password={password}
  ```
  Templates: `{host}` `{port}` `{database}` `{user}` `{password}` `{jdbc}`. Supports `.yml`, `.toml` and
  `.properties` files. The config file must already exist, so start the server once first.
- **`mpl db` commands.** `mpl db info`, `mpl db sql <instance> "<sql>" [--root]` and `mpl db reset`.
- **GUI: Connection tab** on database instances.
  - Host, database, user, password and JDBC URL, with copy buttons.
  - A table browser: click a table to run `SELECT * … LIMIT 200`.
  - A SQL box with results shown as a table (NULLs dimmed, long values truncated, row count).
  - A *Reset database* button.
  - The link-plugin dialog has an optional database binding.
- **MCP tools** for AI agents: `db_info`, `db_query`, `db_reset` and `bind_plugin_database`. `create_instance` accepts
  `type=mariadb`. The `mpl-plugin-test` skill documents the flow.
- **Docs:** new [Databases](docs/database.md) page; CLI, architecture and AI agent docs updated.

### Notes

- Plugin config files that do not exist yet are left alone, because creating one would stop the plugin from writing
  its defaults. Start the server once, then bind.
- Existing projects keep working: all new settings have defaults, so old project and instance files load unchanged.
- The daemon build changes with this release. If an older daemon is running, `mpl` restarts it when idle, and warns
  instead when instances are running; run `mpl shutdown` to switch over.

SHA-256 of `mpl-1.0.1.zip`: `30acdf3dcf09f2fbc1c7ebdcac32bc5174beacd0eb87380741752f583b5c737c`

## [1.0.0] - 2026-11-01

First release.

### Added

- Projects with servers (Paper, Purpur, Folia, Spigot) and proxies (Velocity, Waterfall, BungeeCord), downloaded
  and checksum verified. EULA, offline mode and ports are set up automatically.
- Proxy networks that configure themselves on every start; any server can be switched out of the network to run
  standalone.
- Live consoles in real terminals, embedded in the GUI or popped out, that keep running when the window closes.
- Plugin links with auto-deploy: new builds are copied to the right servers, old versions are removed and the
  servers restart.
- SoulFire bots and a built-in offline Minecraft client that join the network automatically.
- Automatic Java selection per instance, downloading missing JDKs from Adoptium.
- Desktop GUI, `mpl` command line and an MCP server for AI agents, all talking to one background daemon.

[Unreleased]: https://github.com/l299l/mcpluginlab/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/l299l/mcpluginlab/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/l299l/mcpluginlab/releases/tag/v1.0.0
