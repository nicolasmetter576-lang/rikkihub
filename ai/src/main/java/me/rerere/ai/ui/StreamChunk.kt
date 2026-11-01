package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.TokenUsage

/**
 * Provider-independent streaming events.
 *
 * Providers are responsible for translating their wire protocol into these events. Consumers can therefore process
 * text, reasoning, tool calls and images without knowing which provider produced them.
 */
@Serializable
sealed class StreamChunk {
    @Serializable
    @SerialName("text_start")
    data class TextStart(
        val id: String,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("text_delta")
    data class TextDelta(
        val id: String,
        val text: String,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("text_end")
    data class TextEnd(val id: String) : StreamChunk()

    @Serializable
    @SerialName("reasoning_start")
    data class ReasoningStart(
        val id: String,
        val metadata: JsonObject? = null,
        val reasoningType: ReasoningType = ReasoningType.REASONING_TEXT,
    ) : StreamChunk()

    @Serializable
    @SerialName("reasoning_delta")
    data class ReasoningDelta(
        val id: String,
        val text: String,
        val metadata: JsonObject? = null,
        val reasoningType: ReasoningType = ReasoningType.REASONING_TEXT,
    ) : StreamChunk()

    @Serializable
    @SerialName("reasoning_end")
    data class ReasoningEnd(
        val id: String,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    /**
     * 开始一个工具调用 part。
     *
     * [id] 是内部路由键（streamKey）：同一逻辑调用的 Start/Delta/End 共享，
     * 响应内稳定，与 wire 上的真实调用 id 无关。
     *
     * [toolCallId] 是对外展示/回传的调用 id：
     * - 非空（双 ID 路径）= decoder 明确区分了内部路由键与外部 id；
     *   handler 严格按 streamKey→part 定位，不回扫历史 `toolCallId`。
     *   未获得真实 id 时 provider 也必须填稳定非空的临时显示 id，迟到真实
     *   id 经 [ToolCallIdAssigned] 补绑。
     * - `null`（legacy 路径）= 旧式 provider，`id` 同时是对外 `toolCallId`，
     *   handler 保持原有兼容查找语义。
     */
    @Serializable
    @SerialName("tool_call_start")
    data class ToolCallStart(
        val id: String,
        val toolName: String = "",
        val metadata: JsonObject? = null,
        val toolCallId: String? = null,
    ) : StreamChunk()

    /**
     * 同一逻辑调用的真实 wire id 迟到补绑：把 streamKey 为 [id] 的进行中
     * part 的 `toolCallId` 就地更新为 [toolCallId]，不另起 part、不改路由。
     * 必须在对应 [ToolCallEnd] 之前到达；落在未知/已结束/已执行 part 上时
     * handler 抛协议错误而不是静默丢弃。
     */
    @Serializable
    @SerialName("tool_call_id_assigned")
    data class ToolCallIdAssigned(
        val id: String,
        val toolCallId: String,
    ) : StreamChunk()

    /**
     * 工具调用增量。[id] 是 streamKey（见 [ToolCallStart]）；[toolNameDelta]
     * 与 [inputDelta] 永远逐字追加，合法重复字符绝不去重。
     */
    @Serializable
    @SerialName("tool_call_delta")
    data class ToolCallDelta(
        val id: String,
        val toolNameDelta: String = "",
        val inputDelta: String = "",
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("tool_call_end")
    data class ToolCallEnd(val id: String) : StreamChunk()

    /**
     * 服务端已执行函数工具并随响应下发的结果（部分兼容网关会这样做）。[toolCallId] 是 wire 上的调用 id，
     * handler 把 [output] 挂到对应工具 part 上，使它算「已执行」，客户端不再重复执行。
     */
    @Serializable
    @SerialName("tool_call_output")
    data class ToolCallOutput(
        val toolCallId: String,
        val output: String,
    ) : StreamChunk()

    @Serializable
    @SerialName("server_tool_start")
    data class ServerToolStart(
        val id: String,
        val toolName: String,
        val input: JsonElement? = null,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("server_tool_input_delta")
    data class ServerToolInputDelta(
        val id: String,
        val inputDelta: String,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("server_tool_input_end")
    data class ServerToolInputEnd(val id: String) : StreamChunk()

    @Serializable
    @SerialName("server_tool_end")
    data class ServerToolEnd(
        val id: String,
        val input: JsonElement? = null,
        val output: JsonElement? = null,
        val status: ServerToolStatus,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("image_start")
    data class ImageStart(
        val id: String,
        val mimeType: String = "image/png",
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("image_delta")
    data class ImageDelta(
        val id: String,
        val data: String,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    /** A complete renderable image snapshot that replaces previously received data for the same id. */
    @Serializable
    @SerialName("image_snapshot")
    data class ImageSnapshot(
        val id: String,
        val data: String,
        val metadata: JsonObject? = null,
    ) : StreamChunk()

    @Serializable
    @SerialName("image_end")
    data class ImageEnd(val id: String) : StreamChunk()

    @Serializable
    @SerialName("annotations")
    data class Annotations(val annotations: List<UIMessageAnnotation>) : StreamChunk()

    @Serializable
    @SerialName("usage")
    data class Usage(val usage: TokenUsage) : StreamChunk()

    @Serializable
    @SerialName("finish")
    data class Finish(
        val finishReason: String? = null,
        val responseId: String? = null,
        val model: String? = null,
    ) : StreamChunk()
}
