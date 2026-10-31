package dev.mpl.core

import dev.mpl.core.process.TerminalQueries
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalQueryTest {
    @Test
    fun removesJLineProbes() {
        val probes = "\u001B[c\u001B[>c\u001B[6n\u001B[?2026\$p\u001B[?2027\$p\u001B[?u\u001B[>q\u001B]11;?\u0007"
        assertEquals("> ", TerminalQueries.strip("$probes> "))
    }

    @Test
    fun keepsStyling() {
        val text = "\u001B[31mred\u001B[0m\u001B[?2004h\u001B[K"
        assertEquals(text, TerminalQueries.strip(text))
    }
}
