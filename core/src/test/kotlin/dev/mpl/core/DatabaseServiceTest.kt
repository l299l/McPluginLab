package dev.mpl.core

import dev.mpl.api.DatabaseSettings
import dev.mpl.api.InstanceDef
import dev.mpl.api.InstanceType
import dev.mpl.core.database.DatabaseBinaries
import dev.mpl.core.database.DatabaseService
import dev.mpl.core.download.Downloader
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Starts a real MariaDB from the PATH; does nothing when none is installed. */
class DatabaseServiceTest {
    private val cache = Files.createTempDirectory("mpl-dbcache")
    private val binaries = DatabaseBinaries(cache, Downloader())
    private val service = DatabaseService(binaries)

    @Test
    fun bootstrapsQueriesAndResets() {
        if (binaries.systemTools() == null) return
        val dir = Files.createTempDirectory("mpl-dbi").createDirectories()
        val port = ServerSocket(0).use { it.localPort }
        val def = InstanceDef("p", "db", InstanceType.MARIADB, version = DatabaseBinaries.SYSTEM, dir = dir.toString(), port = port,
            database = DatabaseSettings("lab", password = "pw123"))
        val server = ProcessBuilder(service.launchCommand(def) {}).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            repeat(60) { attempt ->
                if (runCatching { service.bootstrap(def) }.isSuccess) return@repeat
                Thread.sleep(500)
                check(attempt < 59 && server.isAlive) { "database did not start" }
            }
            service.bootstrap(def)
            service.execute(def, "CREATE TABLE t (id INT); INSERT INTO t VALUES (7)")
            assertContains(service.execute(def, "SELECT id FROM t"), "7")
            service.reset(def)
            assertFailsWith<IllegalStateException> { service.execute(def, "SELECT id FROM t") }
            assertEquals("jdbc:mariadb://127.0.0.1:$port/lab", service.info(def).jdbc)
        } finally {
            server.destroy()
            server.waitFor(30, TimeUnit.SECONDS)
        }
    }
}
