package me.rerere.rikkihub.data.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.InputStream
import java.io.OutputStream

/** What the agent declared in `initialize`. */
data class AcpAgentInfo(
    val name: String,
    val version: String,
    val loadSession: Boolean,
    val resume: Boolean,
    val close: Boolean,
    val imagePrompts: Boolean,
    val authMethods: List<AcpChoice>,
    /** agy's `_meta.antigravity.systemPrompt.append` extension. */
    val agySystemPromptAppend: Boolean,
    val mcpHttp: Boolean = false,
)

data class AcpChoice(val id: String, val name: String, val description: String? = null)

/** An ACP `select` session config option (model, thinking level, mode, …). */
data class AcpConfigOption(
    val id: String,
    val name: String,
    val category: String?,
    val currentValue: String,
    val options: List<AcpChoice>,
)

data class AcpCommand(val name: String, val description: String, val inputHint: String? = null)

/**
 * Session state the agent reports outside the transcript. [configOptions] is the standard ACP
 * surface; [models]/[modes] are the older one, used only when an agent offers no config options.
 */
data class AcpRuntimeInfo(
    val configOptions: List<AcpConfigOption> = emptyList(),
    val models: List<AcpChoice> = emptyList(),
    val currentModelId: String? = null,
    val modes: List<AcpChoice> = emptyList(),
    val currentModeId: String? = null,
    val commands: List<AcpCommand> = emptyList(),
    val usedTokens: Long? = null,
    val contextTokens: Long? = null,
    val title: String? = null,
) {
    val currentModelName: String? get() = currentName("model", models, currentModelId)
    val currentModeName: String? get() = currentName("mode", modes, currentModeId)

    private fun currentName(category: String, choices: List<AcpChoice>, currentId: String?): String? =
        configOptions.firstOrNull { it.category == category }
            ?.let { option -> option.options.firstOrNull { it.id == option.currentValue }?.name }
            ?: choices.firstOrNull { it.id == currentId }?.name
}

data class AcpPermissionOption(val optionId: String, val name: String, val kind: String)

data class AcpPermissionRequest(
    val toolCallId: String,
    val toolCall: JsonObject,
    val options: List<AcpPermissionOption>,
)

sealed interface AcpPermissionOutcome {
    data class Selected(val optionId: String) : AcpPermissionOutcome
    data object Cancelled : AcpPermissionOutcome
}

sealed interface AcpOpenResult {
    val sessionId: String

    data class Resumed(override val sessionId: String) : AcpOpenResult

    /** A new session; [previousLost] = a saved session id could not be resumed or loaded. */
    data class Created(override val sessionId: String, val previousLost: Boolean) : AcpOpenResult
}

data class AcpPromptResult(val stopReason: String, val usage: JsonObject?)

/**
 * One ACP agent process serving one conversation: handshake, opening a session (resume first,
 * see [open]), prompt turns with their updates and permission requests, and session settings.
 * [closeProcess] tells the process to go away (stdin EOF, then signals).
 */
class AcpSession(
    stdout: InputStream,
    stdin: OutputStream,
    stderr: InputStream,
    private val scope: CoroutineScope,
    private val closeProcess: () -> Unit,
) {
    private class Turn(
        val onUpdate: (JsonObject) -> Unit,
        val onPermission: suspend (AcpPermissionRequest) -> AcpPermissionOutcome,
    ) {
        val scope = CoroutineScope(SupervisorJob())
    }

    private val client = AcpClient(
        input = stdout,
        output = stdin,
        stderr = stderr,
        scope = scope,
        onRequest = ::onRequest,
        onNotification = ::onNotification,
    )

    @Volatile private var turn: Turn? = null
    // Session whose updates we accept; set before new/load/resume so load replays are seen.
    @Volatile private var listeningSessionId: String? = null
    @Volatile private var replayHadHistory = false

    private val _runtime = MutableStateFlow(AcpRuntimeInfo())
    val runtime: StateFlow<AcpRuntimeInfo> = _runtime.asStateFlow()

    lateinit var agent: AcpAgentInfo
        private set

    var sessionId: String? = null
        private set

    val closed get() = client.closed
    val isClosed: Boolean get() = client.closed.isCompleted

    fun stderrTail(): String = client.stderrTail()

    suspend fun initialize(): AcpAgentInfo {
        client.start()
        val result = client.request("initialize", buildJsonObject {
            put("protocolVersion", 1)
            putJsonObject("clientCapabilities") {}
        }) as JsonObject
        val capabilities = result.obj("agentCapabilities")
        val sessionCapabilities = capabilities?.obj("sessionCapabilities")
        agent = AcpAgentInfo(
            name = result.obj("agentInfo")?.str("name").orEmpty(),
            version = result.obj("agentInfo")?.str("version").orEmpty(),
            loadSession = capabilities?.get("loadSession") == JsonPrimitive(true),
            resume = sessionCapabilities?.containsKey("resume") == true,
            close = sessionCapabilities?.containsKey("close") == true,
            imagePrompts = capabilities?.obj("promptCapabilities")?.get("image") == JsonPrimitive(true),
            authMethods = result.arr("authMethods").mapNotNull { method ->
                val obj = method as? JsonObject ?: return@mapNotNull null
                AcpChoice(obj.str("id") ?: return@mapNotNull null, obj.str("name").orEmpty(), obj.str("description"))
            },
            agySystemPromptAppend = capabilities?.obj("_meta")?.obj("antigravity")?.obj("systemPrompt")
                ?.get("append") == JsonPrimitive(true),
            mcpHttp = capabilities?.obj("mcpCapabilities")?.get("http") == JsonPrimitive(true),
        )
        return agent
    }

    suspend fun authenticate(methodId: String) {
        client.request("authenticate", buildJsonObject { put("methodId", methodId) })
    }

    /**
     * Opens the conversation's session. A saved id is resumed when the agent supports it (no
     * replay; an unknown id is an error). Otherwise it is loaded, ignoring the replayed history,
     * and a replay without any user message counts as lost: agy "loads" unknown ids as empty
     * sessions. A lost or missing id becomes a new session carrying [newSessionMeta]. Auth errors propagate.
     */
    suspend fun open(
        savedSessionId: String?,
        cwd: String,
        newSessionMeta: JsonObject?,
        mcpServers: JsonArray = JsonArray(emptyList()),
    ): AcpOpenResult {
        if (savedSessionId != null) {
            if (tryReopen(savedSessionId, cwd, mcpServers)) return AcpOpenResult.Resumed(savedSessionId)
            listeningSessionId = null
        }
        val result = client.request("session/new", buildJsonObject {
            put("cwd", cwd)
            put("mcpServers", mcpServers)
            newSessionMeta?.let { put("_meta", it) }
        }) as JsonObject
        val id = result.str("sessionId") ?: throw AcpException(AcpException.INTERNAL_ERROR, "session/new returned no sessionId")
        activate(id, result)
        return AcpOpenResult.Created(id, previousLost = savedSessionId != null)
    }

    private suspend fun tryReopen(
        id: String,
        cwd: String,
        mcpServers: JsonArray = JsonArray(emptyList()),
    ): Boolean {
        val params = buildJsonObject {
            put("sessionId", id)
            put("cwd", cwd)
            put("mcpServers", mcpServers)
        }
        try {
            when {
                agent.resume -> {
                    listeningSessionId = id
                    activate(id, client.request("session/resume", params) as? JsonObject)
                    return true
                }
                agent.loadSession -> {
                    listeningSessionId = id
                    replayHadHistory = false
                    val result = client.request("session/load", params) as? JsonObject
                    if (!replayHadHistory) return false
                    activate(id, result)
                    return true
                }
                else -> return false
            }
        } catch (error: AcpException) {
            if (error.code == AcpException.AUTH_REQUIRED) throw error
            return false
        }
    }

    private fun activate(id: String, result: JsonObject?) {
        sessionId = id
        listeningSessionId = id
        _runtime.update { it.copy(usedTokens = null, contextTokens = null) }
        result?.let { applySessionState(it) }
    }

    /**
     * One prompt turn. Updates of this session go to [onUpdate] (the user-message echo is dropped)
     * until the turn ends; permission requests go to [onPermission]. Cancelling the caller answers
     * open permission requests with `cancelled`, sends `session/cancel` and waits up to
     * [CANCEL_WAIT_MS] for the agent to end the turn, closing the process if it does not.
     */
    suspend fun prompt(
        blocks: List<JsonObject>,
        onUpdate: (JsonObject) -> Unit,
        onPermission: suspend (AcpPermissionRequest) -> AcpPermissionOutcome,
    ): AcpPromptResult {
        val id = checkNotNull(sessionId) { "No ACP session is open" }
        val current = Turn(onUpdate, onPermission)
        turn = current
        try {
            val response = client.call("session/prompt", buildJsonObject {
                put("sessionId", id)
                put("prompt", JsonArray(blocks))
            })
            try {
                val result = response.await() as? JsonObject
                return AcpPromptResult(result?.str("stopReason") ?: "end_turn", result?.obj("usage"))
            } catch (cancel: CancellationException) {
                withContext(NonCancellable) {
                    current.scope.cancel()
                    runCatching { client.notify("session/cancel", buildJsonObject { put("sessionId", id) }) }
                    val ended = withTimeoutOrNull(CANCEL_WAIT_MS) { runCatching { response.await() } } != null
                    if (!ended) closeProcess()
                }
                throw cancel
            }
        } finally {
            current.scope.cancel()
            if (turn === current) turn = null
        }
    }

    suspend fun setConfigOption(configId: String, value: String) {
        val id = checkNotNull(sessionId)
        val result = client.request("session/set_config_option", buildJsonObject {
            put("sessionId", id)
            put("configId", configId)
            put("value", value)
        }) as? JsonObject
        val options = result?.get("configOptions") as? JsonArray
        if (options != null) {
            _runtime.update { it.copy(configOptions = parseConfigOptions(options)) }
        } else {
            _runtime.update { info ->
                info.copy(configOptions = info.configOptions.map { if (it.id == configId) it.copy(currentValue = value) else it })
            }
        }
    }

    suspend fun setModel(modelId: String) {
        client.request("session/set_model", buildJsonObject {
            put("sessionId", checkNotNull(sessionId))
            put("modelId", modelId)
        })
        _runtime.update { it.copy(currentModelId = modelId) }
    }

    suspend fun setMode(modeId: String) {
        client.request("session/set_mode", buildJsonObject {
            put("sessionId", checkNotNull(sessionId))
            put("modeId", modeId)
        })
        _runtime.update { it.copy(currentModeId = modeId) }
    }

    /** Best effort: tells the agent it may drop [id] (prompt changed, conversation reset). */
    suspend fun closeSession(id: String) {
        if (!agent.close) return
        runCatching { client.request("session/close", buildJsonObject { put("sessionId", id) }) }
        if (sessionId == id) sessionId = null
    }

    fun close() {
        turn?.scope?.cancel()
        closeProcess()
    }

    /** True once the agent's stdout and stderr have both ended (after [close] or a crash). */
    suspend fun awaitReaders() = client.awaitReaders()

    private suspend fun onRequest(method: String, params: JsonObject): JsonElement {
        if (method != "session/request_permission") {
            throw AcpException(AcpException.METHOD_NOT_FOUND, "Method not supported: $method")
        }
        val current = turn
        val request = AcpPermissionRequest(
            toolCallId = params.obj("toolCall")?.str("toolCallId").orEmpty(),
            toolCall = params.obj("toolCall") ?: JsonObject(emptyMap()),
            options = params.arr("options").mapNotNull { option ->
                val obj = option as? JsonObject ?: return@mapNotNull null
                AcpPermissionOption(obj.str("optionId") ?: return@mapNotNull null, obj.str("name").orEmpty(), obj.str("kind").orEmpty())
            },
        )
        val outcome = if (current == null || params.str("sessionId") != sessionId) {
            AcpPermissionOutcome.Cancelled
        } else {
            try {
                current.scope.async { current.onPermission(request) }.await()
            } catch (_: CancellationException) {
                AcpPermissionOutcome.Cancelled
            }
        }
        return buildJsonObject {
            putJsonObject("outcome") {
                when (outcome) {
                    is AcpPermissionOutcome.Selected -> {
                        put("outcome", "selected")
                        put("optionId", outcome.optionId)
                    }
                    AcpPermissionOutcome.Cancelled -> put("outcome", "cancelled")
                }
            }
        }
    }

    private fun onNotification(method: String, params: JsonObject) {
        if (method != "session/update" || params.str("sessionId") != listeningSessionId) return
        val update = params.obj("update") ?: return
        when (update.str("sessionUpdate")) {
            "available_commands_update" -> _runtime.update { info ->
                info.copy(commands = update.arr("availableCommands").mapNotNull { command ->
                    val obj = command as? JsonObject ?: return@mapNotNull null
                    AcpCommand(
                        name = obj.str("name") ?: return@mapNotNull null,
                        description = obj.str("description").orEmpty(),
                        inputHint = obj.obj("input")?.str("hint"),
                    )
                })
            }
            "current_mode_update" -> _runtime.update { it.copy(currentModeId = update.str("currentModeId")) }
            "config_option_update" -> _runtime.update { info ->
                info.copy(configOptions = parseConfigOptions(update["configOptions"] as? JsonArray ?: JsonArray(emptyList())))
            }
            "usage_update" -> _runtime.update {
                it.copy(usedTokens = update.long("used"), contextTokens = update.long("size"))
            }
            "session_info_update" -> update.str("title")?.let { title -> _runtime.update { it.copy(title = title) } }
            "user_message_chunk" -> if (turn == null) replayHadHistory = true
            else -> turn?.onUpdate?.invoke(update)
        }
    }

    private fun applySessionState(result: JsonObject) {
        _runtime.update { info ->
            val models = result.obj("models")
            val modes = result.obj("modes")
            info.copy(
                configOptions = (result["configOptions"] as? JsonArray)?.let(::parseConfigOptions) ?: info.configOptions,
                models = models?.arr("availableModels")?.mapNotNull { model ->
                    val obj = model as? JsonObject ?: return@mapNotNull null
                    AcpChoice(obj.str("modelId") ?: return@mapNotNull null, obj.str("name").orEmpty(), obj.str("description"))
                } ?: info.models,
                currentModelId = models?.str("currentModelId") ?: info.currentModelId,
                modes = modes?.arr("availableModes")?.mapNotNull { mode ->
                    val obj = mode as? JsonObject ?: return@mapNotNull null
                    AcpChoice(obj.str("id") ?: return@mapNotNull null, obj.str("name").orEmpty(), obj.str("description"))
                } ?: info.modes,
                currentModeId = modes?.str("currentModeId") ?: info.currentModeId,
            )
        }
    }

    companion object {
        const val CANCEL_WAIT_MS = 5_000L

        private fun parseConfigOptions(options: JsonArray): List<AcpConfigOption> = options.mapNotNull { option ->
            val obj = option as? JsonObject ?: return@mapNotNull null
            if (obj.str("type") != "select") return@mapNotNull null
            AcpConfigOption(
                id = obj.str("id") ?: return@mapNotNull null,
                name = obj.str("name").orEmpty(),
                category = obj.str("category"),
                currentValue = obj.str("currentValue").orEmpty(),
                // Grouped options ({group, options: [...]}) are flattened.
                options = obj.arr("options").flatMap { entry ->
                    val item = entry as? JsonObject ?: return@flatMap emptyList()
                    if (item.containsKey("group")) item.arr("options").mapNotNull { it as? JsonObject } else listOf(item)
                }.mapNotNull { item ->
                    AcpChoice(item.str("value") ?: return@mapNotNull null, item.str("name").orEmpty(), item.str("description"))
                },
            )
        }
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
