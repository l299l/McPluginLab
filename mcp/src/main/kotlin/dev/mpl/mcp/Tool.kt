package dev.mpl.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class Param(val name: String, val type: String, val description: String, val required: Boolean)

class Tool(
    val name: String,
    private val description: String,
    private val params: List<Param>,
    val handler: suspend (Args) -> String,
) {
    fun wrap(around: suspend (suspend () -> String) -> String) = Tool(name, description, params) { args -> around { handler(args) } }

    fun descriptor(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                params.forEach { param ->
                    putJsonObject(param.name) {
                        put("type", param.type)
                        put("description", param.description)
                    }
                }
            }
            put("required", buildJsonArray { params.filter { it.required }.forEach { add(it.name) } })
        }
    }
}

class ToolBuilder(private val name: String, private val description: String) {
    private val params = mutableListOf<Param>()

    fun string(name: String, description: String, required: Boolean = true) = params.add(Param(name, "string", description, required))
    fun integer(name: String, description: String, required: Boolean = false) = params.add(Param(name, "integer", description, required))
    fun boolean(name: String, description: String, required: Boolean = false) = params.add(Param(name, "boolean", description, required))

    fun build(handler: suspend (Args) -> String) = Tool(name, description, params.toList(), handler)
}

fun tool(name: String, description: String, params: ToolBuilder.() -> Unit = {}, handler: suspend (Args) -> String): Tool =
    ToolBuilder(name, description).apply(params).build(handler)
