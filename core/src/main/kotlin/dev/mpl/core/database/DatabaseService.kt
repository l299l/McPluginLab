package dev.mpl.core.database

import dev.mpl.api.DatabaseInfo
import dev.mpl.api.DatabaseSettings
import dev.mpl.api.InstanceDef
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/** Runs one MariaDB instance's lifecycle steps: data directory, launch command, bootstrap SQL and queries. */
class DatabaseService(private val binaries: DatabaseBinaries) {
    fun info(def: InstanceDef): DatabaseInfo {
        val settings = settings(def)
        val port = requireNotNull(def.port) { "${def.id} has no port" }
        return DatabaseInfo(HOST, port, settings.database, settings.user, settings.password, settings.jdbc(HOST, port), settings.bindAll)
    }

    /** Prepares the data directory (first start only) and returns the server command. */
    fun launchCommand(def: InstanceDef, log: (String) -> Unit): List<String> {
        val settings = settings(def)
        val tools = binaries.resolve(def.version, log)
        val dir = Path(def.dir).createDirectories()
        val data = dir.resolve("data")
        if (!data.resolve("mysql").exists()) {
            log("initialising the data directory")
            val install = buildList {
                add(tools.installDb.toString())
                add("--no-defaults")
                add("--datadir=$data")
                tools.basedir?.let { add("--basedir=$it") }
                add("--auth-root-authentication-method=normal")
                add("--skip-test-db")
            }
            val result = run(install, dir, emptyMap(), timeoutSeconds = 300)
            check(result.code == 0) { "mariadb-install-db failed: ${result.output.takeLast(2000)}" }
        }
        return buildList {
            add(tools.server.toString())
            add("--no-defaults")
            add("--datadir=$data")
            tools.basedir?.let { add("--basedir=$it") }
            add("--socket=${socket(def)}")
            add("--pid-file=${dir.resolve("mariadb.pid")}")
            add("--port=${requireNotNull(def.port) { "${def.id} has no port" }}")
            add("--bind-address=${if (settings.bindAll) "0.0.0.0" else HOST}")
            add("--skip-name-resolve")
            add("--character-set-server=utf8mb4")
            add("--collation-server=utf8mb4_unicode_ci")
            addAll(def.args)
        }
    }

    /** Creates the project database and user. Safe to repeat. */
    fun bootstrap(def: InstanceDef) = synchronized(def.id.toString().intern()) {
        val s = settings(def)
        val sql = listOf(
            "CREATE DATABASE IF NOT EXISTS `${s.database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
            "CREATE USER IF NOT EXISTS '${s.user}'@'%' IDENTIFIED BY '${s.password}'",
            "ALTER USER '${s.user}'@'%' IDENTIFIED BY '${s.password}'",
            "GRANT ALL PRIVILEGES ON `${s.database}`.* TO '${s.user}'@'%'",
            "FLUSH PRIVILEGES",
        ).joinToString("; ")
        execute(def, sql, asRoot = true)
    }

    /** Drops and recreates the project database. */
    fun reset(def: InstanceDef) {
        val s = settings(def)
        execute(def, "DROP DATABASE IF EXISTS `${s.database}`; CREATE DATABASE `${s.database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci", asRoot = true)
    }

    /** Runs [sql] and returns the tab separated output with a header row. As the project user unless [asRoot]. */
    fun execute(def: InstanceDef, sql: String, asRoot: Boolean = false): String {
        val s = settings(def)
        val tools = binaries.resolve(def.version)
        val command = buildList {
            add(tools.client.toString())
            add("--no-defaults")
            add("--batch")
            if (asRoot) {
                add("--protocol=socket")
                add("--socket=${socket(def)}")
                add("--user=root")
            } else {
                add("--protocol=tcp")
                add("--host=$HOST")
                add("--port=${requireNotNull(def.port)}")
                add("--user=${s.user}")
                add(s.database)
            }
            add("--execute=$sql")
        }
        val env = if (asRoot) emptyMap() else mapOf("MYSQL_PWD" to s.password)
        val result = run(command, Path(def.dir), env, timeoutSeconds = 60)
        val output = result.output.lines().filterNot { it.startsWith("WARNING: option --ssl-verify-server-cert") }.joinToString("\n")
        check(result.code == 0) { output.trim().ifEmpty { "mariadb client exited with ${result.code}" } }
        return output
    }

    private fun settings(def: InstanceDef): DatabaseSettings =
        requireNotNull(def.database) { "${def.id} is not a database instance" }

    /** Unix sockets are limited to about 107 characters, so a long instance path falls back to /tmp. */
    private fun socket(def: InstanceDef): Path {
        val inDir = Path(def.dir).resolve("mariadb.sock")
        return if (inDir.toString().length < 100) inDir else Path("/tmp/mpl-${def.id.toString().hashCode().toUInt().toString(16)}.sock")
    }

    private data class Result(val code: Int, val output: String)

    private fun run(command: List<String>, dir: Path, env: Map<String, String>, timeoutSeconds: Long): Result {
        val builder = ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
        builder.environment().putAll(env)
        val process = builder.start()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(1000)
            return Result(-1, "${command.first()} timed out after ${timeoutSeconds}s\n$output")
        }
        reader.join(2000)
        return Result(process.exitValue(), output.toString())
    }

    companion object {
        const val HOST = "127.0.0.1"
        val IDENTIFIER = Regex("^[A-Za-z0-9_]{1,48}$")
    }
}
