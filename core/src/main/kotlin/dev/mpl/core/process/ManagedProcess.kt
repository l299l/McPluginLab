package dev.mpl.core.process

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import dev.mpl.api.InstanceState
import dev.mpl.api.LogLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.nio.file.Path
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories

data class LaunchSpec(
    val command: List<String>,
    val directory: Path,
    val env: Map<String, String>,
    val stopCommand: String,
    val readyPattern: Regex,
)

class ManagedProcess(
    val name: String,
    private val logFile: Path,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(InstanceState.STOPPED)
    val state: StateFlow<InstanceState> = _state.asStateFlow()

    val log = LogBuffer()
    val output = OutputBuffer()

    @Volatile private var process: PtyProcess? = null
    @Volatile private var spec: LaunchSpec? = null
    @Volatile var exitCode: Int? = null
        private set
    @Volatile private var size = WinSize(160, 48)

    val pid: Long? get() = process?.takeIf { it.isAlive }?.pid()
    val isAlive: Boolean get() = process?.isAlive == true

    @Synchronized
    fun start(spec: LaunchSpec) {
        check(!isAlive) { "$name is already running" }
        this.spec = spec
        exitCode = null
        output.clear()
        logFile.parent.createDirectories()
        val writer = logFile.bufferedWriter()

        val pty = PtyProcessBuilder(spec.command.toTypedArray())
            .setDirectory(spec.directory.toString())
            .setEnvironment(System.getenv() + mapOf("TERM" to "xterm-256color") + spec.env)
            .setInitialColumns(size.columns)
            .setInitialRows(size.rows)
            .setRedirectErrorStream(true)
            .start()
        process = pty
        _state.value = InstanceState.STARTING
        scope.launch(Dispatchers.IO) { pump(pty, spec, writer) }
    }

    private suspend fun pump(pty: PtyProcess, spec: LaunchSpec, writer: BufferedWriter) {
        val splitter = LineSplitter { text ->
            log.append(text)
            writer.appendLine(text)
            writer.flush()
            if (_state.value == InstanceState.STARTING && spec.readyPattern.containsMatchIn(text)) {
                _state.value = InstanceState.RUNNING
            }
        }
        val buf = ByteArray(8192)
        writer.use {
            runCatching {
                val input = pty.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.append(TerminalQueries.strip(buf.copyOf(n)))
                    splitter.feed(buf, n)
                }
            }
            splitter.flush()
        }
        val code = pty.onExit().await().exitValue()
        exitCode = code
        val wasStopping = _state.value == InstanceState.STOPPING
        _state.value = if (wasStopping || code == 0) InstanceState.STOPPED else InstanceState.CRASHED
    }

    suspend fun waitForLog(regex: Regex, since: Long, timeoutMs: Long): LogLine? = coroutineScope {
        val match = async { log.waitFor(regex, since, timeoutMs) }
        val exit = async {
            if (!isAlive) awaitCancellation()
            state.first { it == InstanceState.STOPPED || it == InstanceState.CRASHED }
        }
        select {
            match.onAwait { it }
            exit.onAwait { log.read(since, Int.MAX_VALUE).firstOrNull { regex.containsMatchIn(it.text) } }
        }.also { coroutineContext.cancelChildren() }
    }

    fun write(bytes: ByteArray) {
        val out = process?.takeIf { it.isAlive }?.outputStream ?: return
        synchronized(out) {
            out.write(bytes)
            out.flush()
        }
    }

    fun sendCommand(command: String) {
        check(isAlive) { "$name is not running" }
        write("$command\r".toByteArray())
    }

    fun resize(cols: Int, rows: Int) {
        size = WinSize(cols.coerceAtLeast(20), rows.coerceAtLeast(5))
        process?.takeIf { it.isAlive }?.winSize = size
    }

    suspend fun stop(timeoutMs: Long = 60_000) {
        val pty = beginStop() ?: return
        awaitStop(pty, timeoutMs)
    }

    fun requestStop(timeoutMs: Long = 60_000) {
        val pty = beginStop() ?: return
        scope.launch { awaitStop(pty, timeoutMs) }
    }

    private fun beginStop(): PtyProcess? {
        val pty = process?.takeIf { it.isAlive } ?: return null
        if (_state.value != InstanceState.STOPPING) {
            _state.value = InstanceState.STOPPING
            spec?.stopCommand?.takeIf { it.isNotBlank() }?.let { write("$it\r".toByteArray()) } ?: pty.destroy()
        }
        return pty
    }

    private suspend fun awaitStop(pty: PtyProcess, timeoutMs: Long) {
        if (withTimeoutOrNull(timeoutMs) { pty.onExit().await() } != null) return
        pty.destroy()
        if (withTimeoutOrNull(5_000) { pty.onExit().await() } != null) return
        pty.destroyForcibly()
        pty.onExit().await()
    }

    suspend fun kill() {
        val pty = process?.takeIf { it.isAlive } ?: return
        _state.value = InstanceState.STOPPING
        pty.destroyForcibly()
        pty.onExit().await()
    }
}
