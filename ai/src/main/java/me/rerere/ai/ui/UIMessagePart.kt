package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.util.json
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
sealed class ToolApprovalState {
    @Serializable
    @SerialName("auto")
    data object Auto : ToolApprovalState()

    @Serializable
    @SerialName("pending")
    data object Pending : ToolApprovalState()

    @Serializable
    @SerialName("approved")
    data object Approved : ToolApprovalState()

    @Serializable
    @SerialName("denied")
    data class Denied(val reason: String = "") : ToolApprovalState()

    @Serializable
    @SerialName("answered")
    data class Answered(val answer: String) : ToolApprovalState()
}

fun ToolApprovalState.canResumeToolExecution(): Boolean {
    return when (this) {
        ToolApprovalState.Approved -> true
        is ToolApprovalState.Denied -> true
        is ToolApprovalState.Answered -> true
        ToolApprovalState.Auto,
        ToolApprovalState.Pending,
            -> false
    }
}

/**
 * 服务端工具调用的通用生命周期状态。
 *
 * Provider 返回的更细粒度状态应保存在 [UIMessagePart.ServerTool.metadata] 中，避免在通用层绑定具体协议。
 */
@Serializable
enum class ServerToolStatus {
    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,
}

/** The kind of text carried by a reasoning part. */
@Serializable
enum class ReasoningType {
    @SerialName("reasoning_text")
    REASONING_TEXT,

    @SerialName("summary_text")
    SUMMARY_TEXT,
}

@Serializable
sealed class UIMessagePart {
    abstract val metadata: JsonObject?

    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("image")
    data class Image(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("video")
    data class Video(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("document")
    data class Document(
        val url: String,
        val fileName: String,
        val mime: String = "text/*",
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val reasoning: String,
        val createdAt: Instant = Clock.System.now(),
        val finishedAt: Instant? = Clock.System.now(),
        override var metadata: JsonObject? = null,
        val reasoningType: ReasoningType = ReasoningType.REASONING_TEXT,
    ) : UIMessagePart()

    @Deprecated("Deprecated")
    @Serializable
    @SerialName("search")
    data object Search : UIMessagePart() {
        override var metadata: JsonObject? = null
    }

    @Deprecated("Use UIMessagePart.Tool instead")
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val arguments: String,
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        fun merge(other: ToolCall): ToolCall {
            return ToolCall(
                toolCallId = toolCallId,
                toolName = toolName + other.toolName,
                arguments = arguments + other.arguments,
                approvalState = approvalState,
                metadata = if (other.metadata != null) other.metadata else metadata,
            )
        }
    }

    @Deprecated("Use UIMessagePart.Tool instead")
    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val content: JsonElement,
        val arguments: JsonElement,
        override var metadata: JsonObject? = null
    ) : UIMessagePart()

    /**
     * 由 Provider 在服务端执行的工具调用，例如网页搜索、文件检索或代码执行。
     *
     * 与 [Tool] 不同，此类型只追踪调用及结果，不参与客户端审批或执行流程。[input] 和 [output]
     * 使用通用 JSON，以容纳不同 Provider、不同工具的协议结构；需要原样回传的 Provider 数据也应保留在其中。
     */
    @Serializable
    @SerialName("server_tool")
    data class ServerTool(
        val toolCallId: String,
        val toolName: String,
        val input: JsonElement? = null,
        val output: JsonElement? = null,
        val status: ServerToolStatus,
        override var metadata: JsonObject? = null,
    ) : UIMessagePart() {
        val isFinished: Boolean
            get() = status == ServerToolStatus.COMPLETED || status == ServerToolStatus.FAILED
    }

    @Serializable
    @SerialName("tool")
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val input: String,
        val output: List<UIMessagePart> = emptyList(),
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
        override var metadata: JsonObject? = null,
        // 本地执行的起止时间，只统计真正运行工具的耗时；拒绝、问答和旧消息都为空
        val startedAt: Instant? = null,
        val finishedAt: Instant? = null,
    ) : UIMessagePart() {
        /** First tool call of a model response; see [TOOL_STEP_START_KEY]. */
        val startsStep: Boolean get() = metadata?.get(TOOL_STEP_START_KEY) == JsonPrimitive(true)

        /** Whether the tool has been executed (has output) */
        val isExecuted: Boolean get() = output.isNotEmpty()

        /** Whether the tool is pending user approval */
        val isPending: Boolean get() = !isExecuted && approvalState is ToolApprovalState.Pending

        /** Whether generation can resume and handle this tool immediately */
        val canResumeExecution: Boolean get() = !isExecuted && approvalState.canResumeToolExecution()

        /** Parse input string as JsonElement */
        fun inputAsJson(): JsonElement = runCatching {
            json.parseToJsonElement(input.ifBlank { "{}" })
        }.getOrElse { JsonObject(emptyMap()) }

        /**
         * Display-only parse of arguments that are still streaming (a JSON prefix): closes the open
         * string and containers and drops a dangling key, colon, comma, escape or literal. Never use it
         * to execute a tool; execution parses [input] strictly.
         */
        fun inputAsPartialJson(): JsonElement = runCatching {
            json.parseToJsonElement(closePartialJson(input.ifBlank { "{}" })) as? JsonObject
        }.getOrNull() ?: JsonObject(emptyMap())

        fun merge(other: Tool): Tool {
            return Tool(
                toolCallId = toolCallId,
                toolName = toolName + other.toolName,
                input = input + other.input,
                output = output + other.output,
                approvalState = approvalState,
                metadata = if (other.metadata != null) other.metadata else metadata,
                startedAt = startedAt,
                finishedAt = finishedAt,
            )
        }
    }
}

/**
 * Metadata key on the first tool call of each model response. Requests keep the calls of different
 * responses in separate assistant messages, exactly as they were sent while the turn was running;
 * without it two adjacent tool steps merge into one message and the already cached prefix changes.
 */
const val TOOL_STEP_START_KEY = "step_start"

fun UIMessagePart.Tool.markedAsStepStart(): UIMessagePart.Tool =
    copy(metadata = JsonObject(metadata.orEmpty() + (TOOL_STEP_START_KEY to JsonPrimitive(true))))

private val COMPLETE_JSON_LITERAL = Regex("""true|false|null|-?\d+(\.\d+)?([eE][+-]?\d+)?""")

/** Turns a JSON prefix into a document that parses; see [UIMessagePart.Tool.inputAsPartialJson]. */
internal fun closePartialJson(prefix: String): String {
    val closers = ArrayDeque<Char>()
    var inString = false
    var escaped = false
    var stringStart = 0
    var expectKey = false
    // Start of an object key whose value has not begun yet; such a key is dropped.
    var keyStart = -1
    var sawColon = false
    for ((i, c) in prefix.withIndex()) {
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        if (keyStart >= 0 && sawColon && !c.isWhitespace() && c != ':') keyStart = -1
        when (c) {
            '"' -> {
                inString = true
                stringStart = i
                if (expectKey) {
                    keyStart = i
                    sawColon = false
                }
                expectKey = false
            }
            '{' -> { closers.addLast('}'); expectKey = true }
            '[' -> { closers.addLast(']'); expectKey = false }
            '}', ']' -> { closers.removeLastOrNull(); expectKey = false; keyStart = -1 }
            ',' -> { expectKey = closers.lastOrNull() == '}'; keyStart = -1 }
            ':' -> if (keyStart >= 0) sawColon = true
        }
    }
    val out = StringBuilder(prefix)
    if (inString && keyStart != stringStart) {
        if (escaped) out.setLength(out.length - 1)
        val unicode = out.lastIndexOf("\\u")
        if (unicode >= 0 && out.length - unicode < 6 && out.substring(unicode + 2).all { it.isLetterOrDigit() }) {
            out.setLength(unicode)
        }
        out.append('"')
    } else if (keyStart >= 0) {
        out.setLength(keyStart)
    } else if (!inString) {
        val literal = out.takeLastWhile { it.isLetterOrDigit() || it == '.' || it == '-' || it == '+' }
        if (literal.isNotEmpty() && !COMPLETE_JSON_LITERAL.matches(literal)) out.setLength(out.length - literal.length)
    }
    while (out.isNotEmpty() && out.last().isWhitespace()) out.setLength(out.length - 1)
    if (out.lastOrNull() == ',') out.setLength(out.length - 1)
    if (out.lastOrNull() == ':') out.append("null")
    closers.reversed().forEach { out.append(it) }
    return out.toString()
}
