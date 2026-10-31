package dev.mpl.gui.editor

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.Theme
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ConfigEditor(onChange: (String) -> Unit, onSave: () -> Unit) {
    private val area = RSyntaxTextArea().apply {
        Theme.load(javaClass.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/dark.xml")).apply(this)
        font = Font(Font.MONOSPACED, Font.PLAIN, 14)
        tabSize = 2
        isCodeFoldingEnabled = true
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onChange(text)
            override fun removeUpdate(e: DocumentEvent) = onChange(text)
            override fun changedUpdate(e: DocumentEvent) = Unit
        })
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "mpl-save")
        actionMap.put("mpl-save", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onSave()
        })
    }

    val component = RTextScrollPane(area).apply { border = null }

    fun show(path: String, text: String) {
        area.syntaxEditingStyle = styleFor(path)
        area.text = text
        area.caretPosition = 0
        area.discardAllEdits()
    }

    private fun styleFor(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "yml", "yaml" -> SyntaxConstants.SYNTAX_STYLE_YAML
        "properties" -> SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE
        "json" -> SyntaxConstants.SYNTAX_STYLE_JSON
        "toml", "ini", "conf" -> SyntaxConstants.SYNTAX_STYLE_INI
        "sh" -> SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL
        else -> SyntaxConstants.SYNTAX_STYLE_NONE
    }
}
