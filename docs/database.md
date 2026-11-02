# Databases

A project can have a **MariaDB** instance. It starts and stops with the rest of the project, and McPluginLab writes
its connection details into the config of the plugins you link to it, so you never copy hosts and passwords by hand.

## Add a database

```bash
mpl create demo/db --type mariadb
```

In the GUI, pick **Database → mariadb** in *Add instance*. The first start creates the data directory, a database
named after the project and a user `mpl` with a generated password.

- **Binaries.** If `mariadbd` is on your `PATH` (version `system`) it is used. Otherwise the official MariaDB Linux
  tarball (`-v 11.8.3`, `11.4.5`, `10.11.11`) is downloaded once into `MPL_CACHE/db`, checksum verified.
- **Port.** The next free port from 3307.
- **Order.** `mpl up` starts the database before servers and proxies, and `mpl down` stops it last.
- **Access.** It listens on `127.0.0.1` only. `--bind-all` (or the checkbox in the GUI) listens on all interfaces
  so other machines can connect with the project user. `root` is never reachable over the network.

## Connect plugins

Link the plugin as usual, then say which config keys take which value:

```bash
mpl plugin bind -P demo Shop -f plugins/Shop/config.yml \
  -s storage.host={host} -s storage.port={port} -s storage.database={database} \
  -s storage.user={user} -s storage.password={password}
```

| Template | |
|---|---|
| `{host}` `{port}` | Where the database listens |
| `{database}` `{user}` `{password}` | The project database and its user |
| `{jdbc}` | `jdbc:mariadb://host:port/database` |

The values are written into the file on every start and network sync, for the servers the plugin is linked to.
YAML keys can be dotted paths, `.toml` files get top level keys and `.properties` files plain keys.
Only existing files are patched, because creating one would stop the plugin from writing its defaults. Start the
server once, then bind.

## Query and reset

| Command | |
|---|---|
| `mpl db info <instance>` | Host, port, database, user, password and JDBC URL |
| `mpl db sql <instance> "<sql>" [--root]` | Run SQL as the project user (tab separated output) |
| `mpl db reset <instance>` | Drop and recreate the project database |

The GUI shows the same on the instance's **Connection** tab. AI agents get `db_info`, `db_query`, `db_reset` and
`bind_plugin_database`, so a test can assert on what a plugin stored.

## Files

```
<instance folder>/data/            MariaDB data directory
<instance folder>/mariadb.sock     admin socket (/tmp/mpl-*.sock when the path is too long)
```
