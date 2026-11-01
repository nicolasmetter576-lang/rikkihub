package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.provider.stream.ToolCallLedger
import me.rerere.ai.provider.stream.ToolNameResolver
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.OpenRouterReasoningMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import me.rerere.ai.util.parseErrorDetail
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import kotlin.time.Clock

/**
 * Chat Completions SSE 解码器。
 *
 * [knownToolNames] 传入本轮请求实际下发（preflight 后最终 wire 表）的
 * 工具名集合，供 [ToolNameResolver] 消解 `function.name` wire 形态歧义
 * （首发/完整重发/累计快照/真增量分片共存），规则见该类文档。候选全灭的
 * 未注册名（幻觉/沿用旧工具名）按逐字面量透传给执行端报 not found；终态
 * 多候选（真歧义，如 {foo,foofoo} 下 foo→foo）会抛
 * [me.rerere.ai.provider.stream.ToolStreamProtocolException] 终止本 attempt，
 * 绝不猜短名交给执行端。
 *
 * 调用身份：wire 的 `index`/`id` 只用于路由到 [ToolCallLedger] 里的逻辑
 * 调用；对外发射的 StreamChunk 一律用响应内稳定的 streamKey 作 `id`，
 * `ToolCallStart.toolCallId` 显式填真实 wire id 或稳定 synthetic 显示 id
 * （双 ID 路径），迟到真实 id 经 `ToolCallIdAssigned` 在 End 前补绑——
 * 绝不让空/缺失 id 改变内部身份或污染 index 映射。工具调用只在流收尾
 * （Finish）时发 `ToolCallEnd`：text/reasoning/image 交错仅切换显示分段，
 * 不拆调用。
 */
internal class ChatCompletionsStreamDecoder(
    private val knownToolNames: Set<String> = emptySet(),
) : StreamChunkDecoder {
    private val streamState = ChatCompletionsStreamState()
    private val toolLedger = ToolCallLedger()
    // 每个逻辑调用的流式名称解析器（按 streamKey 索引；只在出现工具调用
    // 时才实例化 resolver，纯文本响应不触碰注册表）。
    private val nameResolvers = mutableMapOf<String, ToolNameResolver>()
    private val reasoningDetailsByIndex = linkedMapOf<Int, ReasoningDetailAccumulator>()
    private var responseId: String? = null
    private var responseModel: String? = null
    private var finishReason: String? = null
    private var finished = false

    override fun accept(event: SseEvent): DecodeResult {
        if (finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") return DecodeResult(finish(), completed = true)

        val chunks = buildList {
            event.data.trim().split("\n").filter(String::isNotBlank).forEach { line ->
                // 部分 OpenAI 兼容中转会在流里夹带 `data: null` 之类的非对象行，跳过
                val payload = json.parseToJsonElement(line).jsonObjectOrNull ?: return@forEach
                payload["error"]?.let { throw it.parseErrorDetail() }
                responseId = payload["id"]?.jsonPrimitive?.contentOrNull ?: responseId
                responseModel = payload["model"]?.jsonPrimitive?.contentOrNull ?: responseModel

                payload["choices"]?.jsonArrayOrNull?.firstOrNull()?.jsonObjectOrNull?.let { choice ->
                    (choice["delta"]?.jsonObjectOrNull ?: choice["message"]?.jsonObjectOrNull)?.let { message ->
                        val messageWithoutTools = JsonObject(message.filterKeys { it != "tool_calls" })
                        addAll(streamState.append(parseMessage(messageWithoutTools), responseId))

                        message["tool_calls"]?.jsonArrayOrNull?.let { toolCalls ->
                            // 数组内局部位置不构成稳定调用身份：显式 index 才作
                            // 路由依据；同包内多个无身份块按协议不可判定拒绝
                            // （账本规则 3），绝不做旧实现的 fallbackIndex 猜测。
                            val elements = toolCalls.mapNotNull { it.jsonObjectOrNull }
                            val anonymousCount = elements.count { element ->
                                element["index"]?.jsonPrimitive?.intOrNull == null &&
                                    element["id"]?.jsonPrimitive?.contentOrNull
                                        ?.takeUnless { it.isBlank() } == null
                            }
                            elements.forEach { toolCall ->
                                addAll(routeToolCall(toolCall, anonymousCount))
                            }
                        }
                    }
                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
                }
                parseUsage(payload["usage"] as? JsonObject)?.let { add(StreamChunk.Usage(it)) }
            }
        }
        return DecodeResult(chunks)
    }

    override fun onClosed(): List<StreamChunk> = finish()

    /**
     * 把一个 wire tool_call 块路由到逻辑调用并发射对应流事件。
     * 路由语义集中在 [ToolCallLedger]；这里只负责事件次序：
     * 新建调用 → Start（toolCallId 显式填当前 displayId）；
     * 迟到真实 id → Assigned 先于本帧 Delta；随后发名称增量/参数 Delta。
     */
    private fun routeToolCall(toolCall: JsonObject, anonymousCount: Int): List<StreamChunk> {
        val index = toolCall["index"]?.jsonPrimitive?.intOrNull
        // 空串/空白 id 必须按字段缺失处理：部分实现在后续 chunk 写 "id":""，
        // 若收进账本会把真实 id 的绑定冲掉，名称与 part 身份脱钩翻倍。
        val wireCallId = toolCall["id"]?.jsonPrimitive?.contentOrNull
            ?.takeUnless { it.isBlank() }
        val route = toolLedger.route(
            index = index,
            wireCallId = wireCallId,
            anonymousInPacket = anonymousCount,
            syntheticId = syntheticDisplayId(index),
        )
        val call = route.call
        val function = toolCall["function"]?.jsonObjectOrNull
        val resolver = nameResolvers.getOrPut(call.streamKey) {
            ToolNameResolver(knownToolNames)
        }
        val nameIncrement = resolver.accept(
            function?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
        )
        val input = function?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""

        return buildList {
            addAll(streamState.interruptForTool())
            if (!call.started) {
                call.started = true
                add(
                    StreamChunk.ToolCallStart(
                        id = call.streamKey,
                        toolName = nameIncrement,
                        toolCallId = call.displayId,
                    )
                )
                if (input.isNotEmpty()) {
                    add(StreamChunk.ToolCallDelta(id = call.streamKey, inputDelta = input))
                }
            } else {
                route.assignedId?.let { realId ->
                    add(StreamChunk.ToolCallIdAssigned(id = call.streamKey, toolCallId = realId))
                }
                if (nameIncrement.isNotEmpty() || input.isNotEmpty()) {
                    add(
                        StreamChunk.ToolCallDelta(
                            id = call.streamKey,
                            toolNameDelta = nameIncrement,
                            inputDelta = input,
                        )
                    )
                }
            }
        }
    }

    /**
     * wire id 缺失时的临时显示身份：响应内稳定非空（Start.toolCallId 必须
     * 显式填值供 handler 识别双 ID 路径），真实 id 到达后经 Assigned 替换。
     */
    private fun syntheticDisplayId(index: Int?): String =
        "${responseId ?: "response"}:tool-${index ?: toolLedger.all().size}"

    private fun finish(): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        toolLedger.markClosed()
        return buildList {
            // 工具调用生命周期与显示分段解耦：只在逻辑 Finish 才统一收尾
            // —— 先 flush 名称尾部确认增量再发 End。resolver 的终态多候选歧义/
            // 字面量兜底不可判定在此抛 ToolStreamProtocolException，fail-closed；
            // 未注册名不再抛错，透传剩余字面量交给执行端报 not found。
            toolLedger.all().forEach { call ->
                if (call.started && !call.ended) {
                    call.ended = true
                    val tail = nameResolvers[call.streamKey]?.finish().orEmpty()
                    if (tail.isNotEmpty()) {
                        add(StreamChunk.ToolCallDelta(id = call.streamKey, toolNameDelta = tail))
                    }
                    add(StreamChunk.ToolCallEnd(call.streamKey))
                }
            }
            addAll(streamState.finish(finishReason, responseId, responseModel))
        }
    }

    private fun parseMessage(payload: JsonObject): UIMessage {
        val role = MessageRole.valueOf(
            payload["role"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "ASSISTANT"
        )
        val content = payload["content"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        val reasoning = payload["reasoning_content"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: payload["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: payload["content"]?.takeIf { it is JsonArray }?.let { array ->
                array.jsonArrayOrNull?.getOrNull(0)?.jsonObjectOrNull
                    ?.get("thinking")?.jsonArrayOrNull?.getOrNull(0)?.jsonObjectOrNull
                    ?.get("text")?.jsonPrimitiveOrNull?.contentOrNull
            }
        val reasoningMetadata = accumulateReasoningDetails(payload["reasoning_details"]?.jsonArrayOrNull)
        val images = payload["images"] as? JsonArray ?: JsonArray(emptyList())

        return UIMessage(
            role = role,
            parts = buildList {
                if (!reasoning.isNullOrEmpty() || reasoningMetadata != null) {
                    add(UIMessagePart.Reasoning(
                        reasoning = reasoning.orEmpty(),
                        createdAt = Clock.System.now(),
                        finishedAt = null,
                        metadata = reasoningMetadata,
                    ))
                }
                if (content.isNotEmpty()) add(UIMessagePart.Text(content))
                images.forEach { image ->
                    val imageObject = image.jsonObjectOrNull ?: return@forEach
                    if (imageObject["type"]?.jsonPrimitive?.contentOrNull != "image_url") return@forEach
                    val url = imageObject["image_url"]?.jsonObjectOrNull
                        ?.get("url")?.jsonPrimitive?.contentOrNull ?: return@forEach
                    require(url.startsWith("data:image")) { "Only data uri is supported" }
                    add(UIMessagePart.Image(url.substringAfter("data:image/png;base64,")))
                }
            },
            annotations = parseAnnotations(payload["annotations"]?.jsonArrayOrNull ?: JsonArray(emptyList())),
        )
    }

    private fun accumulateReasoningDetails(details: JsonArray?): JsonObject? {
        if (details == null) return null
        details.forEachIndexed { fallbackIndex, element ->
            val incoming = element.jsonObjectOrNull ?: return@forEachIndexed
            val index = incoming["index"]?.jsonPrimitive?.intOrNull ?: fallbackIndex
            reasoningDetailsByIndex.getOrPut(index) { ReasoningDetailAccumulator() }.merge(incoming)
        }
        // Only null elements so far: nothing to carry, and no empty reasoning block either
        if (reasoningDetailsByIndex.isEmpty()) return null
        return OpenRouterReasoningMetadata(
            reasoningDetails = JsonArray(
                reasoningDetailsByIndex.toSortedMap().values.map { it.toJsonObject() }
            ),
        ).toMetadata()
    }

    /**
     * 每个 index 一份增量缓冲：delta 字段 (text/summary/data/signature) 用 StringBuilder 追加，
     * 其余字段保留最新 JsonElement，仅在发射 metadata 时物化为 JsonObject，
     * 避免每个 delta 都重建整份 JsonArray。
     */
    private class ReasoningDetailAccumulator {
        private val fixedFields = linkedMapOf<String, JsonElement>()
        private val deltaBuffers = linkedMapOf<String, StringBuilder>()

        fun merge(incoming: JsonObject) {
            incoming.forEach { (key, value) ->
                val delta = value.jsonPrimitiveOrNull?.contentOrNull
                if (key in REASONING_DETAIL_DELTA_FIELDS && delta != null) {
                    if (key in fixedFields) fixedFields.remove(key)
                    deltaBuffers.getOrPut(key) { StringBuilder() }.append(delta)
                } else {
                    deltaBuffers.remove(key)
                    fixedFields[key] = value
                }
            }
        }

        fun toJsonObject(): JsonObject = buildJsonObject {
            fixedFields.forEach { (key, value) -> put(key, value) }
            deltaBuffers.forEach { (key, value) -> put(key, value.toString()) }
        }
    }

    private fun parseAnnotations(array: JsonArray): List<UIMessageAnnotation> = array.mapNotNull { element ->
        val annotation = element.jsonObjectOrNull ?: return@mapNotNull null
        val type = annotation["type"]?.jsonPrimitive?.contentOrNull ?: error("type is null")
        when (type) {
            // A citation without its url_citation body carries nothing to show
            "url_citation" -> annotation["url_citation"]?.jsonObjectOrNull?.let { citation ->
                UIMessageAnnotation.UrlCitation(
                    title = citation["title"]?.jsonPrimitive?.contentOrNull ?: "",
                    url = citation["url"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }
            else -> error("unknown annotation type: $type")
        }
    }

    private fun parseUsage(usage: JsonObject?): TokenUsage? {
        if (usage == null) return null
        return TokenUsage(
            promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = usage["prompt_tokens_details"]?.jsonObjectOrNull
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: usage["cached_tokens"]?.jsonPrimitive?.intOrNull
                ?: usage["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
                ?: 0,
        )
    }

    /**
     * 显示分段（text/reasoning/image）状态机：只负责展示边界的开合，
     * 不持有工具调用身份（工具账本在 [ToolCallLedger]）。工具块到来只
     * 需要 [interruptForTool] 关闭进行中的显示分段，工具调用本身保持
     * 开放到流收尾——text/reasoning/image 交错绝不拆调用。
     */
    private class ChatCompletionsStreamState {
        private var sequence = 0
        private var textId: String? = null
        private var reasoningId: String? = null
        private var imageId: String? = null

        fun append(message: UIMessage, sourceId: String?): List<StreamChunk> = buildList {
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> if (part.text.isNotEmpty()) {
                        addAll(closeReasoning()); addAll(closeImage())
                        val id = textId ?: nextId(sourceId, "text").also {
                            textId = it; add(StreamChunk.TextStart(it))
                        }
                        add(StreamChunk.TextDelta(id, part.text))
                    }
                    is UIMessagePart.Reasoning -> if (part.reasoning.isNotEmpty() || part.metadata != null) {
                        addAll(closeText()); addAll(closeImage())
                        val id = reasoningId ?: nextId(sourceId, "reasoning").also {
                            reasoningId = it; add(StreamChunk.ReasoningStart(it, part.metadata))
                        }
                        add(StreamChunk.ReasoningDelta(id, part.reasoning, part.metadata))
                    }
                    is UIMessagePart.Image -> {
                        addAll(closeText()); addAll(closeReasoning())
                        val id = imageId ?: nextId(sourceId, "image").also {
                            imageId = it; add(StreamChunk.ImageStart(it, metadata = part.metadata))
                        }
                        add(StreamChunk.ImageDelta(id, part.url.substringAfter(";base64,", part.url), part.metadata))
                    }
                    // 工具调用不再经本状态机：外层按 ToolCallLedger 路由后
                    // 直接发射 Start/Assigned/Delta，显示分段由 interruptForTool
                    // 维护。理论上来不到这里（外层已剥掉 tool_calls）。
                    else -> Unit
                }
            }
            if (message.annotations.isNotEmpty()) add(StreamChunk.Annotations(message.annotations))
        }

        /** 工具块到来前关闭进行中的显示分段（文本→推理→图）。 */
        fun interruptForTool(): List<StreamChunk> = buildList {
            addAll(closeText()); addAll(closeReasoning()); addAll(closeImage())
        }

        fun finish(reason: String?, responseId: String?, model: String?): List<StreamChunk> = buildList {
            addAll(closeText()); addAll(closeReasoning()); addAll(closeImage())
            add(StreamChunk.Finish(reason, responseId, model))
        }

        private fun closeText() = textId?.let { textId = null; listOf(StreamChunk.TextEnd(it)) }.orEmpty()
        private fun closeReasoning() = reasoningId?.let {
            reasoningId = null; listOf(StreamChunk.ReasoningEnd(it))
        }.orEmpty()
        private fun closeImage() = imageId?.let { imageId = null; listOf(StreamChunk.ImageEnd(it)) }.orEmpty()
        private fun nextId(sourceId: String?, kind: String): String =
            "${sourceId?.takeIf(String::isNotBlank)?.let { "$it:" }.orEmpty()}$kind-${++sequence}"
    }

    private companion object {
        val REASONING_DETAIL_DELTA_FIELDS = setOf("text", "summary", "data", "signature")
    }
}
