package dev.mpl.daemon

import dev.mpl.api.ApiError
import dev.mpl.api.CommandRequest
import dev.mpl.api.CreateInstanceRequest
import dev.mpl.api.CreateProjectRequest
import dev.mpl.api.Event
import dev.mpl.api.FileContent
import dev.mpl.api.InstanceId
import dev.mpl.api.LogPage
import dev.mpl.api.MplJson
import dev.mpl.api.Ok
import dev.mpl.api.PluginLink
import dev.mpl.api.TerminalSize
import dev.mpl.api.UpdateProjectRequest
import dev.mpl.api.VersionList
import dev.mpl.api.WaitResult
import dev.mpl.core.Lab
import dev.mpl.core.NotFoundException
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

fun Application.module(daemon: Daemon) {
    install(ContentNegotiation) { json(MplJson) }
    install(WebSockets)
    install(StatusPages) {
        exception<NotFoundException> { call, e -> call.respond(HttpStatusCode.NotFound, ApiError(e.message.orEmpty())) }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiError(e.message.orEmpty())) }
        exception<IllegalStateException> { call, e -> call.respond(HttpStatusCode.Conflict, ApiError(e.message.orEmpty())) }
        exception<Throwable> { call, e -> call.respond(HttpStatusCode.InternalServerError, ApiError(e.toString())) }
    }
    install(createApplicationPlugin("TokenAuth") {
        onCall { call ->
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ") ?: call.request.queryParameters["token"]
            if (token != daemon.token) call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid token"))
        }
    })

    routing {
        route("/api") {
            get("/ping") { call.respond(Ok(message = Daemon.VERSION)) }

            post("/shutdown") {
                call.respond(Ok(message = "Shutting down"))
                thread { daemon.shutdown() }
            }

            get("/versions/{project}") {
                val project = call.parameters["project"]!!
                call.respond(VersionList(project, withContext(Dispatchers.IO) { daemon.lab.versions(project) }))
            }

            get("/java") { call.respond(withContext(Dispatchers.IO) { daemon.lab.javaInstallations() }) }

            webSocket("/events") {
                daemon.lab.events.collect { send(Frame.Text(MplJson.encodeToString(Event.serializer(), it))) }
            }

            get("/instances") { call.respond(daemon.lab.list(call.request.queryParameters["project"])) }

            route("/projects") {
                get { call.respond(daemon.lab.projects()) }
                post { call.respond(daemon.lab.createProject(call.receive<CreateProjectRequest>())) }

                route("/{project}") {
                    patch { call.respond(daemon.lab.updateProject(call.project, call.receive<UpdateProjectRequest>())) }
                    get("/plugins") { call.respond(daemon.lab.pluginStatuses(call.project)) }
                    post("/plugins") { call.respond(daemon.lab.savePlugin(call.project, call.receive<PluginLink>())) }
                    delete("/plugins/{plugin}") {
                        daemon.lab.removePlugin(call.project, call.parameters["plugin"]!!)
                        call.respond(Ok())
                    }
                    post("/deploy") {
                        val name = call.request.queryParameters["plugin"]
                        call.respond(withContext(Dispatchers.IO) { daemon.lab.deployPlugins(call.project, name) })
                    }
                    post("/network/sync") {
                        withContext(Dispatchers.IO) { daemon.lab.syncNetwork(call.project) }
                        call.respond(Ok())
                    }
                    delete {
                        daemon.lab.removeProject(call.parameters["project"]!!, call.request.queryParameters["deleteFiles"].toBoolean())
                        call.respond(Ok())
                    }
                    get("/instances") { call.respond(daemon.lab.list(call.parameters["project"]!!)) }
                    post("/instances") {
                        val request = call.receive<CreateInstanceRequest>().copy(project = call.parameters["project"]!!)
                        call.respond(daemon.lab.create(request))
                    }
                    route("/instances/{name}") { instanceRoutes(daemon.lab) }
                }
            }
        }
    }
}

private fun Route.instanceRoutes(lab: Lab) {
    get { call.respond(lab.info(call.instanceId)) }
    delete {
        lab.remove(call.instanceId, call.request.queryParameters["deleteFiles"].toBoolean())
        call.respond(Ok())
    }
    post("/start") { call.respond(lab.start(call.instanceId)) }
    post("/stop") { call.respond(if (call.waits) lab.stop(call.instanceId) else lab.requestStop(call.instanceId)) }
    post("/restart") { call.respond(if (call.waits) lab.restart(call.instanceId) else lab.requestRestart(call.instanceId)) }
    post("/kill") { call.respond(lab.kill(call.instanceId)) }
    post("/command") {
        lab.sendCommand(call.instanceId, call.receive<CommandRequest>().command)
        call.respond(Ok())
    }

    get("/log") {
        val log = lab.get(call.instanceId).process.log
        val since = call.request.queryParameters["since"]?.toLong() ?: -100
        val limit = call.request.queryParameters["limit"]?.toInt() ?: 500
        val waitMs = call.request.queryParameters["waitMs"]?.toLong() ?: 0
        val lines = log.awaitLines(since, limit, waitMs)
        call.respond(LogPage(lines, lines.lastOrNull()?.seq?.plus(1) ?: maxOf(since, log.nextSeq)))
    }

    get("/wait") {
        val process = lab.get(call.instanceId).process
        val regex = Regex(call.request.queryParameters["pattern"] ?: throw IllegalArgumentException("pattern is required"))
        val since = call.request.queryParameters["since"]?.toLong() ?: process.log.nextSeq
        val timeoutMs = call.request.queryParameters["timeoutMs"]?.toLong() ?: 60_000
        val line = process.waitForLog(regex, since, timeoutMs)
        call.respond(WaitResult(line != null, line, line?.seq?.plus(1) ?: process.log.nextSeq))
    }

    get("/files") { call.respond(lab.files(call.instanceId).list(call.request.queryParameters["path"].orEmpty())) }
    get("/file") { call.respond(lab.files(call.instanceId).read(call.request.queryParameters["path"] ?: throw IllegalArgumentException("path is required"))) }
    put("/file") {
        val content = call.receive<FileContent>()
        lab.files(call.instanceId).write(content.path, content.text)
        call.respond(Ok())
    }

    webSocket("/attach") {
        val process = lab.get(call.instanceId).process
        val output = launch {
            process.output.stream(withScrollback = true).collect { if (it.isNotEmpty()) send(Frame.Binary(true, it)) }
        }
        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> process.write(frame.readBytes())
                    is Frame.Text -> MplJson.decodeFromString<TerminalSize>(frame.readText()).let { process.resize(it.cols, it.rows) }
                    else -> Unit
                }
            }
        } finally {
            output.cancel()
        }
    }
}

private val ApplicationCall.project: String
    get() = parameters["project"]!!

private val ApplicationCall.waits: Boolean
    get() = request.queryParameters["wait"]?.toBoolean() ?: true

private val ApplicationCall.instanceId: InstanceId
    get() = InstanceId(parameters["project"]!!, parameters["name"]!!)
