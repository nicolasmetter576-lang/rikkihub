package me.rerere.ai.provider.providers.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.ByteBuffer
import kotlin.uuid.Uuid

/** Gemini 对缺签名的首个 functionCall 认的旁路签名（CPA `GeminiSkipThoughtSignatureValidator`）。 */
const val ANTIGRAVITY_SKIP_SIGNATURE = "skip_thought_signature_validator"

/** Claude 模型没给 maxTokens 时的输出上限（CPA 内置 Claude 模型 max_completion_tokens）。 */
internal const val ANTIGRAVITY_CLAUDE_DEFAULT_MAX_OUTPUT = 64_000
private const val CLAUDE_MIN_THINKING_BUDGET = 1_024
private const val CACHE_WARM_REPLAY_MAX_TOKENS = 1

private const val THOUGHT_SIGNATURE = "thoughtSignature"

/** 构建一次 Antigravity 请求需要的上下文（与账户无关的部分由调用方给出）。 */
data class AntigravityRequestContext(
    val modelId: String,
    val projectId: String,
    val tools: List<Tool> = emptyList(),
    val reasoningEnabled: Boolean = false,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    /** 会话身份（同会话稳定），用来派生 `request.sessionId`；null 时退回首条用户文本。 */
    val sessionSeed: String? = null,
    val requestIdFactory: () -> String = { Uuid.random().toString() },
    val nowMillis: () -> Long = System::currentTimeMillis,
)

/**
 * 历史消息预处理：思考签名只回放给产生它的同一模型族。
 *
 * [sourceModelIdOf] 返回 assistant 消息在本 provider 下的模型名；不是本 provider 产生的消息返回 null。
 * - 来源与目标不同族（或来源未知）：去掉所有 part 上的 thoughtSignature；目标是 Claude 时
 *   连思考 part 一起去掉（Claude 只接受自己签过的 thinking）。
 */
fun prepareAntigravityHistory(
    messages: List<UIMessage>,
    targetModelId: String,
    sourceModelIdOf: (UIMessage) -> String?,
): List<UIMessage> {
    val targetClaude = AntigravityModelFamily.isClaude(targetModelId)
    return messages.map { message ->
        if (message.role != MessageRole.ASSISTANT) return@map message
        val source = sourceModelIdOf(message)
        val sameFamily = source != null && AntigravityModelFamily.isClaude(source) == targetClaude
        if (sameFamily) return@map message
        message.copy(
            parts = message.parts
                .filterNot { targetClaude && it is UIMessagePart.Reasoning }
                .map { it.withoutThoughtSignature() }
        )
    }
}

private fun UIMessagePart.withoutThoughtSignature(): UIMessagePart {
    val meta = metadata ?: return this
    if (THOUGHT_SIGNATURE !in meta) return this
    val stripped = JsonObject(meta - THOUGHT_SIGNATURE).takeIf { it.isNotEmpty() }
    return when (this) {
        is UIMessagePart.Text -> copy(metadata = stripped)
        is UIMessagePart.Reasoning -> copy(metadata = stripped)
        is UIMessagePart.Tool -> copy(metadata = stripped)
        is UIMessagePart.Image -> copy(metadata = stripped)
        else -> this
    }
}

/**
 * 把 GoogleProvider 构建的 Gemini generateContent 请求体改写成 Antigravity 请求
 * （对应 CPA `geminiToAntigravity` + 执行器里的改写）。
 */
fun buildAntigravityRequest(geminiBody: JsonObject, context: AntigravityRequestContext): JsonObject {
    val modelId = context.modelId
    val claude = AntigravityModelFamily.isClaude(modelId)
    val image = AntigravityModelFamily.isImage(modelId)
    val inner = LinkedHashMap<String, JsonElement>(geminiBody)

    inner.remove("safetySettings")
    inner.remove("model")

    // 工具 schema：从原始 Tool 重新清理（GoogleProvider 已删掉 enum 等，CPA 要把它们写进描述）
    (inner["tools"] as? JsonArray)?.let { tools ->
        inner["tools"] = rewriteToolDeclarations(tools, context.tools, claude)
    }

    // 思考配置与输出上限
    val generationConfig = LinkedHashMap<String, JsonElement>((inner["generationConfig"] as? JsonObject).orEmpty())
    applyThinkingConfig(generationConfig, context, claude, image)
    // CPA 对非 Claude 模型删掉 maxOutputTokens；只有缓存保活重放（maxTokens=1，见 claudelife
    // `forCacheWarmReplay`）保留，否则重放会生成整段回答
    if (!claude && context.maxTokens != CACHE_WARM_REPLAY_MAX_TOKENS) generationConfig.remove("maxOutputTokens")
    if (generationConfig.isEmpty()) inner.remove("generationConfig") else inner["generationConfig"] = JsonObject(generationConfig)

    if (claude) {
        val toolConfig = LinkedHashMap<String, JsonElement>((inner["toolConfig"] as? JsonObject).orEmpty())
        val calling = LinkedHashMap<String, JsonElement>((toolConfig["functionCallingConfig"] as? JsonObject).orEmpty())
        calling["mode"] = JsonPrimitive("VALIDATED")
        toolConfig["functionCallingConfig"] = JsonObject(calling)
        inner["toolConfig"] = JsonObject(toolConfig)
    }

    // contents：签名、回合、首尾
    var contents = (inner["contents"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    contents = if (claude) sanitizeClaudeSignatures(contents) else sanitizeGeminiSignatures(contents)
    if (AntigravityModelFamily.isGeminiFamily(modelId)) contents = normalizeFunctionResponseTurns(contents)
    if (!claude) contents = ensureBoundaryUserContent(contents)
    inner["contents"] = JsonArray(contents)

    val requestType = if (image) "image_gen" else "agent"
    if (!image) inner["sessionId"] = JsonPrimitive(deriveAntigravitySessionId(context.sessionSeed, contents))

    return JsonObject(
        linkedMapOf(
            "model" to JsonPrimitive(modelId),
            "userAgent" to JsonPrimitive("antigravity"),
            "requestType" to JsonPrimitive(requestType),
            "project" to JsonPrimitive(context.projectId),
            "requestId" to JsonPrimitive(
                if (image) "image_gen/${context.nowMillis()}/${context.requestIdFactory()}/12"
                else "agent-${context.requestIdFactory()}"
            ),
            "request" to JsonObject(inner),
        )
    )
}

private fun rewriteToolDeclarations(tools: JsonArray, declared: List<Tool>, claude: Boolean): JsonArray {
    val byName = declared.associateBy { it.name }
    return JsonArray(tools.map { entry ->
        val tool = entry as? JsonObject ?: return@map entry
        val declarations = tool["functionDeclarations"] as? JsonArray ?: return@map entry
        val rewritten = declarations.map { declaration ->
            val decl = declaration as? JsonObject ?: return@map declaration
            val name = (decl["name"] as? JsonPrimitive)?.contentOrNull
            val original: JsonElement = byName[name]?.let { schemaOf(it) } ?: decl["parameters"] ?: JsonNull
            JsonObject(LinkedHashMap<String, JsonElement>(decl).apply {
                remove("parametersJsonSchema")
                put("parameters", AntigravitySchema.cleanToolSchema(original, claude))
            })
        }
        JsonObject(LinkedHashMap<String, JsonElement>(tool).apply { put("functionDeclarations", JsonArray(rewritten)) })
    })
}

/** 工具声明的原始 schema（未清理），供解码时剥离占位参数对照。 */
fun schemaOf(tool: Tool): JsonElement = tool.parameters()?.let { json.encodeToJsonElement(it) } ?: JsonNull

private fun applyThinkingConfig(
    config: LinkedHashMap<String, JsonElement>,
    context: AntigravityRequestContext,
    claude: Boolean,
    image: Boolean,
) {
    config.remove("thinkingConfig")
    if (!context.reasoningEnabled || image || AntigravityModelFamily.isGptOss(context.modelId)) return
    val level = context.reasoningLevel
    if (claude) {
        if (level == ReasoningLevel.OFF) return
        val maxOutput = (config["maxOutputTokens"] as? JsonPrimitive)?.intOrNull ?: context.maxTokens
            ?: ANTIGRAVITY_CLAUDE_DEFAULT_MAX_OUTPUT
        config["maxOutputTokens"] = JsonPrimitive(maxOutput)
        val budget = if (level == ReasoningLevel.AUTO) {
            -1
        } else {
            level.budgetTokens.coerceAtLeast(CLAUDE_MIN_THINKING_BUDGET).coerceAtMost(maxOutput - 1)
        }
        // 预算放不进输出上限（maxOutput ≤ 1024）就不开思考（CPA normalizeClaudeBudget）
        if (budget != -1 && budget < CLAUDE_MIN_THINKING_BUDGET) return
        config["thinkingConfig"] = JsonObject(
            mapOf("includeThoughts" to JsonPrimitive(true), "thinkingBudget" to JsonPrimitive(budget))
        )
        return
    }
    // AUTO 同 CPA ModeAuto：一律 thinkingBudget=-1（动态）；其余按档位给 thinkingLevel。
    // Gemini 3 关不掉思考，OFF 取最低的 low 并不回传思考内容。
    config["thinkingConfig"] = JsonObject(buildMap {
        put("includeThoughts", JsonPrimitive(level != ReasoningLevel.OFF))
        when (level) {
            ReasoningLevel.AUTO -> put("thinkingBudget", JsonPrimitive(-1))
            ReasoningLevel.OFF, ReasoningLevel.LOW -> put("thinkingLevel", JsonPrimitive("low"))
            ReasoningLevel.MEDIUM -> put("thinkingLevel", JsonPrimitive("medium"))
            ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MAX -> put("thinkingLevel", JsonPrimitive("high"))
        }
    })
}

// ---- 签名 ----

private fun JsonObject.role(): String = (this["role"] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.parts(): List<JsonObject> =
    (this["parts"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.withParts(parts: List<JsonObject>): JsonObject =
    JsonObject(LinkedHashMap<String, JsonElement>(this).apply { put("parts", JsonArray(parts)) })

private fun JsonObject.withoutSignature(): JsonObject =
    if (THOUGHT_SIGNATURE in this) JsonObject(this - THOUGHT_SIGNATURE) else this

private fun JsonObject.signature(): String? =
    (this[THOUGHT_SIGNATURE] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.isThought(): Boolean = (this["thought"] as? JsonPrimitive)?.booleanOrNull == true

/** Gemini 目标（CPA `SanitizeGeminiRequestThoughtSignatures`）。 */
internal fun sanitizeGeminiSignatures(contents: List<JsonObject>): List<JsonObject> = contents.map { content ->
    val modelTurn = content.role() == "model"
    var firstCallSeen = false
    content.withParts(content.parts().map { part ->
        when {
            "functionResponse" in part -> part.withoutSignature()
            !modelTurn -> part.withoutSignature()
            "functionCall" in part -> {
                val first = !firstCallSeen
                firstCallSeen = true
                if (first && part.signature() == null) {
                    JsonObject(LinkedHashMap<String, JsonElement>(part).apply {
                        put(THOUGHT_SIGNATURE, JsonPrimitive(ANTIGRAVITY_SKIP_SIGNATURE))
                    })
                } else if (!first && part.signature() == ANTIGRAVITY_SKIP_SIGNATURE) {
                    part.withoutSignature()
                } else {
                    part
                }
            }

            part.signature() == ANTIGRAVITY_SKIP_SIGNATURE -> part.withoutSignature()
            else -> part
        }
    })
}

/** Claude 目标（CPA `SanitizeAntigravityClaudeGeminiRequestSignatures`）。 */
internal fun sanitizeClaudeSignatures(contents: List<JsonObject>): List<JsonObject> = contents.mapNotNull { content ->
    val modelTurn = content.role() == "model"
    val parts = content.parts().mapNotNull { part ->
        when {
            "functionResponse" in part || !modelTurn -> part.withoutSignature()
            part.isThought() -> {
                val signature = part.signature()
                val text = (part["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (signature == null || signature == ANTIGRAVITY_SKIP_SIGNATURE || text.isBlank()) null else part
            }

            else -> part.withoutSignature()
        }
    }.filterNot { part ->
        // Claude 适配层拒绝空文本 part
        part.keys == setOf("text") && (part["text"] as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()
    }
    if (parts.isEmpty()) null else content.withParts(parts)
}

// ---- functionResponse 回合（Gemini 族） ----

/**
 * CPA `normalizeAntigravityGeminiFunctionResponseRoles`：缺名/`unknown` 的 functionResponse 按同 id
 * 的 functionCall 补名；只含 functionResponse 的回合按上一回合调用顺序排列并改为 `model` 角色。
 */
internal fun normalizeFunctionResponseTurns(contents: List<JsonObject>): List<JsonObject> {
    val callNames = HashMap<String, String>()
    contents.forEach { content ->
        content.parts().forEach { part ->
            val call = part["functionCall"] as? JsonObject ?: return@forEach
            val id = call.string("id").trim()
            val name = call.string("name").trim()
            if (id.isNotEmpty() && name.isNotEmpty() && name != "unknown") callNames[id] = name
        }
    }
    data class Ref(val id: String, val name: String)

    var pending: List<Ref>? = null
    return contents.map { original ->
        val parts = original.parts().map { part ->
            val response = part["functionResponse"] as? JsonObject ?: return@map part
            val id = response.string("id").trim()
            val name = response.string("name").trim()
            val real = callNames[id]
            if (id.isNotEmpty() && (name.isEmpty() || name == "unknown") && real != null) {
                JsonObject(LinkedHashMap<String, JsonElement>(part).apply {
                    put("functionResponse", JsonObject(LinkedHashMap<String, JsonElement>(response).apply {
                        put("name", JsonPrimitive(real))
                    }))
                })
            } else {
                part
            }
        }
        val content = original.withParts(parts)
        if (parts.isEmpty()) {
            pending = null
            return@map content
        }
        val calls = parts.mapNotNull { it["functionCall"] as? JsonObject }.map { Ref(it.string("id"), it.string("name")) }
        val responseParts = parts.filter { "functionResponse" in it }
        val otherParts = parts.filter { "functionCall" !in it && "functionResponse" !in it }
        if (calls.isNotEmpty() && responseParts.isEmpty()) {
            pending = calls
            return@map content
        }
        if (responseParts.isEmpty()) {
            if (otherParts.isNotEmpty()) pending = null
            return@map content
        }
        if (calls.isNotEmpty()) {
            pending = null
            return@map content
        }

        var result = content
        val waiting = pending
        if (!waiting.isNullOrEmpty()) {
            val responses = responseParts.map { (it["functionResponse"] as JsonObject).let { r -> Ref(r.string("id"), r.string("name")) } }
            val used = BooleanArray(responses.size)
            val ordered = mutableListOf<JsonObject>()
            waiting.forEach { call ->
                val index = responses.indices.firstOrNull { i ->
                    !used[i] && ((call.id.isNotEmpty() && responses[i].id == call.id) ||
                        (call.id.isEmpty() && call.name.isNotEmpty() && responses[i].name == call.name))
                }
                if (index != null) {
                    used[index] = true
                    ordered += responseParts[index]
                }
            }
            responses.indices.filterNot { used[it] }.forEach { ordered += responseParts[it] }
            result = result.withParts(ordered + otherParts)
        }
        pending = null
        if (otherParts.isEmpty() && result.role() != "model") {
            result = JsonObject(LinkedHashMap<String, JsonElement>(result).apply { put("role", JsonPrimitive("model")) })
        }
        result
    }
}

private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

// ---- 首尾回合（非 Claude） ----

private val EMPTY_USER_TURN = JsonObject(
    mapOf(
        "role" to JsonPrimitive("user"),
        "parts" to JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive(""))))),
    )
)

/** CPA `DropGeminiTrailingThoughtOnlyContent` + `EnsureGeminiBoundaryUserContent`。 */
internal fun ensureBoundaryUserContent(contents: List<JsonObject>): List<JsonObject> {
    var keep = contents.size
    while (keep > 1 && contents[keep - 1].isThoughtOnlyModelTurn()) keep--
    val out = contents.take(keep).toMutableList()
    if (out.isEmpty()) return out
    if (out.first().role() == "model") out.add(0, EMPTY_USER_TURN)
    val last = out.last()
    if (last.role() == "model") {
        val hasResponse = last.parts().any { "functionResponse" in it }
        val hasResponseMedia = last.parts().any { part ->
            ((part["functionResponse"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty().any { nested ->
                nested is JsonObject && ("inlineData" in nested || "fileData" in nested)
            }
        }
        if (!hasResponse || hasResponseMedia) out.add(EMPTY_USER_TURN)
    }
    return out
}

private fun JsonObject.isThoughtOnlyModelTurn(): Boolean {
    if (role() != "model") return false
    val parts = parts()
    return parts.isNotEmpty() && parts.all { it.isThought() && "functionCall" !in it && "functionResponse" !in it }
}

// ---- sessionId ----

/**
 * `-<正 int64>`：会话身份经 SHA-256 派生（CPA `DerivedAntigravitySessionID` 同形），同会话稳定；
 * 没有会话身份时用首条用户文本（CPA `generateStableSessionID`），再没有就随机。
 */
internal fun deriveAntigravitySessionId(seed: String?, contents: List<JsonObject>): String {
    val source = seed?.takeIf { it.isNotBlank() }?.let { "rikkihub:antigravity:session\u0000$it" }
        ?: contents.firstOrNull { it.role() == "user" && it.parts().firstOrNull()?.string("text").orEmpty().isNotEmpty() }
            ?.parts()?.first()?.string("text")
        ?: return "-" + (SecureRandom().nextLong() and Long.MAX_VALUE).toString()
    val digest = MessageDigest.getInstance("SHA-256").digest(source.encodeToByteArray())
    return "-" + (ByteBuffer.wrap(digest, 0, 8).long and Long.MAX_VALUE).toString()
}
