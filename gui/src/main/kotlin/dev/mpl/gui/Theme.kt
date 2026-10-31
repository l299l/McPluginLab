package dev.mpl.gui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import dev.mpl.api.InstanceState

private val colors = darkColorScheme(
    primary = Color(0xFF7DD3A0),
    onPrimary = Color(0xFF0E2A1A),
    secondary = Color(0xFF8AB4F8),
    background = Color(0xFF111317),
    surface = Color(0xFF16181D),
    surfaceVariant = Color(0xFF1E2128),
    surfaceContainer = Color(0xFF1A1D23),
    surfaceContainerHigh = Color(0xFF22262E),
    outline = Color(0xFF3A3F4A),
    outlineVariant = Color(0xFF2A2E36),
    error = Color(0xFFF28B82),
)

@Composable
fun MplTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors) {
    CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
}

val InstanceState.color: Color
    get() = when (this) {
        InstanceState.RUNNING -> Color(0xFF7DD3A0)
        InstanceState.STARTING, InstanceState.STOPPING -> Color(0xFFF5C46B)
        InstanceState.CRASHED -> Color(0xFFF28B82)
        InstanceState.STOPPED -> Color(0xFF6B7280)
    }

val InstanceState.isAlive: Boolean
    get() = this == InstanceState.RUNNING || this == InstanceState.STARTING || this == InstanceState.STOPPING
