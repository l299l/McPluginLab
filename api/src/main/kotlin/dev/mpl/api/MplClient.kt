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
import io.ktor.client.request.post
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

    suspend fun instances(): List<InstanceInfo> = http.get("/api/instances") { auth() }.parse()

    suspend fun instance(name: String): InstanceInfo = http.get("/api/instances/$name") { auth() }.parse()

    suspend fun create(req: CreateInstanceRequest): InstanceInfo = http.post("/api/instances") {
        auth(); contentType(ContentType.Application.Json); setBody(req)
    }.parse()

    suspend fun remove(name: String, deleteFiles: Boolean): Ok =
        http.delete("/api/instances/$name") { auth(); parameter("deleteFiles", deleteFiles) }.parse()

    suspend fun action(name: String, action: String): InstanceInfo =
        http.post("/api/instances/$name/$action") { auth() }.parse()

    suspend fun command(name: String, command: String): Ok = http.post("/api/instances/$name/command") {
        auth(); contentType(ContentType.Application.Json); setBody(CommandRequest(command))
    }.parse()

    suspend fun log(name: String, since: Long, limit: Int = 500, waitMs: Long = 0): LogPage =
        http.get("/api/instances/$name/log") {
            auth(); parameter("since", since); parameter("limit", limit); parameter("waitMs", waitMs)
        }.parse()

    suspend fun waitFor(name: String, pattern: String, since: Long?, timeoutMs: Long): WaitResult =
        http.get("/api/instances/$name/wait") {
            auth(); parameter("pattern", pattern); since?.let { parameter("since", it) }; parameter("timeoutMs", timeoutMs)
        }.parse()

    suspend fun versions(project: String): VersionList = http.get("/api/versions/$project") { auth() }.parse()

    suspend fun shutdown(): Ok = http.post("/api/shutdown") { auth() }.parse()

    suspend fun attach(name: String, block: suspend DefaultClientWebSocketSession.() -> Unit) =
        http.webSocket("ws://$address/api/instances/$name/attach?token=${info.token}", block = block)

    suspend fun events(block: suspend DefaultClientWebSocketSession.() -> Unit) =
        http.webSocket("ws://$address/api/events?token=${info.token}", block = block)

    override fun close() = http.close()

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
