package dev.mpl.daemon

import dev.mpl.api.DaemonInfo
import dev.mpl.api.MplHome
import dev.mpl.api.MplJson
import dev.mpl.core.InstanceManager
import dev.mpl.core.Workspace
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

class Daemon(home: Path = MplHome.path) {
    val workspace = Workspace(home)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val instances = InstanceManager(workspace, scope)
    val token: String = ByteArray(24).also(SecureRandom()::nextBytes).toHexString()

    private val stopLatch = CountDownLatch(1)
    private lateinit var server: EmbeddedServer<*, *>

    fun run(port: Int = DEFAULT_PORT) {
        val lock = FileChannel.open(workspace.root.resolve("daemon.lock"), CREATE, WRITE).tryLock()
            ?: error("Another McPluginLab daemon is already running")

        server = runCatching { startServer(port) }.getOrElse { startServer(0) }
        val boundPort = runBlocking { server.engine.resolvedConnectors().first().port }
        val info = DaemonInfo(boundPort, token, ProcessHandle.current().pid(), VERSION)
        workspace.root.resolve("daemon.json").writeText(MplJson.encodeToString(DaemonInfo.serializer(), info))

        Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
        println("McPluginLab daemon listening on 127.0.0.1:$boundPort (workspace ${workspace.root})")
        stopLatch.await()
        lock.release()
    }

    private fun startServer(port: Int) =
        embeddedServer(CIO, host = "127.0.0.1", port = port) { module(this@Daemon) }.start(wait = false)

    @Synchronized
    fun shutdown() {
        if (stopLatch.count == 0L) return
        runBlocking { instances.stopAll() }
        workspace.root.resolve("daemon.json").deleteIfExists()
        server.stop(500, 2_000)
        scope.cancel()
        stopLatch.countDown()
    }

    companion object {
        const val DEFAULT_PORT = 47817
        const val VERSION = "0.1.0"
    }
}
