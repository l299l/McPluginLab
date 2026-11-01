package dev.mpl.gui.terminal

import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import dev.mpl.api.InstanceId
import dev.mpl.api.MplClient
import java.awt.Color
import java.awt.Font
import javax.swing.JScrollBar

class ConsoleTerminal(client: MplClient, id: InstanceId) {
    val widget: JediTermWidget = Widget().apply {
        setTtyConnector(DaemonTtyConnector(client, id))
        start()
    }

    fun close() = widget.close()

    private class Widget : JediTermWidget(120, 40, Settings) {
        override fun createScrollBar() = JScrollBar().apply {
            setUI(SlimScrollBarUI(track = BACKGROUND, thumb = Color(0x454B57), thumbActive = Color(0x6B7280)))
            background = BACKGROUND
        }
    }

    private object Settings : DefaultSettingsProvider() {
        private val foreground = TerminalColor.rgb(0xD8, 0xDE, 0xE9)
        private val background = TerminalColor.rgb(BACKGROUND.red, BACKGROUND.green, BACKGROUND.blue)

        override fun getDefaultForeground() = foreground
        override fun getDefaultBackground() = background
        override fun getTerminalFont(): Font = Font(Font.MONOSPACED, Font.PLAIN, terminalFontSize.toInt())
        override fun getTerminalFontSize() = 14f
        override fun getBufferMaxLinesCount() = 10_000
        override fun copyOnSelect() = true
    }

    private companion object {
        val BACKGROUND = Color(0x16, 0x18, 0x1D)
    }
}
