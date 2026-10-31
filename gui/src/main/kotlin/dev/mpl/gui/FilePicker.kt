package dev.mpl.gui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser

object FilePicker {
    suspend fun folder(initial: String?): String? = pick(initial, directories = true)

    suspend fun jar(initial: String?): String? = pick(initial, directories = false)

    private suspend fun pick(initial: String?, directories: Boolean): String? {
        val start = initial?.takeIf { File(it).exists() } ?: System.getProperty("user.home")
        return runCatching { withContext(Dispatchers.IO) { kdialog(start, directories) } }.getOrElse { swing(start, directories) }
    }

    private fun kdialog(start: String, directories: Boolean): String? {
        val args = if (directories) listOf("--getexistingdirectory", start) else listOf("--getopenfilename", start, "*.jar")
        val process = ProcessBuilder(listOf("kdialog") + args).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        return result.takeIf { process.waitFor() == 0 && it.isNotEmpty() }
    }

    private fun swing(start: String, directories: Boolean): String? {
        val chooser = JFileChooser(File(start)).apply {
            fileSelectionMode = if (directories) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
        }
        return chooser.takeIf { it.showOpenDialog(null) == JFileChooser.APPROVE_OPTION }?.selectedFile?.absolutePath
    }
}
