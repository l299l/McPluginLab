package dev.mpl.core.process

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription

class OutputBuffer(private val capacity: Int = 1 shl 20) {
    private val ring = ByteArray(capacity)
    private var start = 0
    private var size = 0
    private val chunks = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Synchronized
    fun append(bytes: ByteArray) {
        for (b in bytes) {
            ring[(start + size) % capacity] = b
            if (size < capacity) size++ else start = (start + 1) % capacity
        }
        chunks.tryEmit(bytes)
    }

    @Synchronized
    fun snapshot(): ByteArray = ByteArray(size) { ring[(start + it) % capacity] }

    @Synchronized
    fun clear() {
        start = 0
        size = 0
    }

    fun stream(withScrollback: Boolean): Flow<ByteArray> =
        if (withScrollback) chunks.onSubscription { emit(snapshot()) } else chunks
}
