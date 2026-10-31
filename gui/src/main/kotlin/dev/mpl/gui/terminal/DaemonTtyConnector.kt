package dev.mpl.gui.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import dev.mpl.api.MplClient
import dev.mpl.api.MplJson
import dev.mpl.api.TerminalSize
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.LinkedBlockingQueue

class DaemonTtyConnector(private val client: MplClient, private val name: String) : TtyConnector {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val received = LinkedBlockingQueue<ByteArray>()
    private val reader = InputStreamReader(QueueInputStream(received), Charsets.UTF_8)
    private val pending = Channel<Frame>(Channel.UNLIMITED)
    @Volatile private var open = true

    init {
        scope.launch {
            while (isActive) {
                runCatching {
                    client.attach(name) {
                        received.put(CLEAR_SCREEN)
                        val sender = launch { for (frame in pending) send(frame) }
                        for (frame in incoming) if (frame is Frame.Binary) received.put(frame.readBytes())
                        sender.cancel()
                    }
                }
                delay(1_000)
            }
        }
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int = reader.read(buf, offset, length)

    override fun write(bytes: ByteArray) {
        pending.trySend(Frame.Binary(true, bytes))
    }

    override fun write(string: String) = write(string.toByteArray())

    override fun resize(termSize: TermSize) {
        val size = TerminalSize(termSize.columns, termSize.rows)
        pending.trySend(Frame.Text(MplJson.encodeToString(TerminalSize.serializer(), size)))
    }

    override fun isConnected() = open

    override fun ready() = reader.ready()

    override fun waitFor(): Int {
        while (open) Thread.sleep(500)
        return 0
    }

    override fun getName() = name

    override fun close() {
        open = false
        scope.cancel()
        received.put(ByteArray(0))
    }

    private companion object {
        val CLEAR_SCREEN = "\u001B[H\u001B[2J\u001B[3J".toByteArray()
    }

    private class QueueInputStream(private val queue: LinkedBlockingQueue<ByteArray>) : InputStream() {
        private var current = ByteArray(0)
        private var position = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= current.size) {
                current = queue.take()
                position = 0
                if (current.isEmpty()) return -1
            }
            val n = minOf(len, current.size - position)
            System.arraycopy(current, position, b, off, n)
            position += n
            return n
        }

        override fun available(): Int = (current.size - position) + (queue.peek()?.size ?: 0)
    }
}
