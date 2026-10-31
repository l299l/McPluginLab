package dev.mpl.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.PrintStream

class McpServer(private val tools: List<Tool>, private val input: BufferedReader, private val output: PrintStream) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run() {
        while (true) {
            val line = input.readLine() ?: return
            if (line.isBlank()) continue
            val message = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            val id = message["id"] ?: continue
            val method = message["method"]?.jsonPrimitive?.content ?: continue
            val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
            respond(id, method, params)
        }
    }

    private suspend fun respond(id: JsonElement, method: String, params: JsonObject) {
        val result = when (method) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", params["protocolVersion"] ?: JsonPrimitive(PROTOCOL_VERSION))
                putJsonObject("capabilities") { putJsonObject("tools") {} }
                putJsonObject("serverInfo") {
                    put("name", "mcpluginlab")
                    put("version", "0.1.0")
                }
                put("instructions", INSTRUCTIONS)
            }
            "ping" -> JsonObject(emptyMap())
            "tools/list" -> buildJsonObject { put("tools", buildJsonArray { tools.forEach { add(it.descriptor()) } }) }
            "tools/call" -> call(params)
            else -> return send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                putJsonObject("error") {
                    put("code", -32601)
                    put("message", "Method not found: $method")
                }
            })
        }
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        })
    }

    private suspend fun call(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.content
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val tool = tools.firstOrNull { it.name == name }
        val (text, isError) = when {
            tool == null -> "Unknown tool: $name" to true
            else -> runCatching { tool.handler(Args(arguments)) to false }.getOrElse { (it.message ?: it.toString()) to true }
        }
        return buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
            put("isError", isError)
        }
    }

    private fun send(message: JsonObject) {
        output.println(json.encodeToString(JsonObject.serializer(), message))
        output.flush()
    }

    private companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        val INSTRUCTIONS = """
            McPluginLab runs Minecraft test servers, Velocity proxies and SoulFire bots grouped in projects.
            Instances are addressed as "project/name". Typical loop: deploy_plugins, start servers with wait_ready,
            start a soulfire instance, then bot_command/send_command with `expect` to check the result in a server log.
            Use read_log with the returned cursor to read only new output.
        """.trimIndent()
    }
}

class Args(private val values: JsonObject) {
    fun string(name: String): String = optString(name) ?: throw IllegalArgumentException("Missing argument '$name'")
    fun optString(name: String): String? = (values[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    fun optLong(name: String): Long? = optString(name)?.toLong()
    fun optInt(name: String): Int? = optString(name)?.toInt()
    fun bool(name: String, default: Boolean): Boolean = optString(name)?.toBooleanStrict() ?: default
}
