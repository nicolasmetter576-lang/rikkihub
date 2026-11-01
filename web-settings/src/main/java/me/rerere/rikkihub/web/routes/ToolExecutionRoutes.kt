package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkihub.data.ai.mcp.McpServerConfig
import me.rerere.rikkihub.data.ai.tools.BuiltinToolCatalog
import me.rerere.rikkihub.data.ai.tools.MAX_PARALLEL_TOOL_CALLS
import me.rerere.rikkihub.data.ai.tools.ToolConcurrencyDefaults
import me.rerere.rikkihub.data.ai.tools.ToolExecutionConfig
import me.rerere.rikkihub.data.ai.tools.isBuiltinParallel
import me.rerere.rikkihub.data.ai.tools.isParallel
import me.rerere.rikkihub.data.ai.tools.resolvedName
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.web.BadRequestException

/**
 * 工具调用并行执行（`Settings.toolExecution` + 各 MCP 工具的 `parallel`）。App 与 Web
 * 改的是同一份。`GET` 返回全局配置、内置工具目录与 MCP 工具的生效值；`PUT` 为
 * merge-patch：只改出现的键，缺键保留；覆盖值 `null` = 恢复默认。
 */
fun Route.toolExecutionRoutes(settingsStore: SettingsStore) {
    route("/tool-execution") {
        get {
            call.respond(HttpStatusCode.OK, projectToolExecution(settingsStore.settingsFlow.value))
        }

        put {
            val body = call.receive<JsonObject>()
            var result: Settings? = null
            settingsStore.update { settings ->
                mergeToolExecution(settings, body).also { result = it }
            }
            call.respond(HttpStatusCode.OK, projectToolExecution(result ?: settingsStore.settingsFlow.value))
        }
    }
}

@Serializable
data class ToolExecutionDto(
    val parallel: Boolean,
    val maxParallel: Int,
    val maxParallelLimit: Int = MAX_PARALLEL_TOOL_CALLS,
    val builtinTools: List<BuiltinToolExecutionDto>,
    val mcpTools: List<McpToolExecutionDto>,
)

@Serializable
data class BuiltinToolExecutionDto(
    val id: String,
    /** 当前 wire 名（含 ToolOverride 改名）。 */
    val name: String,
    val category: String,
    val defaultParallel: Boolean,
    val parallel: Boolean,
    /** 用户是否覆盖了默认值。 */
    val overridden: Boolean,
    /** 只有部分参数能并行（如 str_replace_editor 仅 view）。 */
    val argumentLimited: Boolean,
)

@Serializable
data class McpToolExecutionDto(
    val serverId: String,
    val serverName: String,
    val tool: String,
    val enabled: Boolean,
    val readOnlyHint: Boolean? = null,
    /** 用户覆盖；null = 跟随 readOnlyHint。 */
    val override: Boolean? = null,
    val parallel: Boolean,
)

fun projectToolExecution(settings: Settings): ToolExecutionDto {
    val config = settings.toolExecution
    return ToolExecutionDto(
        parallel = config.parallel,
        maxParallel = config.maxParallel.coerceIn(1, MAX_PARALLEL_TOOL_CALLS),
        builtinTools = ToolConcurrencyDefaults.configurableIds().map { id ->
            val spec = BuiltinToolCatalog.require(id)
            BuiltinToolExecutionDto(
                id = id,
                name = spec.resolvedName(settings.toolOverrides),
                category = spec.category.name,
                defaultParallel = id in ToolConcurrencyDefaults.PARALLEL_BY_DEFAULT,
                parallel = config.isBuiltinParallel(id),
                overridden = id in config.parallelOverrides,
                argumentLimited = ToolConcurrencyDefaults.argumentFilter(id) != null,
            )
        },
        mcpTools = settings.mcpServers.flatMap { server ->
            server.commonOptions.tools.map { tool ->
                McpToolExecutionDto(
                    serverId = server.id.toString(),
                    serverName = server.commonOptions.name,
                    tool = tool.name,
                    enabled = tool.enable,
                    readOnlyHint = tool.readOnlyHint,
                    override = tool.parallel,
                    parallel = tool.isParallel,
                )
            }
        },
    )
}

private val TOOL_EXECUTION_KEYS = setOf("parallel", "maxParallel", "builtinOverrides", "mcpTools")
private val MCP_TOOL_PATCH_KEYS = setOf("serverId", "tool", "parallel")

/**
 * merge-patch（纯函数，单测直接断言）：
 * - 未知键 / 空对象 → 400；
 * - `parallel` 布尔；`maxParallel` 1..[MAX_PARALLEL_TOOL_CALLS] 的 JSON 整数；
 * - `builtinOverrides`：`{id: true|false|null}`，id 必须是可配置的内置工具；与默认值相同
 *   或为 null 时移除覆盖；
 * - `mcpTools`：`[{serverId, tool, parallel: true|false|null}]`，服务器与工具必须存在。
 */
fun mergeToolExecution(settings: Settings, body: JsonObject): Settings {
    val unknown = body.keys - TOOL_EXECUTION_KEYS
    if (unknown.isNotEmpty()) throw BadRequestException("unknown field: ${unknown.sorted().joinToString()}")
    if (body.isEmpty()) throw BadRequestException("no field to update")
    var config: ToolExecutionConfig = settings.toolExecution
    body["parallel"]?.let { value ->
        config = config.copy(parallel = value.booleanOrThrow("parallel"))
    }
    body["maxParallel"]?.let { value ->
        config = config.copy(maxParallel = value.boundedIntOrThrow("maxParallel", 1, MAX_PARALLEL_TOOL_CALLS))
    }
    body["builtinOverrides"]?.let { value ->
        val patch = value as? JsonObject ?: throw BadRequestException("builtinOverrides must be an object")
        val configurable = ToolConcurrencyDefaults.configurableIds().toSet()
        val overrides = config.parallelOverrides.toMutableMap()
        patch.forEach { (id, element) ->
            if (id !in configurable) throw BadRequestException("unknown builtin tool: $id")
            val parallel = element.nullableBooleanOrThrow("builtinOverrides.$id")
            val default = id in ToolConcurrencyDefaults.PARALLEL_BY_DEFAULT
            if (parallel == null || parallel == default) overrides.remove(id) else overrides[id] = parallel
        }
        config = config.copy(parallelOverrides = overrides)
    }
    var mcpServers = settings.mcpServers
    body["mcpTools"]?.let { value ->
        val patches = value as? JsonArray ?: throw BadRequestException("mcpTools must be an array")
        patches.forEach { element ->
            val patch = element as? JsonObject ?: throw BadRequestException("mcpTools entries must be objects")
            val unknownKeys = patch.keys - MCP_TOOL_PATCH_KEYS
            if (unknownKeys.isNotEmpty()) {
                throw BadRequestException("unknown mcpTools field: ${unknownKeys.sorted().joinToString()}")
            }
            val serverId = patch["serverId"].stringOrThrow("mcpTools.serverId")
            val toolName = patch["tool"].stringOrThrow("mcpTools.tool")
            if ("parallel" !in patch) throw BadRequestException("mcpTools.parallel is required")
            val parallel = patch.getValue("parallel").nullableBooleanOrThrow("mcpTools.parallel")
            val server = mcpServers.firstOrNull { it.id.toString() == serverId }
                ?: throw BadRequestException("unknown MCP server: $serverId")
            if (server.commonOptions.tools.none { it.name == toolName }) {
                throw BadRequestException("unknown MCP tool: $toolName")
            }
            mcpServers = mcpServers.map { candidate ->
                if (candidate !== server) candidate else candidate.withToolParallel(toolName, parallel)
            }
        }
    }
    return settings.copy(toolExecution = config, mcpServers = mcpServers)
}

private fun McpServerConfig.withToolParallel(toolName: String, parallel: Boolean?): McpServerConfig =
    clone(
        commonOptions = commonOptions.copy(
            tools = commonOptions.tools.map { tool ->
                if (tool.name == toolName) tool.copy(parallel = parallel) else tool
            }
        )
    )

private fun JsonElement.booleanOrThrow(field: String): Boolean =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.booleanOrNull
        ?: throw BadRequestException("$field must be a boolean")

private fun JsonElement.nullableBooleanOrThrow(field: String): Boolean? =
    if (this is JsonNull) null else booleanOrThrow(field)

private fun JsonElement?.stringOrThrow(field: String): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        ?: throw BadRequestException("$field must be a non-empty string")

private fun JsonElement.boundedIntOrThrow(field: String, min: Int, max: Int): Int {
    val primitive = this as? JsonPrimitive
    val message = "$field must be an integer between $min and $max"
    if (primitive == null || primitive is JsonNull || primitive.isString) throw BadRequestException(message)
    if (!primitive.content.all(Char::isDigit)) throw BadRequestException(message)
    val value = primitive.longOrNull ?: throw BadRequestException(message)
    if (value < min || value > max) throw BadRequestException(message)
    return value.toInt()
}
