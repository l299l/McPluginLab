package dev.mpl.gui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.mpl.api.MplClient
import java.awt.Toolkit

const val APP_NAME = "McPluginLab"

fun runGui(client: MplClient) {
    setWindowClass(APP_NAME)
    application {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(client, scope) }
        Window(
            onCloseRequest = { state.requestClose(::exitApplication) },
            title = APP_NAME,
            icon = remember { appIcon() },
            state = rememberWindowState(width = 1360.dp, height = 860.dp),
        ) {
            MplTheme {
                App(state)
                if (state.closeRequested) QuitDialog(state, onKeepRunning = ::exitApplication, onQuit = { state.quit(::exitApplication) })
            }
        }
    }
}

fun iconSvg(): ByteArray = object {}.javaClass.getResourceAsStream("/icon.svg")!!.use { it.readBytes() }

private fun appIcon(): Painter = iconSvg().inputStream().use { loadSvgPainter(it, Density(1f)) }

private fun setWindowClass(name: String) = runCatching {
    val toolkit = Toolkit.getDefaultToolkit()
    toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(toolkit, name)
}
