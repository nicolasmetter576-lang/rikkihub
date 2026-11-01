package me.rerere.ai.provider.providers.devin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.ui.DevinReasoningMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.util.json
import java.util.Base64
import kotlin.uuid.Uuid

// =============================================================================
// List<UIMessage> → (systemPrompt, List<DevinPrompt>) + List<DevinTool>
// =============================================================================

/** [buildDevinMessages] 的转换产物 */
data class DevinMessageBundle(
    val systemPrompt: String,
    val prompts: List<DevinPrompt>,
    val tools: List<DevinTool>,
)

/**
 * 把通用 UI 消息列表转换为 Devin 请求的 (systemPrompt, prompts, tools).
 *
 * 映射规则 (对照 CPA parseInteractionsPayload + rikkihub 各 provider 惯例):
 * - SYSTEM: 所有 Text part 合并为 systemPrompt
 * - USER: source=1; Text 按 "\n" 拼接; Image(data: URI) → f10
 * - ASSISTANT: 按 tool boundary 分组; 内容组 → source=2 (Text 拼接为 content,
 *   Reasoning 拼接为 thinking + DevinReasoningMetadata 签名); Tool part → f6 toolCall
 *   (未执行) 或 f6 + 紧随的 source=4 prompt (已执行, content=输出文本, toolCallId=f7)
 * - TOOL (legacy ToolResult part): source=4, toolCallId=f7
 * - messageId 用 UIMessage.id; 为空时编码层会兜底 UUID (与 Go 一致)
 *
 * [wireToolRenames] 原 wire 名 → 发出名：历史中记录的工具调用名 (f6) 与
 * tool result 的归属名必须和本轮声明名同源（上游对保留名做配置校验，
 * 历史里残留原名会再次触发拒绝）；[tools] 内的声明名预期已是发出名，
 * 过 map 是无害幂等。
 */
fun buildDevinMessages(
    messages: List<UIMessage>,
    tools: List<Tool>,
    wireToolRenames: Map<String, String> = emptyMap(),
): DevinMessageBundle {
    val systemPrompt = buildDevinSystemPrompt(messages)
    val prompts = mutableListOf<DevinPrompt>()

    for (message in messages) {
        when (message.role) {
            MessageRole.SYSTEM -> Unit // 已合并进 systemPrompt
            MessageRole.USER -> prompts.add(message.toUserPrompt())
            MessageRole.ASSISTANT -> prompts.addAll(message.toAssistantPrompts(wireToolRenames))
            MessageRole.TOOL -> prompts.addAll(message.toToolResultPrompts())
        }
    }

    return DevinMessageBundle(
        systemPrompt = systemPrompt,
        prompts = prompts,
        tools = tools.map { it.toDevinTool() },
    )
}

private fun buildDevinSystemPrompt(messages: List<UIMessage>): String {
    return messages
        .filter { it.role == MessageRole.SYSTEM }
        .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>().map { t -> t.text } }
        .joinToString("\n")
        .trim()
}

private fun UIMessage.toUserPrompt(): DevinPrompt {
    val textParts = mutableListOf<String>()
    val images = mutableListOf<DevinImage>()
    for (part in parts) {
        when (part) {
            is UIMessagePart.Text -> if (part.text.isNotEmpty()) textParts.add(part.text)
            is UIMessagePart.Image -> parseDevinImageDataUrl(part.url)?.let { images.add(it) }
            else -> Unit // 其余 part 类型 Devin 协议无对应表达, 跳过
        }
    }
    return DevinPrompt(
        messageId = id.toString(),
        source = 1,
        content = textParts.joinToString("\n"),
        images = images,
    )
}

private fun UIMessage.toAssistantPrompts(
    wireToolRenames: Map<String, String>,
): List<DevinPrompt> {
    fun newBuilder(source: Int) = DevinPromptBuilder(
        messageId = id.toString(),
        source = source,
        wireToolRenames = wireToolRenames,
    )

    val prompts = mutableListOf<DevinPrompt>()
    var current: DevinPromptBuilder? = null

    fun flush() {
        current?.let { prompts.add(it.build()) }
        current = null
    }

    for (group in groupPartsByToolBoundary(parts)) {
        when (group) {
            is PartGroup.Content -> {
                val builder = current ?: newBuilder(source = 2).also { current = it }
                builder.appendContent(group.parts)
            }

            is PartGroup.Tools -> {
                // 该组全部是已执行工具: 调用记录统一进当前 assistant prompt 的 f6,
                // 然后每个结果各发一个紧随的 source=4 prompt (与 Go function_result 序列一致)
                val builder = current ?: newBuilder(source = 2).also { current = it }
                for (tool in group.tools) {
                    builder.toolCalls.add(
                        DevinToolCall(
                            id = tool.toolCallId,
                            name = wireToolRenames[tool.toolName] ?: tool.toolName,
                            arguments = tool.input,
                        )
                    )
                }
                flush()
                for (tool in group.tools) {
                    prompts.add(
                        DevinPrompt(
                            messageId = Uuid.random().toString(),
                            source = 4,
                            content = tool.output.joinToString("\n") { it.asDevinText() },
                            toolCallId = tool.toolCallId,
                        )
                    )
                }
            }
        }
    }
    flush()
    return prompts
}

@Suppress("DEPRECATION")
private fun UIMessage.toToolResultPrompts(): List<DevinPrompt> {
    return parts.filterIsInstance<UIMessagePart.ToolResult>().map { result ->
        val content = when (val c = result.content) {
            is JsonPrimitive -> if (c.isString) c.content else c.toString()
            else -> json.encodeToString(c)
        }
        DevinPrompt(
            messageId = Uuid.random().toString(),
            source = 4,
            content = content,
            toolCallId = result.toolCallId,
        )
    }
}

/** 把一个 source=2 assistant prompt 的组成部件攒齐后编码 */
private class DevinPromptBuilder(
    val messageId: String,
    val source: Int,
    /** 原 wire 名 → 发出名；历史 f6 调用名与本轮声明同源 (见 buildDevinMessages) */
    val wireToolRenames: Map<String, String> = emptyMap(),
) {
    private val textParts = mutableListOf<String>()
    private val thinkingParts = mutableListOf<String>()
    private val images = mutableListOf<DevinImage>()
    val toolCalls = mutableListOf<DevinToolCall>()
    private var signature: ByteArray = ByteArray(0)
    private var signatureType = ""

    fun appendContent(parts: List<UIMessagePart>) {
        for (part in parts) {
            when (part) {
                is UIMessagePart.Text -> if (part.text.isNotEmpty()) textParts.add(part.text)
                is UIMessagePart.Reasoning -> {
                    if (part.reasoning.isNotEmpty()) thinkingParts.add(part.reasoning)
                    val meta = part.metadataAs<DevinReasoningMetadata>()
                    if (signature.isEmpty() && !meta?.signature.isNullOrEmpty()) {
                        signature = runCatching {
                            Base64.getDecoder().decode(meta!!.signature)
                        }.getOrDefault(ByteArray(0))
                        if (signature.isNotEmpty() && signatureType.isEmpty()) {
                            signatureType = meta!!.signatureType ?: ""
                        }
                    }
                }

                is UIMessagePart.Image -> parseDevinImageDataUrl(part.url)?.let { images.add(it) }
                is UIMessagePart.Tool -> {
                    // 未执行的 Tool part 按 spec 记进 f6
                    toolCalls.add(
                        DevinToolCall(
                            id = part.toolCallId,
                            name = wireToolRenames[part.toolName] ?: part.toolName,
                            arguments = part.input,
                        )
                    )
                }

                else -> Unit
            }
        }
    }

    fun build(): DevinPrompt = DevinPrompt(
        messageId = messageId,
        source = source,
        content = textParts.joinToString("\n"),
        images = images,
        toolCalls = toolCalls,
        thinking = thinkingParts.joinToString("\n\n"),
        signature = signature,
        signatureType = signatureType,
    )
}

/** 工具输出 part → 上送文本; 非文本 part 取其可表达文本, 其余为空串 */
private fun UIMessagePart.asDevinText(): String = when (this) {
    is UIMessagePart.Text -> text
    is UIMessagePart.Reasoning -> reasoning
    else -> ""
}

/** core Tool → DevinTool; parameters 为 JSON schema 字节, 无 schema 时为空 */
internal fun Tool.toDevinTool(): DevinTool {
    val schema = parameters() ?: InputSchema.Obj(properties = JsonObject(emptyMap()))
    return DevinTool(
        name = name,
        description = description,
        parameters = json.encodeToString(InputSchema.serializer(), schema).toByteArray(Charsets.UTF_8),
    )
}

/**
 * data: URI → DevinImage; 其它 URL (http/file) 返回 null 并跳过.
 * 与 ClaudeProvider/GoogleProvider 的上送惯例一致: Devin 上游同样只接受 base64 数据.
 */
internal fun parseDevinImageDataUrl(raw: String): DevinImage? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("data:")) return null
    val comma = trimmed.indexOf(',')
    if (comma < 0) return null
    val header = trimmed.substring(5, comma)
    val data = trimmed.substring(comma + 1)
    if (data.isEmpty()) return null
    val mime = header.split(';').firstOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: "image/png"
    return DevinImage(base64Data = data, mimeType = mime)
}
