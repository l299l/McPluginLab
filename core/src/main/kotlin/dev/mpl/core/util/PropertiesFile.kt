package dev.mpl.core.util

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeLines

object PropertiesFile {
    fun update(path: Path, values: Map<String, String>) {
        val remaining = values.toMutableMap()
        val lines = if (path.exists()) path.readLines() else emptyList()
        val updated = lines.map { line ->
            val key = line.substringBefore('=').trim()
            if (!line.trimStart().startsWith("#") && line.contains('=') && key in remaining) "$key=${remaining.remove(key)}" else line
        }
        path.writeLines(updated + remaining.map { (k, v) -> "$k=$v" })
    }
}
