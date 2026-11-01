package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import me.rerere.ai.util.parseErrorDetail

internal class ClaudeStreamDecoder : StreamChunkDecoder {
    private val blocks = mutableMapOf<Int, ClaudeStreamBlock>()
    private var responseId: String? = null
    private var responseModel: String? = null
    private var finishReason: String? = null
    private var finished = false

    override fun accept(event: SseEvent): DecodeResult {
        if (finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") return DecodeResult(finish(), completed = true)
        return try {
            acceptEvent(event)
        } catch (e: Throwable) {
            // 协议/解析失败中止状态机：之后 onClosed() 不得把半成品 flush 成成功
            finished = true
            throw e
        }
    }

    private fun acceptEvent(event: SseEvent): DecodeResult {
        val dataJson = json.parseToJsonElement(event.data).jsonObject
        if (event.event == "error") {
            throw (dataJson["error"] ?: dataJson).parseErrorDetail()
        }

        dataJson["message"]?.jsonObject?.let { message ->
            responseId = message["id"]?.jsonPrimitive?.contentOrNull ?: responseId
            responseModel = message["model"]?.jsonPrimitive?.contentOrNull ?: responseModel
        }
        dataJson["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.let {
            finishReason = it
        }

        val chunks = buildList {
            parseTokenUsage(dataJson)?.let { add(StreamChunk.Usage(it)) }
            val index = dataJson["index"]?.jsonPrimitive?.intOrNull
            val contentBlock = dataJson["content_block"]?.jsonObject

            if (event.event == "content_block_start" && index != null && contentBlock != null) {
                val kind = contentBlock["type"]?.jsonPrimitive?.contentOrNull ?: ""
                // 空串 id 按缺失处理：blank id 会让多个 block 共享同一 part 身份，
                // 下游拼接语义下会互相污染（见 ChatCompletionsStreamDecoder 同款防护）。
                val blockId = contentBlock["id"]?.jsonPrimitive?.contentOrNull?.takeUnless { it.isBlank() }
                    ?: contentBlock["tool_use_id"]?.jsonPrimitive?.contentOrNull?.takeUnless { it.isBlank() }
                    ?: "${responseId ?: event.id ?: "response"}:block-$index"
                val metadata = when (kind) {
                    "thinking" -> contentBlock["signature"]?.jsonPrimitive?.contentOrNull?.let {
                        ClaudeReasoningMetadata(signature = it).toMetadata()
                    }
                    else -> null
                }
                // tool_use 的 input 快照不能立刻发射：部分上游转换器在
                // content_block_start 里塞完整 input、后续 input_json_delta 又把同一份
                // JSON 增量重放一遍，两边都收会拼出双份非法 JSON。快照先记在 block 上，
                // 仅当整个 block 没有任何 input delta 时才在 stop/finish 时兜底发出。
                val inputSnapshot = if (kind == "tool_use") {
                    contentBlock["input"]?.jsonObject?.takeIf { it.isNotEmpty() }
                        ?.let { json.encodeToString(it) }
                } else null
                blocks[index] = ClaudeStreamBlock(kind, blockId, metadata, inputSnapshot)
                when (kind) {
                    "text" -> add(StreamChunk.TextStart(blockId))
                    "thinking", "redacted_thinking" -> add(StreamChunk.ReasoningStart(blockId, metadata))
                    "tool_use" -> {
                        add(StreamChunk.ToolCallStart(
                            id = blockId,
                            toolName = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        ))
                    }
                    else -> if (kind.isClaudeServerToolUseType()) {
                        add(StreamChunk.ServerToolStart(
                            id = blockId,
                            toolName = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            input = contentBlock["input"],
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                call = contentBlock,
                                callIndex = index,
                            ).toMetadata(),
                        ))
                    } else if (kind.isClaudeServerToolResultType()) {
                        val output = contentBlock["content"]
                        add(StreamChunk.ServerToolEnd(
                            id = blockId,
                            output = output,
                            status = if (output.isClaudeServerToolError()) {
                                ServerToolStatus.FAILED
                            } else {
                                ServerToolStatus.COMPLETED
                            },
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                result = contentBlock,
                                resultIndex = index,
                            ).toMetadata(),
                        ))
                    }
                }
            }

            if (event.event == "content_block_delta" && index != null) {
                val block = blocks[index] ?: error("Unknown content block index: $index")
                val delta = dataJson["delta"]?.jsonObject ?: JsonObject(emptyMap())
                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> add(StreamChunk.TextDelta(
                        block.id,
                        delta["text"]?.jsonPrimitive?.contentOrNull ?: "",
                    ))
                    "thinking_delta" -> add(StreamChunk.ReasoningDelta(
                        block.id,
                        delta["thinking"]?.jsonPrimitive?.contentOrNull ?: "",
                        block.metadata,
                    ))
                    "signature_delta" -> {
                        val metadata = delta["signature"]?.jsonPrimitive?.contentOrNull?.let {
                            ClaudeReasoningMetadata(signature = it).toMetadata()
                        }
                        blocks[index] = block.copy(metadata = metadata ?: block.metadata)
                        add(StreamChunk.ReasoningDelta(block.id, "", metadata))
                    }
                    "input_json_delta" -> {
                        val partialJson = delta["partial_json"]?.jsonPrimitive?.contentOrNull
                        // 空 delta 或缺 partial_json 字段一律视为 no-op：上游转换器可能
                        // 发送空 input_json_delta，若据此丢弃 start 快照会把完整参数清空。
                        // 只有收到真实参数字节才切换来源（丢弃快照避免双份）并逐字发射，
                        // 合法重复字符绝不去重。
                        if (!partialJson.isNullOrEmpty()) {
                            if (block.initialInput != null) blocks[index] = block.copy(initialInput = null)
                            if (block.kind.isClaudeServerToolUseType()) {
                                add(StreamChunk.ServerToolInputDelta(block.id, partialJson))
                            } else {
                                add(StreamChunk.ToolCallDelta(id = block.id, inputDelta = partialJson))
                            }
                        }
                    }
                }
            }

            if (event.event == "content_block_stop" && index != null) {
                val block = blocks.remove(index) ?: error("Unknown content block index: $index")
                addAll(endBlock(block))
            }
        }

        return if (event.event == "message_stop") {
            DecodeResult(chunks + finish(), completed = true)
        } else {
            DecodeResult(chunks)
        }
    }

    override fun onClosed(): List<StreamChunk> = finish()

    private fun finish(): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        return buildList {
            blocks.values.forEach { addAll(endBlock(it)) }
            blocks.clear()
            add(StreamChunk.Finish(finishReason, responseId, responseModel))
        }
    }

    private fun endBlock(block: ClaudeStreamBlock): List<StreamChunk> = buildList {
        // start 携带的 input 快照且全程无 delta 时，把快照作为一次完整 delta 补发
        if (block.kind == "tool_use" && block.initialInput != null) {
            add(StreamChunk.ToolCallDelta(id = block.id, inputDelta = block.initialInput))
        }
        when (block.kind) {
            "text" -> add(StreamChunk.TextEnd(block.id))
            "thinking", "redacted_thinking" -> add(StreamChunk.ReasoningEnd(block.id, block.metadata))
            "tool_use" -> add(StreamChunk.ToolCallEnd(block.id))
            else -> if (block.kind.isClaudeServerToolUseType()) {
                add(StreamChunk.ServerToolInputEnd(block.id))
            }
        }
    }

    private fun parseTokenUsage(bodyJson: JsonObject): TokenUsage? {
        val usageJson = bodyJson["usage"]?.jsonObject
            ?: bodyJson["message"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val inputTokens = usageJson["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedInputTokens = usageJson["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedCreationTokens = usageJson["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val completionTokens = usageJson["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val promptTokens = inputTokens + cachedInputTokens + cachedCreationTokens
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            cachedTokens = cachedInputTokens,
        )
    }

    private data class ClaudeStreamBlock(
        val kind: String,
        val id: String,
        val metadata: JsonObject? = null,
        /** start 携带的 input 快照；收到首个 input_json_delta 后置 null */
        val initialInput: String? = null,
    )
}
