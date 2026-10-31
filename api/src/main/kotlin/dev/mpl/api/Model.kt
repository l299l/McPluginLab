package dev.mpl.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class InstanceType(val fillProject: String?, val isProxy: Boolean) {
    @SerialName("paper") PAPER("paper", false),
    @SerialName("folia") FOLIA("folia", false),
    @SerialName("velocity") VELOCITY("velocity", true),
    @SerialName("waterfall") WATERFALL("waterfall", true),
    @SerialName("custom") CUSTOM(null, false);

    val defaultStopCommand: String get() = if (this == VELOCITY || this == WATERFALL) "end" else "stop"
}

@Serializable
data class InstanceDef(
    val name: String,
    val type: InstanceType,
    val version: String = "",
    val build: Int? = null,
    val dir: String,
    val jar: String = "server.jar",
    val java: String = "java",
    val memory: String = "2G",
    val jvmArgs: List<String> = emptyList(),
    val args: List<String> = emptyList(),
    val port: Int? = null,
    val stopCommand: String? = null,
    val readyPattern: String? = null,
    val env: Map<String, String> = emptyMap(),
) {
    val effectiveStopCommand: String get() = stopCommand ?: type.defaultStopCommand
    val effectiveReadyPattern: String get() = readyPattern ?: DEFAULT_READY_PATTERN

    companion object {
        const val DEFAULT_READY_PATTERN = """Done \([0-9.,]+s\)!"""
    }
}

@Serializable
enum class InstanceState { STOPPED, STARTING, RUNNING, STOPPING, CRASHED }

@Serializable
data class InstanceInfo(
    val def: InstanceDef,
    val state: InstanceState,
    val pid: Long? = null,
    val exitCode: Int? = null,
    val nextLogSeq: Long = 0,
)

@Serializable
data class CreateInstanceRequest(
    val name: String,
    val type: InstanceType,
    val version: String? = null,
    val build: Int? = null,
    val dir: String? = null,
    val port: Int? = null,
    val memory: String? = null,
    val java: String? = null,
    val jar: String? = null,
    val acceptEula: Boolean = false,
    val offlineMode: Boolean = false,
)

@Serializable data class CommandRequest(val command: String)
@Serializable data class LogLine(val seq: Long, val text: String)
@Serializable data class LogPage(val lines: List<LogLine>, val next: Long)
@Serializable data class WaitResult(val matched: Boolean, val line: LogLine? = null, val next: Long)
@Serializable data class VersionList(val project: String, val versions: List<String>)
@Serializable data class ApiError(val error: String)
@Serializable data class Ok(val ok: Boolean = true, val message: String? = null)

@Serializable
data class DaemonInfo(val port: Int, val token: String, val pid: Long, val version: String)

@Serializable
data class TerminalSize(val cols: Int, val rows: Int)

@Serializable
sealed interface Event {
    @Serializable @SerialName("state")
    data class StateChanged(val name: String, val state: InstanceState, val exitCode: Int? = null) : Event

    @Serializable @SerialName("created")
    data class Created(val name: String) : Event

    @Serializable @SerialName("removed")
    data class Removed(val name: String) : Event
}
