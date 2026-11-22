package me.rerere.rikkihub.data.cli

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.mcp.McpManager
import me.rerere.rikkihub.data.ai.mcp.McpTool
import me.rerere.rikkihub.data.model.Assistant
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.uuid.Uuid

/** Snapshot entry of an enabled MCP tool exposed through [AcpMcpGateway]. */
data class GatewayTool(
    val serverId: Uuid,
    val serverName: String,
    val tool: McpTool,
)

/**
 * Loopback-only HTTP MCP server bridging RikkiHub's configured MCP tools to an ACP agent session.
 *
 * Binds exclusively to `127.0.0.1` on an OS-assigned ephemeral port (`0`) and authenticates every
 * `POST /mcp` request against a per-session random Bearer token using constant-time comparison.
 */
class AcpMcpGateway(
    private val listTools: () -> List<GatewayTool>,
    private val callTool: suspend (serverId: Uuid, toolName: String, args: JsonObject) -> List<UIMessagePart>,
) : Closeable {
    private val bearerToken: String = generateToken()
    private val bearerTokenBytes: ByteArray = bearerToken.toByteArray(Charsets.UTF_8)

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var boundPort: Int = -1

    val port: Int
        get() = boundPort.also { check(it > 0) { "AcpMcpGateway is not started" } }

    val endpointUrl: String
        get() = "http://127.0.0.1:$port/mcp"

    /** Starts the loopback Ktor CIO server and resolves the bound ephemeral port. */
    suspend fun start(): AcpMcpGateway {
        if (server != null) return this
        val created = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                post("/mcp") {
                    handleMcpPost(call)
                }
            }
        }.start(wait = false)
        val resolvedPort = runCatching {
            created.engine.resolvedConnectors().firstOrNull()?.port
        }.getOrNull()
        if (resolvedPort == null || resolvedPort <= 0) {
            runCatching { created.stop(100, 500) }
            throw IllegalStateException("Failed to bind loopback port for AcpMcpGateway")
        }
        server = created
        boundPort = resolvedPort
        return this
    }

    /** Returns the ACP `mcpServers` entry describing this loopback HTTP MCP server. */
    fun toAcpMcpServerJson(): JsonObject = buildJsonObject {
        put("type", "http")
        put("name", SERVER_NAME)
        put("url", endpointUrl)
        putJsonArray("headers") {
            add(
                buildJsonObject {
                    put("name", HttpHeaders.Authorization)
                    put("value", "Bearer $bearerToken")
                },
            )
        }
    }

    override fun close() {
        val running = server ?: return
        server = null
        boundPort = -1
        runCatching { running.stop(100, 500) }
    }

    private suspend fun handleMcpPost(call: ApplicationCall) {
        if (!isAuthorized(call.request.headers[HttpHeaders.Authorization])) {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        val bodyText = call.receiveText()
        val request = runCatching { Json.parseToJsonElement(bodyText) as? JsonObject }.getOrNull()
        if (request == null) {
            call.respondJsonRpc(jsonRpcError(JsonNull, -32700, "Parse error"))
            return
        }

        val id = request["id"]
        val method = request.str("method")
        if (method == null) {
            call.respondJsonRpc(jsonRpcError(id ?: JsonNull, -32600, "Invalid Request"))
            return
        }

        if (method == "notifications/initialized") {
            call.respond(HttpStatusCode.Accepted)
            return
        }

        val params = request.obj("params") ?: JsonObject(emptyMap())
        val responseId = id ?: JsonNull
        val response = when (method) {
            "initialize" -> handleInitialize(responseId, params)
            "tools/list" -> handleToolsList(responseId)
            "tools/call" -> handleToolsCall(responseId, params)
            else -> jsonRpcError(responseId, -32601, "Method not found: $method")
        }
        call.respondJsonRpc(response)
    }

    private fun isAuthorized(authHeader: String?): Boolean {
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) return false
        val providedBytes = authHeader.substring(BEARER_PREFIX.length).toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(bearerTokenBytes, providedBytes)
    }

    private fun handleInitialize(id: JsonElement, params: JsonObject): JsonObject {
        val protocolVersion = params.str("protocolVersion") ?: DEFAULT_MCP_PROTOCOL_VERSION
        return jsonRpcResult(
            id = id,
            result = buildJsonObject {
                put("protocolVersion", protocolVersion)
                putJsonObject("capabilities") {
                    putJsonObject("tools") {}
                }
                putJsonObject("serverInfo") {
                    put("name", SERVER_NAME)
                    put("version", SERVER_VERSION)
                }
            },
        )
    }

    private fun handleToolsList(id: JsonElement): JsonObject {
        val whitelisted = resolveWhitelistedTools()
        val toolsArray = buildJsonArray {
            for ((exposedName, entry) in whitelisted) {
                add(
                    buildJsonObject {
                        put("name", exposedName)
                        entry.tool.description?.let { put("description", it) }
                        put("inputSchema", entry.tool.inputSchema.toMcpInputSchema())
                    },
                )
            }
        }
        return jsonRpcResult(
            id = id,
            result = buildJsonObject {
                put("tools", toolsArray)
            },
        )
    }

    private suspend fun handleToolsCall(id: JsonElement, params: JsonObject): JsonObject {
        val requestedName = params.str("name")
        val target = requestedName?.let { resolveWhitelistedTools()[it] }
            ?: return jsonRpcError(id, -32602, "Unknown or disabled tool: $requestedName")

        val args = params.obj("arguments") ?: JsonObject(emptyMap())
        val parts = try {
            callTool(target.serverId, target.tool.name, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return jsonRpcResult(
                id = id,
                result = buildJsonObject {
                    putJsonArray("content") {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", e.message ?: e.javaClass.simpleName)
                            },
                        )
                    }
                    put("isError", true)
                },
            )
        }

        return jsonRpcResult(
            id = id,
            result = buildJsonObject {
                putJsonArray("content") {
                    for (part in parts) {
                        add(part.toMcpContent())
                    }
                }
                put("isError", false)
            },
        )
    }

    private fun resolveWhitelistedTools(): Map<String, GatewayTool> {
        val usedNames = HashSet<String>()
        val result = LinkedHashMap<String, GatewayTool>()
        for (entry in listTools()) {
            if (!entry.tool.enable) continue
            val sanitizedServer = entry.serverName
                .replace(NON_IDENTIFIER_REGEX, "_")
                .trim('_')
                .ifEmpty { "server" }
            var exposedName = "${sanitizedServer}__${entry.tool.name}"
            if (exposedName in usedNames) {
                val shortId = entry.serverId.toString().take(8)
                exposedName = "${sanitizedServer}_${shortId}__${entry.tool.name}"
                var suffix = 2
                while (exposedName in usedNames) {
                    exposedName = "${sanitizedServer}_${shortId}_${suffix}__${entry.tool.name}"
                    suffix++
                }
            }
            usedNames.add(exposedName)
            result[exposedName] = entry
        }
        return result
    }

    private suspend fun ApplicationCall.respondJsonRpc(payload: JsonObject) {
        respondText(
            text = payload.toString(),
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.OK,
        )
    }

    companion object {
        const val SERVER_NAME = "rikkihub-mcp"
        const val SERVER_VERSION = "1.0.0"
        private const val DEFAULT_MCP_PROTOCOL_VERSION = "2025-03-26"
        private const val BEARER_PREFIX = "Bearer "
        private val NON_IDENTIFIER_REGEX = Regex("[^a-zA-Z0-9_-]")
        private val secureRandom = SecureRandom()

        fun forAssistant(mcpManager: McpManager, assistant: Assistant): AcpMcpGateway =
            AcpMcpGateway(
                listTools = {
                    mcpManager.getAllAvailableTools(assistant).map { (serverId, serverName, tool) ->
                        GatewayTool(serverId = serverId, serverName = serverName, tool = tool)
                    }
                },
                callTool = mcpManager::callTool,
            )

        private fun generateToken(): String {
            val bytes = ByteArray(32)
            secureRandom.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun jsonRpcResult(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

        private fun jsonRpcError(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }

        private fun InputSchema?.toMcpInputSchema(): JsonObject = when (this) {
            is InputSchema.Obj -> buildJsonObject {
                put("type", "object")
                put("properties", properties)
                required?.takeIf { it.isNotEmpty() }?.let { req ->
                    putJsonArray("required") {
                        req.forEach { add(JsonPrimitive(it)) }
                    }
                }
            }
            null -> buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            }
        }

        private fun UIMessagePart.toMcpContent(): JsonObject = buildJsonObject {
            put("type", "text")
            put(
                "text",
                when (this@toMcpContent) {
                    is UIMessagePart.Text -> text
                    is UIMessagePart.Image -> "[image: $url]"
                    is UIMessagePart.Document -> "[document: $fileName ($url)]"
                    is UIMessagePart.Audio -> "[audio: $url]"
                    is UIMessagePart.Video -> "[video: $url]"
                    else -> toString()
                },
            )
        }
    }
}
