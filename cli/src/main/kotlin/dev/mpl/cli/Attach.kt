package dev.mpl.cli

import dev.mpl.api.MplClient
import dev.mpl.api.MplJson
import dev.mpl.api.TerminalSize
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import java.io.File
import sun.misc.Signal

private const val DETACH_KEY = 0x1d

fun attach(client: MplClient, name: String) {
    val saved = stty("-g").trim()
    stty("raw", "-echo")
    println("[attached to $name, Ctrl+] to detach]\r")
    try {
        runBlocking {
            client.attach(name) {
                val resizes = Channel<Unit>(Channel.CONFLATED)
                runCatching { Signal.handle(Signal("WINCH")) { resizes.trySend(Unit) } }
                resizes.trySend(Unit)
                val sizer = launch { for (ignored in resizes) terminalSize()?.let { send(Frame.Text(MplJson.encodeToString(TerminalSize.serializer(), it))) } }

                val output = launch {
                    for (frame in incoming) if (frame is Frame.Binary) {
                        System.out.write(frame.readBytes())
                        System.out.flush()
                    }
                }
                val input = launch(Dispatchers.IO) {
                    val buf = ByteArray(1024)
                    while (true) {
                        val n = System.`in`.read(buf)
                        if (n < 0) break
                        val detachAt = (0 until n).firstOrNull { buf[it].toInt() == DETACH_KEY }
                        if (detachAt != null) {
                            if (detachAt > 0) send(Frame.Binary(true, buf.copyOf(detachAt)))
                            break
                        }
                        send(Frame.Binary(true, buf.copyOf(n)))
                    }
                }
                select {
                    output.onJoin {}
                    input.onJoin {}
                }
                sizer.cancel()
                output.cancel()
            }
        }
    } finally {
        stty(saved)
        println("\r\n[detached from $name]")
    }
}

private fun terminalSize(): TerminalSize? {
    val parts = stty("size").trim().split(" ")
    if (parts.size != 2) return null
    return TerminalSize(cols = parts[1].toInt(), rows = parts[0].toInt())
}

private fun stty(vararg args: String): String {
    val process = ProcessBuilder(listOf("stty") + args)
        .redirectInput(File("/dev/tty"))
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    return process.inputStream.bufferedReader().readText().also { process.waitFor() }
}
