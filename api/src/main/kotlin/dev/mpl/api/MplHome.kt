package dev.mpl.api

import java.nio.file.Path
import kotlin.io.path.Path

object MplHome {
    val path: Path by lazy { env("MPL_HOME") ?: xdg("XDG_DATA_HOME", ".local/share").resolve("mcpluginlab") }
    val cache: Path by lazy { env("MPL_CACHE") ?: xdg("XDG_CACHE_HOME", ".cache").resolve("mcpluginlab") }

    val daemonInfoFile: Path get() = path.resolve("daemon.json")

    private fun env(name: String): Path? = System.getenv(name)?.takeIf { it.isNotBlank() }?.let { Path(it) }

    private fun xdg(variable: String, fallback: String): Path = env(variable) ?: Path(System.getProperty("user.home"), fallback)
}
