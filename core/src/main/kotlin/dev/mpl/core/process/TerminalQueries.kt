package dev.mpl.core.process

object TerminalQueries {
    private val pattern = Regex(
        listOf(
            """\u001B\[[>=]?0?c""",
            """\u001B\[\??[56]n""",
            """\u001B\[\??\d+\${'$'}p""",
            """\u001B\[\?u""",
            """\u001B\[>0?q""",
            """\u001BP[$+]q[^\u001B]*\u001B\\""",
            """\u001B][0-9;]*\?(?:\u0007|\u001B\\)""",
        ).joinToString("|"),
    )

    fun strip(text: String): String = pattern.replace(text, "")

    fun strip(bytes: ByteArray): ByteArray {
        val text = String(bytes, Charsets.ISO_8859_1)
        return if (pattern.containsMatchIn(text)) strip(text).toByteArray(Charsets.ISO_8859_1) else bytes
    }
}
