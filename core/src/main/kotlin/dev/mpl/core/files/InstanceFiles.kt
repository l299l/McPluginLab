package dev.mpl.core.files

import dev.mpl.api.FileContent
import dev.mpl.api.FileEntry
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

class InstanceFiles(dir: String) {
    private val root: Path = Path(dir).toAbsolutePath().normalize()

    fun list(path: String): List<FileEntry> {
        val dir = resolve(path)
        require(dir.isDirectory()) { "$path is not a directory" }
        return dir.listDirectoryEntries()
            .filter { it.name !in HIDDEN }
            .sortedWith(compareBy({ !it.isDirectory() }, { it.name.lowercase() }))
            .map { FileEntry(it.relativeTo(root).toString(), it.isDirectory(), if (it.isRegularFile()) it.fileSize() else 0) }
    }

    fun read(path: String): FileContent {
        val file = resolve(path)
        require(file.isRegularFile()) { "$path is not a file" }
        require(file.fileSize() <= MAX_SIZE) { "$path is too large to edit" }
        return FileContent(path, file.readText())
    }

    fun write(path: String, text: String) {
        val file = resolve(path)
        require(!file.isDirectory()) { "$path is a directory" }
        file.parent.createDirectories()
        file.writeText(text)
    }

    fun exists(path: String) = resolve(path).exists()

    private fun resolve(path: String): Path {
        val resolved = root.resolve(path.trimStart('/')).normalize()
        require(resolved.startsWith(root)) { "$path is outside the instance folder" }
        return resolved
    }

    private companion object {
        const val MAX_SIZE = 2L * 1024 * 1024
        val HIDDEN = setOf("libraries", "versions", "cache", ".console_history")
    }
}
