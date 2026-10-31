package dev.mpl.core.util

object YamlPatch {
    fun set(text: String, path: List<String>, value: String): String {
        val lines = text.lines().toMutableList()
        var start = 0
        var end = lines.size
        var indent = 0

        path.forEachIndexed { depth, key ->
            val index = (start until end).firstOrNull { lines[it].indentation() == indent && lines[it].trimStart().startsWith("$key:") }
            if (index == null) {
                val inserted = path.drop(depth).mapIndexed { offset, missing ->
                    val pad = " ".repeat(indent + offset * 2)
                    if (depth + offset == path.lastIndex) "$pad$missing: $value" else "$pad$missing:"
                }
                lines.addAll(end, inserted)
                return lines.joinToString("\n")
            }
            if (depth == path.lastIndex) {
                lines[index] = " ".repeat(indent) + "$key: $value"
                return lines.joinToString("\n")
            }
            start = index + 1
            end = (start until end).firstOrNull { lines[it].isContent() && lines[it].indentation() <= indent } ?: end
            indent = (start until end).firstOrNull { lines[it].isContent() }?.let { lines[it].indentation() } ?: (indent + 2)
        }
        return lines.joinToString("\n")
    }

    fun replaceTopLevel(text: String, key: String, block: List<String>): String {
        val lines = text.lines().toMutableList()
        val start = lines.indexOfFirst { it.startsWith("$key:") }
        val replacement = listOf("$key:") + block
        if (start < 0) return (lines.dropLastWhile { it.isBlank() } + replacement).joinToString("\n")
        val end = (start + 1 until lines.size).firstOrNull { lines[it].isContent() && lines[it].indentation() == 0 && !lines[it].startsWith("-") } ?: lines.size
        repeat(end - start) { lines.removeAt(start) }
        lines.addAll(start, replacement)
        return lines.joinToString("\n")
    }

    private fun String.indentation() = length - trimStart(' ').length

    private fun String.isContent() = isNotBlank() && !trimStart().startsWith("#")
}

object TomlPatch {
    fun setTopLevel(text: String, key: String, value: String): String {
        val lines = text.lines().toMutableList()
        val firstSection = lines.indexOfFirst { it.startsWith("[") }.takeIf { it >= 0 } ?: lines.size
        val index = (0 until firstSection).firstOrNull { lines[it].substringBefore('=').trim() == key && !lines[it].trimStart().startsWith("#") }
        if (index != null) lines[index] = "$key = $value" else lines.add(firstSection, "$key = $value")
        return lines.joinToString("\n")
    }

    fun replaceSection(text: String, section: String, body: List<String>): String {
        val lines = text.lines().toMutableList()
        val header = lines.indexOfFirst { it.trim() == "[$section]" }
        val replacement = listOf("[$section]") + body + ""
        if (header < 0) return (lines + "" + replacement).joinToString("\n")
        val next = (header + 1 until lines.size).firstOrNull { lines[it].startsWith("[") } ?: lines.size
        repeat(next - header) { lines.removeAt(header) }
        lines.addAll(header, replacement)
        return lines.joinToString("\n")
    }
}
