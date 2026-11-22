package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.UpstreamStreamRateLimitException
import me.rerere.ai.util.json
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import java.io.IOException
import kotlin.time.Clock

const val UNTERMINATED_STREAMED_ARGS_CODE = "google_unterminated_streamed_args"

internal class GoogleStreamDecoder(
    private val responseId: String,
    private val model: String,
    private val vertexAI: Boolean = false,
) : StreamChunkDecoder {
    private val usageNormalizer = GoogleUsageNormalizer()
    private val streamState = GoogleStreamState()
    private var finishReason: String? = null
    private var sawNonStopFinishReason = false

    /** The model's tool call was rejected by the server (MALFORMED_FUNCTION_CALL / UNEXPECTED_TOOL_CALL). */
    var toolCallRejected = false
        private set
    private var finished = false
    // 服务端工具（toolCall/toolResponse）省略 id 时的兜底序号；与 streamState 内
    // 客户端工具调用的 allocator 前缀不同，不会相互碰撞。
    private var serverToolSequence = 0

    override fun accept(event: SseEvent): DecodeResult {
        if (finished) return DecodeResult(completed = true)
        if (event.data.trim() == "[DONE]") return DecodeResult(finish(), completed = true)

        val jsonData = try {
            json.parseToJsonElement(event.data).jsonObject
        } catch (e: Throwable) {
            finished = true
            throw e
        }
        val hasError = if (vertexAI) {
            jsonData.containsKey("error")
        } else {
            jsonData["error"] != null && jsonData["error"] !is JsonNull
        }
        if (hasError) {
            finished = true
            throw upstreamStreamError(jsonData["error"], vertexAI)
        }
        val blockedReason = jsonData["promptFeedback"]?.jsonObjectOrNull
            ?.get("blockReason")?.jsonPrimitiveOrNull?.contentOrNull
        if (blockedReason != null) {
            // 失败路径不能经 finish() 把 pending 参数 flush 成成功
            finished = true
            error("Prompt feedback: $blockedReason")
        }

        val chunks = try {
            buildList<StreamChunk> {
                usageNormalizer.update(jsonData["usageMetadata"] as? JsonObject)?.let { add(StreamChunk.Usage(it)) }
                val candidates = jsonData["candidates"]
                if (vertexAI && candidates != null &&
                    (candidates !is JsonArray || candidates.size > 1)
                ) {
                    throw ToolStreamProtocolException(
                        "google_invalid_candidate", "Vertex streaming supports only candidate zero",
                    )
                }
                val candidate = if (vertexAI) {
                    candidates?.jsonArray?.firstOrNull()?.let {
                        if (it !is JsonObject) {
                            throw ToolStreamProtocolException(
                                "google_invalid_candidate", "Invalid Vertex stream candidate",
                            )
                        }
                        it
                    }
                } else {
                    candidates?.jsonArrayOrNull?.firstOrNull()?.jsonObjectOrNull
                } ?: return@buildList
                if (vertexAI) {
                    val index = candidate["index"]
                    if (index != null &&
                        (index !is JsonPrimitive || index.isString || index.intOrNull != 0)
                    ) {
                        throw ToolStreamProtocolException(
                            "google_invalid_candidate", "Vertex streaming supports only candidate zero",
                        )
                    }
                    val reason = candidate["finishReason"]
                    if (reason != null && reason != JsonNull) {
                        if (reason !is JsonPrimitive || !reason.isString) {
                            throw ToolStreamProtocolException(
                                "google_invalid_finish_reason", "Invalid Vertex stream finish reason",
                            )
                        }
                        if (reason.content.isNotBlank() && reason.content != "FINISH_REASON_UNSPECIFIED") {
                            finishReason = reason.content
                            if (reason.content != "STOP") sawNonStopFinishReason = true
                            if (reason.content == "MALFORMED_FUNCTION_CALL" || reason.content == "UNEXPECTED_TOOL_CALL") {
                                toolCallRejected = true
                                throw ToolStreamProtocolException(
                                    "google_incomplete_tool_call",
                                    "Vertex stream rejected a tool call (finishReason=${reason.content})",
                                )
                            }
                        }
                    }
                } else {
                    candidate["finishReason"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?.takeIf { it.isNotBlank() && it != "FINISH_REASON_UNSPECIFIED" }
                        ?.let { finishReason = it }
                }
                val content = (if (vertexAI) candidate["content"]?.jsonObject else candidate["content"]?.jsonObjectOrNull)
                    ?: return@buildList
                val message = parseMessage(content, candidate["groundingMetadata"] as? JsonObject)
                addAll(streamState.append(message, responseId))
            }
        } catch (e: Throwable) {
            // 失败路径不能经 finish() 把 pending 参数 flush 成成功
            finished = true
            throw e
        }
        return DecodeResult(chunks)
    }

    override fun onClosed(): List<StreamChunk> = finish()

    private fun finish(): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        if (finishReason == null) {
            // Seen in production when an upstream cuts a stream short. Tools only
            // run after a finish reason, so retrying as a network failure cannot repeat side effects.
            val provider = if (vertexAI) "Vertex" else "Google"
            throw IOException("$provider stream ended without a finish reason")
        }
        return streamState.finish(finishReason, responseId, model, vertexAI, sawNonStopFinishReason)
    }

    private fun parseMessage(content: JsonObject, groundingMetadata: JsonObject?): GoogleStreamMessage =
        GoogleStreamMessage(
            parts = content["parts"]?.jsonArray?.mapIndexed { index, part ->
                parsePart(part.jsonObject, index)
            }.orEmpty(),
            annotations = parseAnnotations(groundingMetadata),
        )

    private fun parsePart(part: JsonObject, index: Int): GoogleStreamPart = when {
        part.containsKey("text") -> {
            val text = part["text"]?.jsonPrimitive?.contentOrNull ?: ""
            val metadata = part.toGoogleThoughtMetadata()
            if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                GoogleStreamPart.Ui(UIMessagePart.Reasoning(text, Clock.System.now(), null, metadata))
            } else {
                GoogleStreamPart.Ui(UIMessagePart.Text(text, metadata))
            }
        }
        // Gemini 4 with built-in search streams opaque state as parts holding only a signature. Echoing them
        // alone is rejected ("Invalid thought signature"); folded into the thought part they are accepted.
        part.keys == setOf("thoughtSignature") ->
            GoogleStreamPart.Ui(UIMessagePart.Reasoning("", Clock.System.now(), null, part.toGoogleThoughtMetadata()))

        part.containsKey("functionCall") -> {
            val functionCall = part["functionCall"]!!.jsonObject
            // args 是完整 JsonElement 快照而不是增量片段，交给 streamState 按
            // 调用身份做 pending-replace，绝不做 JSON 字符串前缀差分。
            GoogleStreamPart.FunctionCall(
                callId = functionCall["id"]?.jsonPrimitive?.contentOrNull,
                name = functionCall["name"]?.jsonPrimitive?.contentOrNull ?: "",
                args = functionCall["args"],
                partialArgs = functionCall["partialArgs"] as? JsonArray,
                willContinue = (functionCall["willContinue"] as? JsonPrimitive)?.booleanOrNull == true,
                metadata = GoogleThoughtMetadata(
                    thoughtSignature = part["thoughtSignature"]?.jsonPrimitive?.contentOrNull,
                ).toMetadata(),
            )
        }

        part.containsKey("toolCall") -> {
            val toolCall = part["toolCall"]!!.jsonObject
            GoogleStreamPart.Ui(UIMessagePart.ServerTool(
                toolCallId = toolCall["id"]?.jsonPrimitive?.contentOrNull
                    ?: "$responseId:server-tool-${++serverToolSequence}",
                toolName = toolCall["toolType"]?.jsonPrimitive?.contentOrNull ?: "",
                input = toolCall["args"],
                status = ServerToolStatus.IN_PROGRESS,
                metadata = ServerToolMetadata(
                    protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
                    call = part,
                    callIndex = index,
                ).toMetadata(),
            ))
        }

        part.containsKey("toolResponse") -> {
            val toolResponse = part["toolResponse"]!!.jsonObject
            GoogleStreamPart.Ui(UIMessagePart.ServerTool(
                toolCallId = toolResponse["id"]?.jsonPrimitive?.contentOrNull
                    ?: "$responseId:server-tool-${++serverToolSequence}",
                toolName = toolResponse["toolType"]?.jsonPrimitive?.contentOrNull ?: "",
                output = toolResponse["response"],
                status = ServerToolStatus.COMPLETED,
                metadata = ServerToolMetadata(
                    protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
                    result = part,
                    resultIndex = index,
                ).toMetadata(),
            ))
        }
        part.containsKey("inlineData") -> {
            val inlineData = part["inlineData"]!!.jsonObject
            val mimeType = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/png"
            require(mimeType.startsWith("image/")) { "Only image mime type is supported" }
            if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                GoogleStreamPart.Ui(UIMessagePart.Reasoning("[Draft Image]\n", Clock.System.now(), null))
            } else {
                GoogleStreamPart.Ui(UIMessagePart.Image(
                    url = "data:$mimeType;base64,${inlineData["data"]?.jsonPrimitive?.contentOrNull ?: ""}",
                    metadata = part.toGoogleThoughtMetadata(),
                ))
            }
        }
        else -> error("unknown message part type: $part")
    }

    private fun parseAnnotations(metadata: JsonObject?): List<UIMessageAnnotation> =
        metadata?.get("groundingChunks")?.jsonArray.orEmpty().mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            val url = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = web["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            UIMessageAnnotation.UrlCitation(title, url)
        }

    /** 一个解析后的流式 message：客户端 functionCall 单独建模以保留 args 原始 JsonElement。 */
    private class GoogleStreamMessage(
        val parts: List<GoogleStreamPart>,
        val annotations: List<UIMessageAnnotation>,
    )

    private sealed interface GoogleStreamPart {
        data class Ui(val part: UIMessagePart) : GoogleStreamPart
        data class FunctionCall(
            /** wire id；absent 或 blank 时交由 allocator 统一分配合成 id */
            val callId: String?,
            val name: String,
            /** 完整参数快照（JsonElement），不是增量片段 */
            val args: JsonElement?,
            val metadata: JsonObject?,
            /** Vertex streamed arguments: `{jsonPath, stringValue|numberValue|boolValue|nullValue, willContinue}` pieces */
            val partialArgs: JsonArray? = null,
            val willContinue: Boolean = false,
        ) : GoogleStreamPart
    }

    private class GoogleStreamState {
        private var sequence = 0
        private var textId: String? = null
        private var reasoningId: String? = null
        private var imageId: String? = null
        // 客户端 functionCall 账本：每调用一份 pending 快照，保留到响应结束。
        // 参数一律等 finish 时发射最终完整快照（已接受的 UI 取舍：参数显示推迟到 Finish）。
        private val pendingToolCalls = linkedMapOf<String, PendingToolCall>()
        // 本轮响应里已被占用的客户端工具 id（wire 显式 + 合成），allocator 必须跳过它们，
        // 避免合成 id 与后到 wire id 碰撞。streamState 不再是第二分配器：absent/blank 只在此处归一化。
        private val usedToolIds = mutableSetOf<String>()

        // allocator 分出的合成 id 单独登记 origin。合成 id 形如 "resp:tool-N"
        // 是确定性可猜测的，不能以「实际不可达」免责：显式 wire id 命中本集合
        // 意味着真实身份与合成身份碰撞，必须 fail-closed，绝不覆盖/合并 pending。
        private val syntheticToolIds = mutableSetOf<String>()

        fun append(message: GoogleStreamMessage, responseId: String): List<StreamChunk> = buildList {
            val imageCount = message.parts.count {
                it is GoogleStreamPart.Ui && it.part is UIMessagePart.Image
            }
            var emittedImages = 0
            message.parts.forEach { streamPart ->
                when (streamPart) {
                    is GoogleStreamPart.FunctionCall -> {
                        addAll(closeText()); addAll(closeReasoning()); addAll(closeImage())
                        addAll(acceptFunctionCall(streamPart, responseId))
                    }
                    is GoogleStreamPart.Ui -> when (val part = streamPart.part) {
                        is UIMessagePart.Text -> if (part.text.isNotEmpty()) {
                            addAll(closeReasoning()); addAll(closeImage())
                            val id = textId ?: nextId(responseId, "text").also {
                                textId = it; add(StreamChunk.TextStart(it, part.metadata))
                            }
                            add(StreamChunk.TextDelta(id, part.text, part.metadata))
                        }
                        is UIMessagePart.Reasoning -> if (part.reasoning.isNotEmpty() || part.metadata != null) {
                            addAll(closeText()); addAll(closeImage())
                            val id = reasoningId ?: nextId(responseId, "reasoning").also {
                                reasoningId = it; add(StreamChunk.ReasoningStart(it, part.metadata))
                            }
                            add(StreamChunk.ReasoningDelta(id, part.reasoning, part.metadata))
                        }
                        is UIMessagePart.ServerTool -> {
                            // 服务端工具不关闭客户端 pending 调用：两者协议上可交错，
                            // 提前 ToolCallEnd 会丢掉尚未发射的 args 快照。
                            addAll(closeText()); addAll(closeReasoning()); addAll(closeImage())
                            if (part.status == ServerToolStatus.IN_PROGRESS) {
                                add(
                                    StreamChunk.ServerToolStart(
                                        id = part.toolCallId,
                                        toolName = part.toolName,
                                        input = part.input,
                                        metadata = part.metadata,
                                    )
                                )
                            } else {
                                add(
                                    StreamChunk.ServerToolEnd(
                                        id = part.toolCallId,
                                        input = part.input,
                                        output = part.output,
                                        status = part.status,
                                        metadata = part.metadata,
                                    )
                                )
                            }
                        }
                        is UIMessagePart.Image -> {
                            addAll(closeText()); addAll(closeReasoning())
                            if (imageCount > 1 && emittedImages > 0) addAll(closeImage())
                            val id = imageId ?: nextId(responseId, "image").also {
                                imageId = it
                                add(StreamChunk.ImageStart(
                                    id = it,
                                    mimeType = part.url.substringAfter("data:").substringBefore(";base64,"),
                                    metadata = part.metadata,
                                ))
                            }
                            add(StreamChunk.ImageDelta(
                                id,
                                part.url.substringAfter(";base64,", part.url),
                                part.metadata,
                            ))
                            emittedImages++
                        }
                        else -> Unit
                    }
                }
            }
            if (message.annotations.isNotEmpty()) add(StreamChunk.Annotations(message.annotations))
        }

        // Vertex streamed-argument calls (`streamFunctionCallArguments`): the first part carries
        // name, id and signature with willContinue, nameless parts then carry partialArgs, and an
        // empty functionCall closes the call. Arguments go out as an append-only JSON prefix;
        // ToolCallEnd still waits for terminal validation in finish().
        private val streamedToolCalls = linkedMapOf<String, StreamedToolCall>()
        private var openStreamedCall: StreamedToolCall? = null

        private fun acceptFunctionCall(
            call: GoogleStreamPart.FunctionCall,
            responseId: String,
        ): List<StreamChunk> = buildList {
            val open = openStreamedCall
            when {
                open != null -> {
                    if (call.name.isNotEmpty() || call.callId != null) {
                        throw ToolStreamProtocolException(
                            "google_interleaved_tool_call", "A functionCall started before the streamed call ended",
                        )
                    }
                    call.partialArgs?.let { addInputDelta(open.id, open.append(it)) }
                    if (!call.willContinue) {
                        addInputDelta(open.id, open.end())
                        openStreamedCall = null
                    }
                }
                call.willContinue && call.name.isNotEmpty() -> {
                    val streamed = StreamedToolCall(assignToolId(call.callId, responseId))
                    streamedToolCalls[streamed.id] = streamed
                    openStreamedCall = streamed
                    add(StreamChunk.ToolCallStart(streamed.id, call.name, call.metadata))
                    call.partialArgs?.let { addInputDelta(streamed.id, streamed.append(it)) }
                }
                call.partialArgs != null ->
                    throw ToolStreamProtocolException(
                        "google_orphan_partial_args", "Streamed functionCall arguments arrived without an open call",
                    )
                else -> addAll(recordFunctionCall(call, responseId))
            }
        }

        private fun MutableList<StreamChunk>.addInputDelta(id: String, delta: String) {
            if (delta.isNotEmpty()) add(StreamChunk.ToolCallDelta(id, inputDelta = delta))
        }

        /**
         * 登记一次 functionCall part。调用身份唯一归一化点：wire id 空/缺失时由
         * allocator 分配合成 id，同一非空 id 的后续 part 一律合并到同一调用：
         * name 冲突（两边都非空且不同）是协议错误；args 是完整快照，重复同值
         * no-op、不同值整体替换 pending（不做字符串前缀差分、不拼接）。
         * 无 id 的 part 不做消重：每次出现都是一次独立调用。
         */
        private fun recordFunctionCall(
            call: GoogleStreamPart.FunctionCall,
            responseId: String,
        ): List<StreamChunk> = buildList {
            val callId = assignToolId(call.callId, responseId)
            val pending = pendingToolCalls[callId]
            if (pending == null) {
                pendingToolCalls[callId] = PendingToolCall(
                    name = call.name,
                    args = call.args,
                    metadata = call.metadata,
                )
                add(StreamChunk.ToolCallStart(callId, call.name, call.metadata))
            } else {
                if (pending.name.isNotEmpty() && call.name.isNotEmpty() && pending.name != call.name) {
                    throw ToolStreamProtocolException(
                        code = "conflicting_tool_name",
                        message = "functionCall id '$callId' re-sent with a different name: " +
                            "'${pending.name}' -> '${call.name}'",
                    )
                }
                if (pending.name.isEmpty() && call.name.isNotEmpty()) {
                    pending.name = call.name
                    add(StreamChunk.ToolCallDelta(callId, toolNameDelta = call.name))
                }
                pending.args = call.args
                pending.metadata = call.metadata ?: pending.metadata
            }
        }

        private fun assignToolId(callId: String?, responseId: String): String {
            val wireId = callId?.takeUnless { it.isBlank() }
            // 显式 wire id 命中 allocator 分出的合成身份：这是 identity collision
            // （Google 无稳定 index，不能按 index 晚到绑定处理），拒绝而非合并。
            if (wireId != null && wireId in syntheticToolIds) {
                throw ToolStreamProtocolException(
                    code = "tool_call_identity_collision",
                    message = "explicit functionCall id '$wireId' collides with a " +
                        "decoder-assigned synthetic id; refusing to merge or overwrite",
                )
            }
            return (wireId ?: allocateToolId(responseId)).also { usedToolIds.add(it) }
        }

        fun finish(
            reason: String?,
            responseId: String,
            model: String,
            vertexAI: Boolean,
            sawNonStopFinishReason: Boolean,
        ): List<StreamChunk> = buildList {
            val hasToolCalls = pendingToolCalls.isNotEmpty() || streamedToolCalls.isNotEmpty()
            if (vertexAI && hasToolCalls && (reason != "STOP" || sawNonStopFinishReason)) {
                throw ToolStreamProtocolException(
                    "google_incomplete_tool_call",
                    "Vertex stream ended with an incomplete tool call (finishReason=$reason)",
                )
            }
            if (streamedToolCalls.values.any { !it.ended }) {
                throw ToolStreamProtocolException(
                    UNTERMINATED_STREAMED_ARGS_CODE,
                    "Stream ended inside streamed tool arguments (finishReason=$reason)",
                )
            }
            addAll(closeText()); addAll(closeReasoning()); addAll(closeImage())
            streamedToolCalls.keys.forEach { add(StreamChunk.ToolCallEnd(it)) }
            streamedToolCalls.clear()
            // 正常结束时一次性发射每个调用的最终完整 args 快照并 End。
            // 终止校验必须先于 flush；失败后的重复关闭由 decoder 拦截。
            pendingToolCalls.forEach { (callId, pending) ->
                val input = pending.args?.let(json::encodeToString).orEmpty()
                if (input.isNotEmpty()) {
                    add(StreamChunk.ToolCallDelta(callId, inputDelta = input, metadata = pending.metadata))
                }
                add(StreamChunk.ToolCallEnd(callId))
            }
            pendingToolCalls.clear()
            add(StreamChunk.Finish(reason, responseId, model))
        }

        private fun closeText() = textId?.let { textId = null; listOf(StreamChunk.TextEnd(it)) }.orEmpty()
        private fun closeReasoning() = reasoningId?.let {
            reasoningId = null; listOf(StreamChunk.ReasoningEnd(it))
        }.orEmpty()
        private fun closeImage() = imageId?.let { imageId = null; listOf(StreamChunk.ImageEnd(it)) }.orEmpty()
        private fun allocateToolId(responseId: String): String {
            var id: String
            do {
                id = "$responseId:tool-${++sequence}"
            } while (!usedToolIds.add(id))
            syntheticToolIds += id
            return id
        }
        private fun nextId(responseId: String, kind: String): String =
            "$responseId:$kind-${++sequence}"
    }

    private class PendingToolCall(
        var name: String,
        var args: JsonElement?,
        var metadata: JsonObject?,
    )

    /**
     * Turns `partialArgs` pieces into an append-only JSON object prefix: each call returns the
     * text to append to the tool input, so the input stays a parseable prefix while streaming
     * and becomes the complete arguments at [end]. Only top-level fields are supported.
     */
    private class StreamedToolCall(val id: String) {
        private val keys = mutableSetOf<String>()
        private var openKey: String? = null
        private var started = false
        var ended = false
            private set

        fun append(pieces: JsonArray): String = buildString {
            for (element in pieces) {
                val piece = element as? JsonObject ?: throw unsupportedPartialArgs()
                val key = (piece["jsonPath"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.let { STREAMED_ARGUMENT_PATH.matchEntire(it)?.groupValues?.get(1) }
                    ?: throw unsupportedPartialArgs()
                val text = (piece["stringValue"] as? JsonPrimitive)?.takeIf { it.isString }
                if (text != null) {
                    if (openKey != key) {
                        closeString()
                        startValue(key)
                        append('"')
                        openKey = key
                    }
                    append(json.encodeToString(text.content).let { it.substring(1, it.length - 1) })
                    if ((piece["willContinue"] as? JsonPrimitive)?.booleanOrNull != true) closeString()
                } else {
                    val literal = when {
                        piece.containsKey("nullValue") -> "null"
                        else -> listOf("numberValue", "boolValue").firstNotNullOfOrNull { field ->
                            (piece[field] as? JsonPrimitive)?.takeUnless { it.isString }?.toString()
                        } ?: throw unsupportedPartialArgs()
                    }
                    closeString()
                    startValue(key)
                    append(literal)
                }
            }
        }

        fun end(): String = buildString {
            closeString()
            if (!started) append('{')
            append('}')
            ended = true
        }

        private fun StringBuilder.closeString() {
            if (openKey != null) {
                append('"')
                openKey = null
            }
        }

        private fun StringBuilder.startValue(key: String) {
            if (!keys.add(key)) {
                throw ToolStreamProtocolException("google_duplicate_partial_args", "Streamed argument field repeated")
            }
            append(if (started) ',' else '{')
            started = true
            append(json.encodeToString(key)).append(':')
        }

        private fun unsupportedPartialArgs() = ToolStreamProtocolException(
            "google_unsupported_partial_args", "Unsupported streamed functionCall argument (only top-level fields)",
        )
    }

    private fun JsonObject.toGoogleThoughtMetadata() =
        this["thoughtSignature"]?.jsonPrimitive?.contentOrNull?.let {
            GoogleThoughtMetadata(thoughtSignature = it).toMetadata()
        }
}

private val STREAMED_ARGUMENT_PATH = Regex("""\$\.([A-Za-z_][A-Za-z0-9_]*)""")

private val TRANSIENT_UPSTREAM_CODES = setOf(429, 500, 502, 503, 504)
private val TRANSIENT_UPSTREAM_STATUSES = setOf("RESOURCE_EXHAUSTED", "UNAVAILABLE", "INTERNAL", "DEADLINE_EXCEEDED")
private val UPSTREAM_STATUS_NAME = Regex("[A-Z_]{1,40}")

/**
 * A Vertex in-stream `error` frame. Overload, quota and timeout errors become [IOException]
 * so the generation loop's network retry restarts the attempt (tools only run after a
 * successful stream); anything else fails the attempt as a protocol error. Only the numeric
 * code and the enum status are kept: the upstream message may echo request content.
 */
private fun upstreamStreamError(error: JsonElement?, vertexAI: Boolean): Exception {
    val provider = if (vertexAI) "Vertex" else "Google"
    val fields = error as? JsonObject
    val code = (fields?.get("code") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    val status = (fields?.get("status") as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { UPSTREAM_STATUS_NAME.matches(it) }
    val label = listOfNotNull(code?.toString(), status).joinToString(" ").ifEmpty { "no code" }
    return when {
        code == 429 || code == 503 || status == "RESOURCE_EXHAUSTED" || status == "UNAVAILABLE" ->
            UpstreamStreamRateLimitException(
                "$provider stream error ($label)",
                statusCode = code ?: if (status == "RESOURCE_EXHAUSTED") 429 else 503,
            )
        code in TRANSIENT_UPSTREAM_CODES || status in TRANSIENT_UPSTREAM_STATUSES ->
            IOException("$provider stream error ($label)")
        else ->
            ToolStreamProtocolException("google_stream_upstream_error", "$provider reported a stream error ($label)")
    }
}
