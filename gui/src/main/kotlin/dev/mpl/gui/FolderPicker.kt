package dev.mpl.gui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser

object FolderPicker {
    suspend fun pick(initial: String?): String? {
        val start = initial ?: System.getProperty("user.home")
        return runCatching { withContext(Dispatchers.IO) { kdialog(start) } }.getOrElse { swing(start) }
    }

    private fun kdialog(start: String): String? {
        val process = ProcessBuilder("kdialog", "--getexistingdirectory", start, "--title", "Select servers folder").start()
        val result = process.inputStream.bufferedReader().readText().trim()
        return result.takeIf { process.waitFor() == 0 && it.isNotEmpty() }
    }

    private fun swing(start: String): String? {
        val chooser = JFileChooser(File(start)).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        return chooser.takeIf { it.showOpenDialog(null) == JFileChooser.APPROVE_OPTION }?.selectedFile?.absolutePath
    }
}
