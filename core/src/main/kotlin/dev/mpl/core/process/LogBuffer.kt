package dev.mpl.core.process

import dev.mpl.api.LogLine
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeoutOrNull

class LogBuffer(private val capacity: Int = 20_000) {
    private val lines = ArrayDeque<LogLine>()
    private val _newLines = MutableSharedFlow<LogLine>(extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val newLines: SharedFlow<LogLine> = _newLines.asSharedFlow()

    @get:Synchronized
    var nextSeq = 0L
        private set

    @Synchronized
    fun append(text: String): LogLine {
        val line = LogLine(nextSeq++, text)
        lines.addLast(line)
        if (lines.size > capacity) lines.removeFirst()
        _newLines.tryEmit(line)
        return line
    }

    @Synchronized
    fun read(since: Long, limit: Int): List<LogLine> {
        val from = if (since < 0) maxOf(0, nextSeq + since) else since
        return lines.asSequence().filter { it.seq >= from }.take(limit).toList()
    }

    suspend fun awaitLines(since: Long, limit: Int, timeoutMs: Long): List<LogLine> {
        read(since, limit).takeIf { it.isNotEmpty() || timeoutMs <= 0 }?.let { return it }
        withTimeoutOrNull(timeoutMs) {
            newLines.onSubscription { read(since, 1).firstOrNull()?.let { emit(it) } }.first { it.seq >= since }
        }
        return read(since, limit)
    }

    suspend fun waitFor(regex: Regex, since: Long, timeoutMs: Long): LogLine? = withTimeoutOrNull(timeoutMs) {
        newLines
            .onSubscription { read(since, Int.MAX_VALUE).firstOrNull { regex.containsMatchIn(it.text) }?.let { emit(it) } }
            .first { it.seq >= since && regex.containsMatchIn(it.text) }
    }
}
