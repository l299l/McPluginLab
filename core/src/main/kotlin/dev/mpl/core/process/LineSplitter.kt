package dev.mpl.core.process

import java.io.ByteArrayOutputStream

class LineSplitter(private val onLine: (String) -> Unit) {
    private val current = ByteArrayOutputStream()

    fun feed(bytes: ByteArray, length: Int) {
        for (i in 0 until length) {
            val b = bytes[i]
            if (b == '\n'.code.toByte()) flush() else current.write(b.toInt())
        }
    }

    fun flush() {
        if (current.size() == 0) return
        val raw = current.toString(Charsets.UTF_8)
        current.reset()
        val text = render(raw)
        if (text.isNotBlank()) onLine(text)
    }

    companion object {
        private const val ESC = '\u001B'
        private const val BEL = '\u0007'

        fun render(raw: String): String {
            val line = StringBuilder()
            var cursor = 0
            var i = 0

            fun put(c: Char) {
                if (cursor < line.length) line.setCharAt(cursor, c) else {
                    while (line.length < cursor) line.append(' ')
                    line.append(c)
                }
                cursor++
            }

            while (i < raw.length) {
                val c = raw[i++]
                when {
                    c == '\r' -> cursor = 0
                    c == '\b' -> cursor = maxOf(0, cursor - 1)
                    c == ESC && i < raw.length && raw[i] == '[' -> {
                        val start = ++i
                        while (i < raw.length && raw[i] !in '@'..'~') i++
                        if (i >= raw.length) break
                        val params = raw.substring(start, i)
                        val n = params.toIntOrNull() ?: 1
                        when (raw[i++]) {
                            'C' -> cursor += n
                            'D' -> cursor = maxOf(0, cursor - n)
                            'G' -> cursor = maxOf(0, n - 1)
                            'K' -> when (params) {
                                "", "0" -> line.setLength(minOf(cursor, line.length))
                                "2" -> line.setLength(0)
                            }
                        }
                    }
                    c == ESC && i < raw.length && raw[i] == ']' -> {
                        while (i < raw.length && raw[i] != BEL && !(raw[i] == ESC && raw.getOrNull(i + 1) == '\\')) i++
                        i += if (raw.getOrNull(i) == ESC) 2 else 1
                    }
                    c == ESC -> i++
                    c < ' ' -> Unit
                    else -> put(c)
                }
            }
            return line.toString().removePrefix("> ").trimEnd()
        }
    }
}
