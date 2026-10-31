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
data class InstanceId(val project: String, val name: String) {
    override fun toString() = "$project/$name"

    companion object {
        fun parse(value: String, defaultProject: String): InstanceId {
            val project = value.substringBefore('/', defaultProject)
            return InstanceId(project, value.substringAfter('/'))
        }
    }
}

@Serializable
data class ProjectDef(
    val name: String,
    val dir: String,
    val onlineMode: Boolean = false,
    val forwardingSecret: String = "",
    val plugins: List<PluginLink> = emptyList(),
)

@Serializable
data class PluginLink(
    val name: String,
    val source: String,
    val targets: List<String> = emptyList(),
    val autoDeploy: Boolean = true,
    val restart: Boolean = true,
)

@Serializable
data class PluginStatus(
    val link: PluginLink,
    val jar: String? = null,
    val deployedJar: String? = null,
    val deployedAt: Long? = null,
    val error: String? = null,
)

@Serializable
data class UpdateProjectRequest(val onlineMode: Boolean? = null)

@Serializable
data class FileEntry(val path: String, val directory: Boolean, val size: Long)

@Serializable
data class FileContent(val path: String, val text: String)

@Serializable
data class ProjectInfo(
    val def: ProjectDef,
    val instances: Int,
    val running: Int,
)

@Serializable
data class CreateProjectRequest(val name: String, val dir: String? = null)

@Serializable
data class InstanceDef(
    val project: String,
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
    val id: InstanceId get() = InstanceId(project, name)
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
    val project: String,
    val name: String,
    val type: InstanceType,
    val version: String? = null,
    val build: Int? = null,
    val dir: String? = null,
    val port: Int? = null,
    val memory: String? = null,
    val java: String? = null,
    val jar: String? = null,
    val acceptEula: Boolean = true,
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
data class DaemonInfo(val port: Int, val token: String, val pid: Long, val version: String, val build: String = "")

@Serializable
data class TerminalSize(val cols: Int, val rows: Int)

@Serializable
sealed interface Event {
    @Serializable @SerialName("state")
    data class StateChanged(val id: InstanceId, val state: InstanceState, val exitCode: Int? = null) : Event

    @Serializable @SerialName("instances")
    data class InstancesChanged(val project: String) : Event

    @Serializable @SerialName("projects")
    data object ProjectsChanged : Event

    @Serializable @SerialName("plugins")
    data class PluginsChanged(val project: String) : Event
}
