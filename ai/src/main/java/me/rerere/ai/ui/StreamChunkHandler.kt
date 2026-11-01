package me.rerere.ai.ui

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.util.json
import kotlin.time.Clock

/**
 * 将 Provider 产生的通用流事件合并为一条 [UIMessage]。
 *
 * 文本、推理和图片可能交错到达，因此不能只更新最后一个 part。本类会在收到对应的 `Start`
 * 事件时，记录事件 id 在 [UIMessage.parts] 中的位置；后续 `Delta`、`Snapshot` 和 `End`
 * 事件再通过同一 id 定位并更新该 part。工具调用则直接使用
 * [UIMessagePart.Tool.toolCallId] 定位。
 *
 * [handle] 不会修改传入的消息列表，而是返回包含更新后消息的新列表。如果列表末尾不是助手
 * 消息，第一个事件会自动创建一条空的助手消息。收到 [StreamChunk.Finish] 后，会标记消息完成
 * 并清除内部状态。
 *
 * ## 工作过程
 *
 * 1. 接收一个 [StreamChunk]，检查消息列表非空。
 * 2. 如果列表末尾不是助手消息，创建一条带有当前 [model] id 的空助手消息。
 * 3. 根据事件类型更新助手消息：
 *    - `TextStart/Delta/End`：创建、追加并结束文本 part。
 *    - `ReasoningStart/Delta/End`：创建、追加并标记推理 part 的完成时间。
 *    - `ToolCallStart/Delta/End`：按调用 id 创建工具 part，并逐步拼接工具名和输入参数。
 *      定位到同 id 的已执行 part 时一律另起新 part（provider 复用 id），不复用旧 call。
 *    - `ServerToolStart/InputDelta/InputEnd/End`：追踪服务端工具的 JSON 输入、结果和状态。
 *    - `ImageStart/Delta/End`：创建图片 part，并逐步追加 Base64 数据。
 *    - `ImageSnapshot`：用最新的完整 Base64 快照替换同 id 图片的旧数据。
 *    - `Annotations`：追加并去重消息注解。
 *    - `Usage`：将本次用量合并到消息已有的 Token 用量中。
 *    - `Finish`：设置消息完成时间，结束尚未关闭的推理 part，并清空事件索引。
 * 4. 使用 [UIMessage.copy] 生成更新后的助手消息，将其替换到列表末尾并返回新列表。
 *
 * 一段文本流的典型调用顺序如下：
 *
 * ```
 * TextStart(id) -> TextDelta(id, ...) -> TextDelta(id, ...) -> TextEnd(id) -> Finish
 * ```
 *
 * [StreamChunk.Finish] 只代表响应流正常结束，并不保证一定到达。网络错误、协议解析失败或上层取消
 * Flow 时，流可能直接异常结束。此时已经合并的内容仍然保留，但消息的 `finishedAt` 可能为空，尚未
 * 收到 `ReasoningEnd` 的推理 part 也不会由本类自动结束。调用方应在 Flow 的完成或异常处理中执行
 * 必要的 UI 收尾，并丢弃当前 handler；不要将它复用于下一条响应流。
 *
 * 该类保存着一次响应流的合并状态，不是无状态转换器。每条并发响应流都必须使用独立实例，且
 * 事件应按 Provider 产生的顺序交给同一实例处理。
 */
class StreamChunkHandler(private val model: Model? = null) {
    // Map 的值是对应 part 在当前助手消息 parts 列表中的下标。
    private val textPartIndexes = mutableMapOf<String, Int>()
    private val reasoningPartIndexes = mutableMapOf<String, Int>()
    private val imagePartIndexes = mutableMapOf<String, Int>()
    private val toolCallPartIndexes = mutableMapOf<String, Int>()
    private val serverToolPartIndexes = mutableMapOf<String, Int>()

    // 每个流式 part 的累计内容缓冲，避免逐 delta 对已累积文本做 O(n) 扫描拼接。
    private val textBuffers = mutableMapOf<String, StringBuilder>()
    private val reasoningBuffers = mutableMapOf<String, StringBuilder>()
    private val imageBuffers = mutableMapOf<String, StringBuilder>()
    private val toolInputBuffers = mutableMapOf<String, StringBuilder>()
    private val toolNameBuffers = mutableMapOf<String, StringBuilder>()
    private val serverToolInputBuffers = mutableMapOf<String, StringBuilder>()

    // 双 ID 路径（ToolCallStart.toolCallId != null）下登记的 streamKey：
    // 这些键只按 streamKey→partIndex 精确路由，Delta 绝不用外部 toolCallId
    // 回扫历史 part。toolCallEndedKeys 区分「已 End」与「从未见过」供
    // ToolCallIdAssigned 的协议错误分类。
    private val strictToolStreamKeys = mutableSetOf<String>()
    private val toolCallEndedKeys = mutableSetOf<String>()

    /**
     * 将一个 [chunk] 合并进消息列表末尾的助手消息，并返回新的消息列表。
     *
     * @throws IllegalArgumentException 当 [messages] 为空时抛出
     */
    fun handle(messages: List<UIMessage>, chunk: StreamChunk): List<UIMessage> {
        require(messages.isNotEmpty()) { "messages must not be empty" }

        val targetMessages = if (messages.last().role != MessageRole.ASSISTANT) {
            messages + UIMessage(modelId = model?.id, role = MessageRole.ASSISTANT, parts = emptyList())
        } else {
            messages
        }
        val updatedMessage = append(targetMessages.last(), chunk)
        return targetMessages.dropLast(1) + updatedMessage
    }

    private fun append(message: UIMessage, chunk: StreamChunk): UIMessage = with(message) {
        when (chunk) {
            is StreamChunk.TextStart -> {
                if (chunk.id in textPartIndexes) this
                else copy(parts = parts + UIMessagePart.Text("", chunk.metadata)).also {
                    textPartIndexes[chunk.id] = parts.size
                    textBuffers[chunk.id] = StringBuilder()
                }
            }
            is StreamChunk.TextDelta -> {
                val index = textPartIndexes[chunk.id]
                // 容忍 Provider 未发送 Start：首次收到 Delta 时直接创建对应 part。
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Text) {
                    copy(parts = parts + UIMessagePart.Text(chunk.text, chunk.metadata)).also {
                        textPartIndexes[chunk.id] = parts.size
                        textBuffers[chunk.id] = StringBuilder(chunk.text)
                    }
                } else {
                    val buffer = textBuffers.getOrPut(chunk.id) {
                        StringBuilder((parts[index] as UIMessagePart.Text).text)
                    }
                    buffer.append(chunk.text)
                    copy(parts = parts.toMutableList().apply {
                        val text = get(index) as UIMessagePart.Text
                        set(
                            index, text.copy(
                                text = buffer.toString(),
                                metadata = chunk.metadata ?: text.metadata,
                            )
                        )
                    })
                }
            }

            is StreamChunk.TextEnd -> this.also {
                textPartIndexes.remove(chunk.id)
                textBuffers.remove(chunk.id)
            }

            is StreamChunk.ReasoningStart -> {
                if (chunk.id in reasoningPartIndexes) this
                else copy(parts = parts + UIMessagePart.Reasoning(
                    reasoning = "",
                    createdAt = Clock.System.now(),
                    finishedAt = null,
                    metadata = chunk.metadata,
                    reasoningType = chunk.reasoningType,
                )).also {
                    reasoningPartIndexes[chunk.id] = parts.size
                    reasoningBuffers[chunk.id] = StringBuilder()
                }
            }

            is StreamChunk.ReasoningDelta -> {
                val index = reasoningPartIndexes[chunk.id]
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Reasoning) {
                    copy(parts = parts + UIMessagePart.Reasoning(
                        reasoning = chunk.text,
                        createdAt = Clock.System.now(),
                        finishedAt = null,
                        metadata = chunk.metadata,
                        reasoningType = chunk.reasoningType,
                    )).also {
                        reasoningPartIndexes[chunk.id] = parts.size
                        reasoningBuffers[chunk.id] = StringBuilder(chunk.text)
                    }
                } else {
                    val buffer = reasoningBuffers.getOrPut(chunk.id) {
                        StringBuilder((parts[index] as UIMessagePart.Reasoning).reasoning)
                    }
                    buffer.append(chunk.text)
                    copy(parts = parts.toMutableList().apply {
                        val reasoning = get(index) as UIMessagePart.Reasoning
                        set(index, reasoning.copy(
                            reasoning = buffer.toString(),
                            metadata = chunk.metadata ?: reasoning.metadata,
                            reasoningType = chunk.reasoningType,
                        ))
                    })
                }
            }

            is StreamChunk.ReasoningEnd -> {
                reasoningBuffers.remove(chunk.id)
                val index = reasoningPartIndexes.remove(chunk.id)
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Reasoning) this
                else copy(parts = parts.toMutableList().apply {
                    val reasoning = get(index) as UIMessagePart.Reasoning
                    set(index, reasoning.copy(
                        finishedAt = Clock.System.now(),
                        metadata = chunk.metadata ?: reasoning.metadata,
                    ))
                })
            }

            is StreamChunk.ToolCallStart -> {
                if (chunk.toolCallId != null) {
                    // ---- 双 ID 路径（strict）：chunk.id 是内部 streamKey ----
                    // 只按 streamKey→partIndex 精确路由，绝不用外部 toolCallId
                    // 回扫历史 part。外部 id 必须显式非空白（双 ID 的判定键）；
                    // 身份/名字冲突、End 后重开一律 typed 协议错误，不默默新开 part。
                    if (chunk.toolCallId.isBlank()) {
                        throw ToolStreamProtocolException(
                            code = "tool_call_start_blank_external_id",
                            message = "tool call start on the dual-id path must " +
                                "carry a non-blank external id",
                        )
                    }
                    strictToolStreamKeys += chunk.id
                    val mappedIndex = toolCallPartIndexes[chunk.id]
                    if (mappedIndex == null) {
                        if (chunk.id in toolCallEndedKeys) {
                            throw ToolStreamProtocolException(
                                code = "tool_call_start_after_end",
                                message = "tool call start reuses an ended stream key",
                            )
                        }
                        copy(parts = parts + UIMessagePart.Tool(
                            toolCallId = chunk.toolCallId,
                            toolName = chunk.toolName,
                            input = "",
                            metadata = chunk.metadata,
                        )).also {
                            toolCallPartIndexes[chunk.id] = parts.size
                            toolNameBuffers[chunk.id] = StringBuilder(chunk.toolName)
                            toolInputBuffers[chunk.id] = StringBuilder()
                        }
                    } else {
                        val existing = parts.getOrNull(mappedIndex) as? UIMessagePart.Tool
                            ?: throw ToolStreamProtocolException(
                                code = "tool_call_start_missing_part",
                                message = "tool call stream key maps to a missing " +
                                    "or non-tool part",
                            )
                        if (existing.isExecuted) {
                            throw ToolStreamProtocolException(
                                code = "tool_call_start_executed",
                                message = "tool call start reuses the stream key " +
                                    "of an executed part",
                            )
                        }
                        if (existing.toolCallId != chunk.toolCallId) {
                            throw ToolStreamProtocolException(
                                code = "tool_call_id_conflict",
                                message = "tool call start restates a different " +
                                    "external id; changes must use ToolCallIdAssigned",
                            )
                        }
                        val namesCompatible = chunk.toolName.isBlank() ||
                            existing.toolName.isBlank() ||
                            chunk.toolName.startsWith(existing.toolName) ||
                            existing.toolName.startsWith(chunk.toolName)
                        if (!namesCompatible) {
                            throw ToolStreamProtocolException(
                                code = "tool_name_conflict",
                                message = "tool call start restates an " +
                                    "incompatible name on a strict stream key",
                            )
                        }
                        reuseToolPart(mappedIndex, existing, chunk)
                    }
                } else {
                    // ---- legacy 路径（toolCallId == null）：id 即外部 ----
                    // toolCallId，保持原兼容语义：已执行 tool 对后续 Start 不可见
                    // （provider 跨 response 复用同一 toolCallId 时必须另起 part，
                    // 绝不能把新 call 的 delta 拼进旧 call）；未执行同 id part 且
                    // 名字兼容时复用 part；名字互不兼容 = 同 id 全新调用，追加为
                    // 新 part，与非流式 isSameCallSnapshot 规则对称。
                    val existingIndex = parts.indexOfLast {
                        it is UIMessagePart.Tool && it.toolCallId == chunk.id && !it.isExecuted
                    }
                    val existing = existingIndex.takeIf { it >= 0 }
                        ?.let { parts[it] as UIMessagePart.Tool }
                        ?.takeIf {
                            chunk.toolName.isBlank() || it.toolName.isBlank() ||
                                chunk.toolName.startsWith(it.toolName) ||
                                it.toolName.startsWith(chunk.toolName)
                        }
                    if (existing != null) {
                        reuseToolPart(existingIndex, existing, chunk)
                    } else {
                        copy(parts = parts + UIMessagePart.Tool(
                            toolCallId = chunk.id,
                            toolName = chunk.toolName,
                            input = "",
                            metadata = chunk.metadata,
                        )).also {
                            toolCallPartIndexes[chunk.id] = parts.size
                            toolNameBuffers[chunk.id] = StringBuilder(chunk.toolName)
                            toolInputBuffers[chunk.id] = StringBuilder()
                        }
                    }
                }
            }

            is StreamChunk.ToolCallIdAssigned -> {
                // 真实 wire id 迟到补绑：只按 streamKey→partIndex 精确命中进行中
                // part 就地改 toolCallId，不回扫旧 external id 历史。空白外部 id
                // 与三种定位错误（未知 key / 已 End / part 已执行）一律 fail-closed。
                if (chunk.toolCallId.isBlank()) {
                    throw ToolStreamProtocolException(
                        code = "tool_call_id_assign_blank",
                        message = "tool call id assignment must carry a non-blank " +
                            "external id",
                    )
                }
                val index = toolCallPartIndexes[chunk.id]
                    ?: throw ToolStreamProtocolException(
                        code = if (chunk.id in toolCallEndedKeys) {
                            "tool_call_id_assign_after_end"
                        } else {
                            "tool_call_id_assign_unknown_key"
                        },
                        message = "tool call id assignment targets unknown or " +
                            "ended stream key (assigned id '${chunk.toolCallId}')",
                    )
                val part = parts.getOrNull(index) as? UIMessagePart.Tool
                if (part == null || part.isExecuted) {
                    throw ToolStreamProtocolException(
                        code = "tool_call_id_assign_executed",
                        message = "tool call id assignment targets an executed part",
                    )
                }
                copy(parts = parts.toMutableList().apply {
                    set(index, part.copy(toolCallId = chunk.toolCallId))
                })
            }

            is StreamChunk.ToolCallDelta -> {
                // 工具调用可以并行生成，通过 toolCallId 而不是 part 位置识别目标。
                // 索引与兜底查找都限定未执行 part：命中已执行 call 意味着 provider
                // 复用了旧 id，这里的裸 delta 无法构成完整调用，按丢失处理但绝不
                // 污染已执行 call 的 name/input（与 Start 另起 part 的规则对称）。
                // 双 ID 路径的 streamKey 没有匹配的外部 toolCallId 语义，跳过兜底
                // 回扫——漏掉的 delta 与 legacy 的 id 复用隔离，不能跨 part 污染。
                val index = toolCallPartIndexes[chunk.id]
                    ?: run {
                        if (chunk.id in strictToolStreamKeys) {
                            // strict streamKey 的 Delta 落在已 End 后的窗口：
                            // typed 拒绝，绝不静默丢弃或回扫历史 part。
                            throw ToolStreamProtocolException(
                                code = "tool_call_delta_after_end",
                                message = "tool call delta arrives for a strict " +
                                    "stream key after its ToolCallEnd",
                            )
                        }
                        parts.indexOfLast {
                            it is UIMessagePart.Tool && it.toolCallId == chunk.id &&
                                !it.isExecuted
                        }.takeIf { it >= 0 }?.also { toolCallPartIndexes[chunk.id] = it }
                    }
                val tool = index?.let { parts.getOrNull(it) } as? UIMessagePart.Tool
                if (tool == null || tool.isExecuted) {
                    if (chunk.id in strictToolStreamKeys) {
                        throw ToolStreamProtocolException(
                            code = if (tool == null) {
                                "tool_call_delta_missing_part"
                            } else {
                                "tool_call_delta_executed"
                            },
                            message = "tool call delta for a strict stream key " +
                                "targets a missing or executed part",
                        )
                    }
                    this
                } else {
                    val nameBuffer = toolNameBuffers.getOrPut(chunk.id) { StringBuilder(tool.toolName) }
                        .append(chunk.toolNameDelta)
                    val inputBuffer = toolInputBuffers.getOrPut(chunk.id) { StringBuilder(tool.input) }
                        .append(chunk.inputDelta)
                    copy(parts = parts.toMutableList().apply {
                        set(index, tool.copy(
                            toolName = nameBuffer.toString(),
                            input = inputBuffer.toString(),
                            metadata = chunk.metadata ?: tool.metadata,
                        ))
                    })
                }
            }

            is StreamChunk.ToolCallEnd -> this.also {
                toolCallPartIndexes.remove(chunk.id)
                toolNameBuffers.remove(chunk.id)
                toolInputBuffers.remove(chunk.id)
                toolCallEndedKeys += chunk.id
            }

            is StreamChunk.ToolCallOutput -> {
                val index = parts.indexOfLast { it is UIMessagePart.Tool && it.toolCallId == chunk.toolCallId }
                if (index < 0) {
                    this
                } else {
                    copy(parts = parts.toMutableList().apply {
                        set(index, (parts[index] as UIMessagePart.Tool).copy(
                            output = listOf(UIMessagePart.Text(chunk.output))
                        ))
                    })
                }
            }

            is StreamChunk.ServerToolStart -> {
                val index = serverToolPartIndexes[chunk.id]
                    ?: parts.indexOfFirst {
                        it is UIMessagePart.ServerTool && it.toolCallId == chunk.id
                    }
                if (index < 0) {
                    copy(parts = parts + UIMessagePart.ServerTool(
                        toolCallId = chunk.id,
                        toolName = chunk.toolName,
                        input = chunk.input,
                        status = ServerToolStatus.IN_PROGRESS,
                        metadata = chunk.metadata,
                    )).also { serverToolPartIndexes[chunk.id] = parts.size }
                } else {
                    serverToolPartIndexes[chunk.id] = index
                    copy(parts = parts.toMutableList().apply {
                        val tool = get(index) as UIMessagePart.ServerTool
                        set(index, tool.copy(
                            toolName = chunk.toolName.ifBlank { tool.toolName },
                            input = chunk.input ?: tool.input,
                            metadata = mergeMetadata(tool.metadata, chunk.metadata),
                        ))
                    })
                }
            }

            is StreamChunk.ServerToolInputDelta -> {
                val buffer = serverToolInputBuffers.getOrPut(chunk.id) { StringBuilder() }
                buffer.append(chunk.inputDelta)
                updateServerTool(chunk.id) { tool ->
                    tool.copy(metadata = mergeMetadata(tool.metadata, chunk.metadata))
                }
            }

            is StreamChunk.ServerToolInputEnd -> {
                val input = serverToolInputBuffers.remove(chunk.id)?.toString()?.takeIf { it.isNotBlank() }
                    ?.let(::parseServerToolJson)
                if (input == null) this else updateServerTool(chunk.id) { it.copy(input = input) }
            }

            is StreamChunk.ServerToolEnd -> {
                val bufferedInput = serverToolInputBuffers.remove(chunk.id)?.toString()?.takeIf { it.isNotBlank() }
                    ?.let(::parseServerToolJson)
                val index = serverToolPartIndexes[chunk.id]
                    ?: parts.indexOfFirst {
                        it is UIMessagePart.ServerTool && it.toolCallId == chunk.id
                    }
                if (index < 0) {
                    copy(parts = parts + UIMessagePart.ServerTool(
                        toolCallId = chunk.id,
                        toolName = "",
                        input = chunk.input ?: bufferedInput,
                        output = chunk.output,
                        status = chunk.status,
                        metadata = chunk.metadata,
                    ))
                } else {
                    updateServerTool(chunk.id) { tool ->
                        tool.copy(
                            input = chunk.input ?: bufferedInput ?: tool.input,
                            output = chunk.output ?: tool.output,
                            status = chunk.status,
                            metadata = mergeMetadata(tool.metadata, chunk.metadata),
                        )
                    }
                }
            }
            is StreamChunk.ImageStart -> {
                if (chunk.id in imagePartIndexes) this
                else {
                    val url = "data:${chunk.mimeType};base64,"
                    copy(parts = parts + UIMessagePart.Image(
                        url = url,
                        metadata = chunk.metadata,
                    )).also {
                        imagePartIndexes[chunk.id] = parts.size
                        imageBuffers[chunk.id] = StringBuilder(url)
                    }
                }
            }

            is StreamChunk.ImageDelta -> {
                val index = imagePartIndexes[chunk.id]
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Image) {
                    copy(parts = parts + UIMessagePart.Image(chunk.data, chunk.metadata)).also {
                        imagePartIndexes[chunk.id] = parts.size
                        imageBuffers[chunk.id] = StringBuilder(chunk.data)
                    }
                } else {
                    val buffer = imageBuffers.getOrPut(chunk.id) {
                        StringBuilder((parts[index] as UIMessagePart.Image).url)
                    }
                    buffer.append(chunk.data)
                    copy(parts = parts.toMutableList().apply {
                        val image = get(index) as UIMessagePart.Image
                        set(index, image.copy(
                            url = buffer.toString(),
                            metadata = chunk.metadata ?: image.metadata,
                        ))
                    })
                }
            }

            is StreamChunk.ImageSnapshot -> {
                val index = imagePartIndexes[chunk.id]
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Image) {
                    val url = "data:image/png;base64,${chunk.data}"
                    copy(
                        parts = parts + UIMessagePart.Image(
                            url = url,
                            metadata = chunk.metadata,
                        )
                    ).also {
                        imagePartIndexes[chunk.id] = parts.size
                        imageBuffers[chunk.id] = StringBuilder(url)
                    }
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val image = get(index) as UIMessagePart.Image
                        // Snapshot 是一张完整的可渲染图片，只替换 data URL 的数据部分；
                        // 与 ImageDelta 不同，它不会把数据追加到上一帧之后。
                        val dataUrlPrefix = image.url.substringBefore(",").takeIf { it.startsWith("data:") }
                            ?: "data:image/png;base64"
                        val url = "$dataUrlPrefix,${chunk.data}"
                        imageBuffers[chunk.id] = StringBuilder(url)
                        set(index, image.copy(
                            url = url,
                            metadata = chunk.metadata ?: image.metadata,
                        ))
                    })
                }
            }

            is StreamChunk.ImageEnd -> this.also {
                imagePartIndexes.remove(chunk.id)
                imageBuffers.remove(chunk.id)
            }

            is StreamChunk.Annotations -> copy(annotations = (annotations + chunk.annotations).distinct())
            is StreamChunk.Usage -> copy(usage = usage.merge(chunk.usage))
            is StreamChunk.Finish -> copy(
                finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            ).finishReasoning().also {
                // Finish 同时结束尚未显式结束的 reasoning，并释放本次响应流的索引状态。
                textPartIndexes.clear()
                reasoningPartIndexes.clear()
                imagePartIndexes.clear()
                toolCallPartIndexes.clear()
                serverToolPartIndexes.clear()
                textBuffers.clear()
                reasoningBuffers.clear()
                imageBuffers.clear()
                toolInputBuffers.clear()
                toolNameBuffers.clear()
                serverToolInputBuffers.clear()
                strictToolStreamKeys.clear()
                toolCallEndedKeys.clear()
            }
        }
    }

    /**
     * 未执行同调用 part 的 Start 复用：缓冲与 part 必须从同一个刚接受的
     * nextName 一次性建立（缺失时才播种），input/name 缓冲一律保留不清空
     * —— 否则已确认前缀会整体丢失或名称倒退（曾用 existing.toolName 补种
     * 导致 End→Start→Delta 序列名称回退）。
     */
    private fun UIMessage.reuseToolPart(
        existingIndex: Int,
        existing: UIMessagePart.Tool,
        chunk: StreamChunk.ToolCallStart,
    ): UIMessage {
        val nextName = when {
            chunk.toolName.isBlank() -> existing.toolName
            chunk.toolName.startsWith(existing.toolName) -> chunk.toolName
            else -> existing.toolName
        }
        toolCallPartIndexes[chunk.id] = existingIndex
        toolNameBuffers[chunk.id] = toolNameBuffers[chunk.id]
            ?.also { it.clear(); it.append(nextName) }
            ?: StringBuilder(nextName)
        toolInputBuffers.getOrPut(chunk.id) { StringBuilder(existing.input) }
        return if (existing.toolName != nextName || chunk.metadata != null) {
            copy(parts = parts.toMutableList().apply {
                set(existingIndex, existing.copy(
                    toolName = nextName,
                    metadata = chunk.metadata ?: existing.metadata,
                ))
            })
        } else this
    }

    private fun UIMessage.updateServerTool(
        id: String,
        transform: (UIMessagePart.ServerTool) -> UIMessagePart.ServerTool,
    ): UIMessage {
        val index = serverToolPartIndexes[id]
            ?: parts.indexOfFirst {
                it is UIMessagePart.ServerTool && it.toolCallId == id
            }.takeIf { it >= 0 }?.also { serverToolPartIndexes[id] = it }
            ?: return this
        val part = parts.getOrNull(index) as? UIMessagePart.ServerTool ?: return this
        return copy(parts = parts.toMutableList().apply {
            set(index, transform(part))
        })
    }
}

/**
 * 把一份完整的工具调用快照应用到未执行的既有 part 上。
 *
 * 与增量 [UIMessagePart.Tool.merge] 不同，这里 name/input 是替换不是拼接；
 * 快照留空的字段保留旧值。output 在生成侧恒为空，若未来出现非空快照则按
 * 到达顺序追加；approvalState 与 toolCallId 由既有 part 保留。
 */
private fun UIMessagePart.Tool.applyToolSnapshot(snapshot: UIMessagePart.Tool): UIMessagePart.Tool {
    return copy(
        toolName = snapshot.toolName.ifBlank { toolName },
        input = snapshot.input.ifBlank { input },
        output = output + snapshot.output,
        metadata = snapshot.metadata ?: metadata,
    )
}

/**
 * 判断 [snapshot] 是否仍是本 part 所代表的同一个 tool call。
 *
 * 快照更新只允许单向增长：快照的每个非空字段必须等于旧值或以旧值为前缀
 * （同一个 call 从半截重传/重放）。任一字段不兼容就说明 provider 复用了
 * toolCallId 发来全新调用，此时不能替换旧 part，必须追加为新 part——否则
 * pause_turn 续跑或多响应合并时会静默改写消息里较早的那次调用。
 */
private fun UIMessagePart.Tool.isSameCallSnapshot(snapshot: UIMessagePart.Tool): Boolean {
    fun extends(old: String, incoming: String) =
        incoming.isBlank() || incoming.startsWith(old)
    return extends(toolName, snapshot.toolName) && extends(input, snapshot.input)
}

private fun parseServerToolJson(value: String) = runCatching {
    json.parseToJsonElement(value)
}.getOrElse { JsonPrimitive(value) }

private fun mergeMetadata(old: JsonObject?, new: JsonObject?): JsonObject? = when {
    old == null -> new
    new == null -> old
    else -> JsonObject(old + new)
}

/**
 * [newStep]: the result is the next request of a multi-step (tool call) generation. Its text and
 * reasoning become parts of their own instead of being glued onto the previous step's last part.
 * Provider-internal continuations of one reply (Claude `pause_turn`) keep the default.
 */
fun List<UIMessage>.handleTextGenerationResult(
    result: TextGenerationResult,
    model: Model? = null,
    newStep: Boolean = false,
): List<UIMessage> {
    require(isNotEmpty()) { "messages must not be empty" }
    val incoming = result.message.copy(
        modelId = model?.id,
        usage = result.usage,
        finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
    ).finishReasoning()
    return if (last().role != incoming.role) {
        this + incoming
    } else {
        dropLast(1) + last().appendMessage(incoming, mergeTextAndReasoning = !newStep).copy(
            modelId = model?.id ?: last().modelId,
            usage = last().usage.merge(result.usage ?: TokenUsage()),
            finishedAt = incoming.finishedAt,
        ).finishReasoning()
    }
}

private fun UIMessage.appendMessage(delta: UIMessage, mergeTextAndReasoning: Boolean = true): UIMessage {
    var newParts = delta.parts.fold(parts) { acc, deltaPart ->
        when (deltaPart) {
            is UIMessagePart.Text -> {
                if (deltaPart.text.isEmpty()) {
                    acc
                } else {
                    val lastPart = acc.lastOrNull()
                    if (mergeTextAndReasoning && lastPart is UIMessagePart.Text) {
                        acc.dropLast(1) + lastPart.copy(text = lastPart.text + deltaPart.text)
                    } else {
                        acc + deltaPart
                    }
                }
            }

            // 非流式解析已经产出完整可渲染的 URL(data URI 或 http)，
            // 这里每张图片都是独立的完整图片，不能拼接 URL 或补 data 前缀。
            is UIMessagePart.Image -> acc + deltaPart

            is UIMessagePart.Reasoning -> {
                if (deltaPart.reasoning.isEmpty() && deltaPart.metadata == null) {
                    acc
                } else {
                    val lastPart = acc.lastOrNull()
                    if (mergeTextAndReasoning && lastPart is UIMessagePart.Reasoning &&
                        lastPart.reasoningType == deltaPart.reasoningType
                    ) {
                        acc.dropLast(1) + UIMessagePart.Reasoning(
                            reasoning = lastPart.reasoning + deltaPart.reasoning,
                            createdAt = lastPart.createdAt,
                            finishedAt = null,
                            metadata = deltaPart.metadata ?: lastPart.metadata,
                            reasoningType = lastPart.reasoningType,
                        )
                    } else {
                        acc + deltaPart
                    }
                }
            }

            is UIMessagePart.Tool -> {
                // 非流式响应里的 Tool part 是完整快照，不是增量：不能走 Tool.merge
                // （它按 delta 追加 name/input，会把重发/累计的完整名再拼一次，
                // 甚至污染已经执行过的旧 call）。确定规则：
                // - 空 id：无法与既有 call 可靠关联，一律追加为新 part；跨轮不回填
                //   任何旧工具（包括同位置的旧空 id call）。
                // - 非空 id 只在命中未执行 call 且快照兼容（isSameCallSnapshot：
                //   逐字段相等或是旧值的前缀增长）时按快照更新；快照更新只发生在
                //   当前合并消息内最后一个未执行同 id part 上（findLast）。
                // - 命中的是已执行 call，或同 id 快照互不兼容（provider 复用了旧 id
                //   发全新调用），追加为新 part，绝不动旧 call 字段。
                if (deltaPart.toolCallId.isBlank()) {
                    acc + deltaPart.copy()
                } else {
                    val existingPart = acc.findLast {
                        it is UIMessagePart.Tool && it.toolCallId == deltaPart.toolCallId &&
                            !it.isExecuted
                    } as? UIMessagePart.Tool
                    if (existingPart == null || !existingPart.isSameCallSnapshot(deltaPart)) {
                        acc + deltaPart.copy()
                    } else {
                        acc.map { part ->
                            if (part === existingPart) existingPart.applyToolSnapshot(deltaPart) else part
                        }
                    }
                }
            }

            is UIMessagePart.ServerTool -> {
                val existingPart = acc.find {
                    it is UIMessagePart.ServerTool && it.toolCallId == deltaPart.toolCallId
                } as? UIMessagePart.ServerTool
                if (existingPart == null) {
                    acc + deltaPart
                } else {
                    acc.map { part ->
                        if (part is UIMessagePart.ServerTool && part.toolCallId == deltaPart.toolCallId) {
                            part.copy(
                                toolName = deltaPart.toolName.ifBlank { part.toolName },
                                input = deltaPart.input ?: part.input,
                                output = deltaPart.output ?: part.output,
                                status = deltaPart.status,
                                metadata = mergeMetadata(part.metadata, deltaPart.metadata),
                            )
                        } else part
                    }
                }
            }

            else -> acc
        }
    }

    if (parts.filterIsInstance<UIMessagePart.Reasoning>().isNotEmpty() &&
        delta.parts.filterIsInstance<UIMessagePart.Reasoning>().isEmpty()
    ) {
        newParts = newParts.map { part ->
            if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                part.copy(finishedAt = Clock.System.now())
            } else {
                part
            }
        }
    }

    return copy(
        parts = newParts,
        annotations = delta.annotations.ifEmpty { annotations },
    )
}
