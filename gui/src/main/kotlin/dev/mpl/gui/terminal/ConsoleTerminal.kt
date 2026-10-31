package dev.mpl.gui.terminal

import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import dev.mpl.api.InstanceId
import dev.mpl.api.MplClient
import java.awt.Font

class ConsoleTerminal(client: MplClient, id: InstanceId) {
    val widget = JediTermWidget(120, 40, Settings).apply {
        setTtyConnector(DaemonTtyConnector(client, id))
        start()
    }

    fun close() = widget.close()

    private object Settings : DefaultSettingsProvider() {
        private val foreground = TerminalColor(0xD8, 0xDE, 0xE9)
        private val background = TerminalColor(0x16, 0x18, 0x1D)

        override fun getDefaultForeground() = foreground
        override fun getDefaultBackground() = background
        override fun getDefaultStyle() = TextStyle(foreground, background)
        override fun getTerminalFont(): Font = Font(Font.MONOSPACED, Font.PLAIN, terminalFontSize.toInt())
        override fun getTerminalFontSize() = 14f
        override fun getBufferMaxLinesCount() = 10_000
        override fun copyOnSelect() = true
    }
}
