package dev.mpl.api

import java.nio.file.Path
import kotlin.io.path.Path

object MplHome {
    val path: Path by lazy {
        System.getenv("MPL_HOME")?.takeIf { it.isNotBlank() }?.let { Path(it) }
            ?: Path(System.getProperty("user.home"), "McPluginLab")
    }

    val daemonInfoFile: Path get() = path.resolve("daemon.json")
}
