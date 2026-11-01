package dev.mpl.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.io.path.exists
import kotlin.io.path.readText

class MplException(message: String) : RuntimeException(message)

class MplClient(val info: DaemonInfo) : AutoCloseable {
    private val address = "127.0.0.1:${info.port}"

    val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(MplJson) }
        install(WebSockets)
        install(HttpTimeout) {
            requestTimeoutMillis = 10 * 60_000
            connectTimeoutMillis = 3_000
        }
        defaultRequest { url("http://$address") }
        expectSuccess = false
    }

    private fun HttpRequestBuilder.auth() = bearerAuth(info.token)

    private suspend inline fun <reified T> HttpResponse.parse(): T {
        val text = bodyAsText()
        if (!status.isSuccess()) {
            val msg = runCatching { MplJson.decodeFromString<ApiError>(text).error }.getOrElse { text.ifBlank { status.toString() } }
            throw MplException(msg)
        }
        return MplJson.decodeFromString(text)
    }

    suspend fun ping(): Boolean = runCatching { http.get("/api/ping") { auth() }.status.isSuccess() }.getOrDefault(false)

    suspend fun projects(): List<ProjectInfo> = http.get("/api/projects") { auth() }.parse()

    suspend fun createProject(req: CreateProjectRequest): ProjectInfo = http.post("/api/projects") {
        auth(); contentType(ContentType.Application.Json); setBody(req)
    }.parse()

    suspend fun removeProject(project: String, deleteFiles: Boolean): Ok =
        http.delete("/api/projects/$project") { auth(); parameter("deleteFiles", deleteFiles) }.parse()

    suspend fun updateProject(project: String, req: UpdateProjectRequest): ProjectInfo = http.patch("/api/projects/$project") {
        auth(); contentType(ContentType.Application.Json); setBody(req)
    }.parse()

    suspend fun plugins(project: String): List<PluginStatus> = http.get("/api/projects/$project/plugins") { auth() }.parse()

    suspend fun savePlugin(project: String, link: PluginLink): List<PluginStatus> = http.post("/api/projects/$project/plugins") {
        auth(); contentType(ContentType.Application.Json); setBody(link)
    }.parse()

    suspend fun removePlugin(project: String, name: String): Ok = http.delete("/api/projects/$project/plugins/$name") { auth() }.parse()

    suspend fun deploy(project: String, plugin: String? = null): List<PluginStatus> =
        http.post("/api/projects/$project/deploy") { auth(); plugin?.let { parameter("plugin", it) } }.parse()

    suspend fun group(project: String, action: String, instances: List<String> = emptyList(), wait: Boolean = false): List<InstanceInfo> =
        http.post("/api/projects/$project/$action") {
            auth(); contentType(ContentType.Application.Json); setBody(GroupRequest(instances, wait))
        }.parse()

    suspend fun updateInstance(id: InstanceId, req: UpdateInstanceRequest): InstanceInfo = http.patch(path(id)) {
        auth(); contentType(ContentType.Application.Json); setBody(req)
    }.parse()

    suspend fun syncNetwork(project: String): Ok = http.post("/api/projects/$project/network/sync") { auth() }.parse()

    suspend fun files(id: InstanceId, path: String = ""): List<FileEntry> =
        http.get("${path(id)}/files") { auth(); parameter("path", path) }.parse()

    suspend fun readFile(id: InstanceId, path: String): FileContent =
        http.get("${path(id)}/file") { auth(); parameter("path", path) }.parse()

    suspend fun writeFile(id: InstanceId, path: String, text: String): Ok = http.put("${path(id)}/file") {
        auth(); contentType(ContentType.Application.Json); setBody(FileContent(path, text))
    }.parse()

    suspend fun instances(project: String? = null): List<InstanceInfo> =
        http.get("/api/instances") { auth(); project?.let { parameter("project", it) } }.parse()

    suspend fun instance(id: InstanceId): InstanceInfo = http.get(path(id)) { auth() }.parse()

    suspend fun create(req: CreateInstanceRequest): InstanceInfo = http.post("/api/projects/${req.project}/instances") {
        auth(); contentType(ContentType.Application.Json); setBody(req)
    }.parse()

    suspend fun remove(id: InstanceId, deleteFiles: Boolean): Ok =
        http.delete(path(id)) { auth(); parameter("deleteFiles", deleteFiles) }.parse()

    suspend fun action(id: InstanceId, action: String, wait: Boolean = true): InstanceInfo =
        http.post("${path(id)}/$action") { auth(); parameter("wait", wait) }.parse()

    suspend fun command(id: InstanceId, command: String): Ok = http.post("${path(id)}/command") {
        auth(); contentType(ContentType.Application.Json); setBody(CommandRequest(command))
    }.parse()

    suspend fun log(id: InstanceId, since: Long, limit: Int = 500, waitMs: Long = 0): LogPage =
        http.get("${path(id)}/log") {
            auth(); parameter("since", since); parameter("limit", limit); parameter("waitMs", waitMs)
        }.parse()

    suspend fun waitFor(id: InstanceId, pattern: String, since: Long?, timeoutMs: Long): WaitResult =
        http.get("${path(id)}/wait") {
            auth(); parameter("pattern", pattern); since?.let { parameter("since", it) }; parameter("timeoutMs", timeoutMs)
        }.parse()

    suspend fun sendAndExpect(target: InstanceId, line: String, watched: InstanceId, pattern: String?, timeoutMs: Long): WaitResult? {
        var cursor = instance(watched).nextLogSeq
        command(target, line)
        pattern ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val result = waitFor(watched, pattern, cursor, maxOf(0, deadline - System.currentTimeMillis()))
            if (!result.matched || result.line!!.text.trim() != line.trim()) return result
            cursor = result.next
        }
    }

    suspend fun versions(project: String): VersionList = http.get("/api/versions/$project") { auth() }.parse()

    suspend fun javaInstallations(): List<String> = http.get("/api/java") { auth() }.parse()

    suspend fun shutdown(): Ok = http.post("/api/shutdown") { auth() }.parse()

    suspend fun attach(id: InstanceId, block: suspend DefaultClientWebSocketSession.() -> Unit) =
        http.webSocket("ws://$address${path(id)}/attach?token=${info.token}", block = block)

    suspend fun events(block: suspend DefaultClientWebSocketSession.() -> Unit) =
        http.webSocket("ws://$address/api/events?token=${info.token}", block = block)

    val isOutdated: Boolean get() = info.build != DaemonConnection.build

    override fun close() = http.close()

    private fun path(id: InstanceId) = "/api/projects/${id.project}/instances/${id.name}"

    companion object {
        fun fromHome(): MplClient? {
            val file = MplHome.daemonInfoFile
            if (!file.exists()) return null
            return runCatching { MplJson.decodeFromString<DaemonInfo>(file.readText()) }.getOrNull()?.let(::MplClient)
        }
    }
}

val MplJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "kind"
}
