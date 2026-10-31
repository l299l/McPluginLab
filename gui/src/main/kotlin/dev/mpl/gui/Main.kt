package dev.mpl.gui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.mpl.api.MplClient

fun runGui(client: MplClient) = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "McPluginLab",
        state = rememberWindowState(width = 1360.dp, height = 860.dp),
    ) {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(client, scope) }
        MplTheme { App(state) }
    }
}
