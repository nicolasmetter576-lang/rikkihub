package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import me.rerere.ai.util.parseErrorDetail
import me.rerere.common.http.jsonObjectOrNull

internal class ResponseApiStreamDecoder : StreamChunkDecoder {
    private val state = ResponseStreamState()

    override fun accept(event: SseEvent): DecodeResult {
        if (state.finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") return DecodeResult(state.finish(), completed = true)

        val payload = try {
            json.parseToJsonElement(event.data).jsonObject
        } catch (error: Throwable) {
            state.abort()
            throw error
        }
        val chunks = try {
            parseEvent(payload)
        } catch (e: Throwable) {
            // 协议错误/解析失败同样中止状态机：后续 onClosed() 不得把半成品 flush 成成功
            state.abort()
            throw e
        }
        val eventType = payload["type"]?.jsonPrimitive?.contentOrNull
        val completed = eventType == "response.completed" || eventType == "response.incomplete" ||
            event.event == "response.completed" || event.event == "response.incomplete"
        return DecodeResult(chunks, completed)
    }

    override fun onClosed(): List<StreamChunk> = state.finish()

    private fun parseEvent(payload: JsonObject): List<StreamChunk> {
        val chunkType = payload["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
        val itemId = payload["item_id"]?.jsonPrimitive?.contentOrNull
        val outputIndex = payload["output_index"]?.jsonPrimitive?.intOrNull
        val contentIndex = payload["content_index"]?.jsonPrimitive?.intOrNull ?: 0
        val summaryIndex = payload["summary_index"]?.jsonPrimitive?.intOrNull ?: contentIndex
        val textId = itemId?.let { "$it:text:$contentIndex" }
        val summaryReasoningId = itemId?.let { "$it:reasoning:summary:$summaryIndex" }
        val contentReasoningId = itemId?.let { "$it:reasoning:content:$contentIndex" }

        return when (chunkType) {
            "response.output_text.delta" -> state.textDelta(
                textId ?: error("item_id not found"),
                payload["delta"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            "response.reasoning_summary_text.delta" -> state.reasoningDelta(
                summaryReasoningId ?: error("item_id not found"),
                payload["delta"]?.jsonPrimitive?.contentOrNull ?: "",
                state.reasoningMetadata[itemId],
                ReasoningType.SUMMARY_TEXT,
            )
            "response.reasoning_text.delta" -> state.reasoningDelta(
                contentReasoningId ?: error("item_id not found"),
                payload["delta"]?.jsonPrimitive?.contentOrNull ?: "",
                state.reasoningMetadata[itemId],
                ReasoningType.REASONING_TEXT,
            )
            "response.content_part.added" -> {
                val part = payload["part"]?.jsonObject ?: return emptyList()
                if (part["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                    state.startText(textId ?: error("item_id not found"))
                } else emptyList()
            }
            "response.content_part.done", "response.output_text.done" ->
                state.endText(textId ?: error("item_id not found"))
            "response.reasoning_summary_part.added" -> state.startReasoning(
                summaryReasoningId ?: error("item_id not found"),
                state.reasoningMetadata[itemId],
                ReasoningType.SUMMARY_TEXT,
            )
            "response.reasoning_summary_part.done",
            "response.reasoning_summary_text.done",
            "response.reasoning_text.done" -> emptyList()
            "response.output_item.added" -> {
                val item = payload["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                if (type == "function_call") {
                    // function_call 的 item.id 规范上可选：只有 call_id 也是合法身份；
                    // 空白 id 仍由账本按缺失/冲突语义 fail-closed，不在这里放宽其他类型
                    state.addFunctionCallItem(
                        itemId = item["id"]?.jsonPrimitive?.contentOrNull,
                        wireCallId = item["call_id"]?.jsonPrimitive?.contentOrNull
                            ?.takeUnless { it.isBlank() },
                        outputIndex = outputIndex,
                        name = item["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        initialArguments = item["arguments"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                } else if (type == "function_call_output") {
                    functionCallOutput(item)
                } else {
                    val id = item["id"]?.jsonPrimitive?.content ?: error("chunk id not found")
                    when (type) {
                        "image_generation_call" -> state.startImage(id)
                        "reasoning" -> {
                            state.reasoningMetadata[id] = OpenAIReasoningMetadata(
                                reasoningId = id,
                                encryptedContent = item["encrypted_content"]?.jsonPrimitive?.contentOrNull,
                            ).toMetadata()
                            emptyList()
                        }
                        else -> if (isOpenAIServerToolCall(type)) {
                            state.startServerTool(item.toOpenAIServerTool())
                        } else emptyList()
                    }
                }
            }
            "response.output_item.done" -> {
                val item = payload["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                if (type == "function_call") {
                    return state.completeFunctionCallItem(
                        itemId = item["id"]?.jsonPrimitive?.contentOrNull,
                        wireCallId = item["call_id"]?.jsonPrimitive?.contentOrNull
                            ?.takeUnless { it.isBlank() },
                        outputIndex = outputIndex,
                        finalName = item["name"]?.jsonPrimitive?.contentOrNull,
                        finalArguments = item["arguments"]?.jsonPrimitive?.contentOrNull,
                    )
                }
                if (type == "function_call_output") return functionCallOutput(item)
                val id = item["id"]?.jsonPrimitive?.content ?: error("chunk id not found")
                return when (type) {
                    "reasoning" -> {
                        val metadata = OpenAIReasoningMetadata(
                            reasoningId = id,
                            encryptedContent = item["encrypted_content"]?.jsonPrimitive?.contentOrNull,
                        ).toMetadata()
                        state.reasoningMetadata[id] = metadata
                        state.endReasoningItem(id, metadata)
                    }
                    "image_generation_call" -> buildList {
                        addAll(state.startImage(id))
                        item["result"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let {
                            add(StreamChunk.ImageSnapshot(id, it))
                        }
                        addAll(state.endImage(id))
                    }
                    else -> if (isOpenAIServerToolCall(type)) {
                        val tool = item.toOpenAIServerTool()
                        state.endServerTool(
                            id = tool.toolCallId,
                            input = tool.input,
                            output = tool.output,
                            status = tool.status.takeUnless { it == ServerToolStatus.IN_PROGRESS }
                                ?: ServerToolStatus.COMPLETED,
                            metadata = tool.metadata,
                        )
                    } else emptyList()
                }
            }
            "response.function_call_arguments.delta" -> state.toolArgumentsDelta(
                itemId = itemId,
                wireCallId = payload["call_id"]?.jsonPrimitive?.contentOrNull
                    ?.takeUnless { it.isBlank() },
                outputIndex = outputIndex,
                delta = payload["delta"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            "response.function_call_arguments.done" -> state.completeToolCall(
                itemId = itemId,
                wireCallId = payload["call_id"]?.jsonPrimitive?.contentOrNull
                    ?.takeUnless { it.isBlank() },
                outputIndex = outputIndex,
                finalName = null,
                finalArguments = payload["arguments"]?.jsonPrimitive?.contentOrNull,
            )
            "response.image_generation_call.partial_image" -> {
                val requiredItemId = itemId ?: error("item_id not found")
                buildList {
                    addAll(state.startImage(requiredItemId))
                    add(StreamChunk.ImageSnapshot(
                        requiredItemId,
                        payload["partial_image_b64"]?.jsonPrimitive?.contentOrNull ?: "",
                    ))
                }
            }
            "response.completed", "response.incomplete" -> parseTerminalResponse(payload)
            "response.failed" -> failWithResponseError(payload)
            "error" -> failWithError(payload)
            else -> parseServerToolStatusEvent(chunkType, itemId)
        }
    }

    private fun parseTerminalResponse(payload: JsonObject): List<StreamChunk> {
        val response = payload["response"]?.jsonObject
        val status = response?.get("status")?.jsonPrimitive?.contentOrNull
            ?: payload["type"]?.jsonPrimitive?.contentOrNull?.removePrefix("response.")
        val incompleteReason = response?.get("incomplete_details")?.jsonObjectOrNull
            ?.get("reason")?.jsonPrimitive?.contentOrNull
        val finishReason = if (status == "incomplete" && incompleteReason != null) {
            "$status:$incompleteReason"
        } else {
            status
        }

        return buildList {
            parseUsage(response?.get("usage") as? JsonObject)?.let { add(StreamChunk.Usage(it)) }
            addAll(state.finish(
                finishReason = finishReason,
                responseId = response?.get("id")?.jsonPrimitive?.contentOrNull,
                model = response?.get("model")?.jsonPrimitive?.contentOrNull,
            ))
        }
    }

    // 部分兼容网关会在服务端执行函数工具并下发 function_call_output，需挂到对应工具上，避免客户端重复执行
    private fun functionCallOutput(item: JsonObject): List<StreamChunk> {
        val callId = item["call_id"]?.jsonPrimitive?.contentOrNull?.takeUnless { it.isBlank() } ?: return emptyList()
        return listOf(StreamChunk.ToolCallOutput(callId, item.functionCallOutputText()))
    }

    private fun failWithResponseError(payload: JsonObject): Nothing {
        val response = payload["response"]?.jsonObject
        return failWithError(response?.get("error")?.takeUnless { it is JsonNull }
            ?: response
            ?: payload)
    }

    private fun failWithError(error: JsonElement): Nothing {
        state.abort()
        throw error.parseErrorDetail()
    }

    private fun parseServerToolStatusEvent(chunkType: String, itemId: String?): List<StreamChunk> {
        val event = chunkType.removePrefix("response.")
        val itemType = event.substringBeforeLast('.', missingDelimiterValue = "")
        val providerStatus = event.substringAfterLast('.', missingDelimiterValue = "")
        if (!isOpenAIServerToolCall(itemType) || itemId == null) return emptyList()

        return when (providerStatus) {
            "completed" -> state.endServerTool(itemId, status = ServerToolStatus.COMPLETED)
            "failed", "cancelled", "incomplete" ->
                state.endServerTool(itemId, status = ServerToolStatus.FAILED)
            else -> state.startServerTool(itemId, itemType.removeSuffix("_call"))
        }
    }

    private fun parseUsage(usage: JsonObject?): TokenUsage? {
        if (usage == null) return null
        return TokenUsage(
            promptTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = usage["input_tokens_details"]?.jsonObjectOrNull
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private class ResponseStreamState {
        // function_call 账本按 item_id 保存至响应结束：done 只是关闭发射，
        // 不能删映射，否则同值重发的 done 会重复追加参数。
        // 兼容输入允许 item_id 缺失，此时账本只有 call_id（甚至仅 output_index
        // 先见的声明）身份：三张别名表都指向同一账本，任何两条线索指向不同
        // 账本都是协议错误。call_id 别名表是唯一的全量账本集合（账本 callId
        // 必然非空），finish 遍历它保证每个调用只收尾一次。
        private val toolLedgersByItemId = linkedMapOf<String, ToolCallLedger>()
        private val toolLedgersByCallId = linkedMapOf<String, ToolCallLedger>()
        private val toolLedgersByOutputIndex = mutableMapOf<Int, ToolCallLedger>()
        val reasoningMetadata = mutableMapOf<String, JsonObject>()
        private val openTextIds = linkedSetOf<String>()
        private val openReasoningIds = linkedSetOf<String>()
        private val openImageIds = linkedSetOf<String>()
        private val openServerToolIds = linkedSetOf<String>()
        var finished = false
            private set

        fun abort() {
            finished = true
        }

        fun startText(id: String) = if (openTextIds.add(id)) listOf(StreamChunk.TextStart(id)) else emptyList()
        fun textDelta(id: String, text: String) = startText(id) + StreamChunk.TextDelta(id, text)
        fun endText(id: String) = if (openTextIds.remove(id)) listOf(StreamChunk.TextEnd(id)) else emptyList()
        fun startReasoning(id: String, metadata: JsonObject?, reasoningType: ReasoningType) =
            if (openReasoningIds.add(id)) {
                listOf(StreamChunk.ReasoningStart(id, metadata, reasoningType))
            } else {
                emptyList()
            }
        fun reasoningDelta(
            id: String,
            text: String,
            metadata: JsonObject?,
            reasoningType: ReasoningType,
        ) = startReasoning(id, metadata, reasoningType) +
            StreamChunk.ReasoningDelta(id, text, metadata, reasoningType)
        fun endReasoning(id: String, metadata: JsonObject?) =
            if (openReasoningIds.remove(id)) listOf(StreamChunk.ReasoningEnd(id, metadata)) else emptyList()

        fun endReasoningItem(itemId: String, metadata: JsonObject?): List<StreamChunk> {
            reasoningMetadata.remove(itemId)
            val ids = openReasoningIds.filter { it.startsWith("$itemId:reasoning:") }
            if (ids.isNotEmpty()) return ids.flatMap { endReasoning(it, metadata) }

            // encrypted_content 可以在 summary 为空时单独出现，仍需物化 metadata-only reasoning part。
            val id = "$itemId:reasoning:metadata:0"
            return startReasoning(id, metadata, ReasoningType.REASONING_TEXT) + endReasoning(id, metadata)
        }

        fun startImage(id: String) = if (openImageIds.add(id)) listOf(StreamChunk.ImageStart(id)) else emptyList()
        fun endImage(id: String) = if (openImageIds.remove(id)) listOf(StreamChunk.ImageEnd(id)) else emptyList()

        /**
         * output_item.added 的 function_call：登记 initial 快照但不发射
         * （首个非空 delta 会取代它）。item.id 规范上可选，缺失时以 call_id
         * 为账本身份；delta 先到的账本在这里补绑真实 item.id 与名字，
         * 不重发 Start、不回填已被 delta 接管的参数。
         */
        fun addFunctionCallItem(
            itemId: String?,
            wireCallId: String?,
            outputIndex: Int?,
            name: String,
            initialArguments: String,
        ): List<StreamChunk> = buildList {
            val ledger = resolveToolLedger(itemId, wireCallId, outputIndex, "output_item.added")
            if (ledger.name.isEmpty() && name.isNotEmpty()) {
                ledger.name = name
                if (ledger.started) add(StreamChunk.ToolCallDelta(ledger.callId, toolNameDelta = name))
            }
            if (!ledger.deltaSeen && ledger.initialArguments.isEmpty()) {
                ledger.initialArguments = initialArguments
            }
            addAll(ensureToolStarted(ledger))
        }

        /** output_item.done 的 function_call：带 final arguments/name 的 done。 */
        fun completeFunctionCallItem(
            itemId: String?,
            wireCallId: String?,
            outputIndex: Int?,
            finalName: String?,
            finalArguments: String?,
        ): List<StreamChunk> = completeToolCall(itemId, wireCallId, outputIndex, finalName, finalArguments)

        /**
         * function_call_arguments.delta：raw delta 原样发射（不去重）。
         * 空 delta 不发射也不创建账本，但输入形态按原契约校验：空白
         * item_id、负 index 是非法输入；item_id/call_id 全缺且 index 未登记
         * 时无合法归属，同样拒绝。合法身份的 call-only/已登记 index 空
         * delta 是纯 no-op。
         */
        fun toolArgumentsDelta(itemId: String?, wireCallId: String?, outputIndex: Int?, delta: String): List<StreamChunk> {
            if (delta.isEmpty()) {
                requireValidToolIdentityShape(itemId, outputIndex, "function_call_arguments.delta")
                if (itemId == null && wireCallId == null &&
                    (outputIndex == null || outputIndex !in toolLedgersByOutputIndex)
                ) {
                    throw ToolStreamProtocolException(
                        code = "missing_item_id",
                        message = "function_call_arguments.delta requires a locatable tool call " +
                            "identity (item_id, call_id or a registered output_index)",
                    )
                }
                return emptyList()
            }
            val ledger = resolveToolLedger(itemId, wireCallId, outputIndex, "function_call_arguments.delta")
            if (ledger.doneSeen) throw ToolStreamProtocolException(
                code = "arguments_after_done",
                message = "received non-empty arguments delta after done for call '${ledger.callId}'",
            )
            return buildList {
                addAll(ensureToolStarted(ledger))
                // 首个非空 delta 接管 initial 快照：added 里的 arguments 从此不再参与发射
                ledger.deltaSeen = true
                ledger.initialArguments = ""
                ledger.emittedInput.append(delta)
                add(StreamChunk.ToolCallDelta(ledger.callId, inputDelta = delta))
            }
        }

        /**
         * arguments.done / output_item.done 统一收口：两种 done 任意先后、同值重发幂等。
         * 未发任何 delta 时发射最终快照一次（优先非空 final arguments，否则 initial）；
         * 已发 delta 时 final arguments 按解析后 JSON 结构与已发射输入比较（非字符串比较），
         * 一致/缺失 no-op，冲突是协议错误。
         */
        fun completeToolCall(
            itemId: String?,
            wireCallId: String?,
            outputIndex: Int?,
            finalName: String?,
            finalArguments: String?,
        ): List<StreamChunk> {
            val ledger = resolveToolLedger(itemId, wireCallId, outputIndex, "function_call done")
            if (ledger.doneSeen) {
                if (finalArguments != null && !argumentsEquivalent(ledger.emittedInput.toString(), finalArguments)) {
                    throw ToolStreamProtocolException(
                        code = "conflicting_arguments",
                        message = "repeated done for call '${ledger.callId}' carries different arguments",
                    )
                }
                return emptyList()
            }
            ledger.doneSeen = true
            return buildList {
                if (ledger.name.isEmpty() && !finalName.isNullOrEmpty()) {
                    ledger.name = finalName
                }
                addAll(ensureToolStarted(ledger))
                val current = ledger.emittedInput.toString()
                if (ledger.deltaSeen) {
                    if (finalArguments != null && !argumentsEquivalent(current, finalArguments)) {
                        throw ToolStreamProtocolException(
                            code = "conflicting_arguments",
                            message = "done arguments for call '${ledger.callId}' conflict with " +
                                "already streamed deltas",
                        )
                    }
                } else {
                    // 无 delta：择非空 final 快照否则 initial，只发一次
                    val snapshot = finalArguments?.takeIf { it.isNotEmpty() }
                        ?: ledger.initialArguments.takeIf { it.isNotEmpty() }
                    if (snapshot != null) {
                        ledger.emittedInput.setLength(0)
                        ledger.emittedInput.append(snapshot)
                        add(StreamChunk.ToolCallDelta(ledger.callId, inputDelta = snapshot))
                    }
                }
                ledger.ended = true
                add(StreamChunk.ToolCallEnd(ledger.callId))
            }
        }

        /** 空白 item_id 与负 output_index 是非法输入，路由前直接拒绝。 */
        private fun requireValidToolIdentityShape(itemId: String?, outputIndex: Int?, event: String) {
            if (itemId != null && itemId.isBlank()) throw ToolStreamProtocolException(
                code = "missing_item_id",
                message = "$event requires a non-blank item id",
            )
            if (outputIndex != null && outputIndex < 0) throw ToolStreamProtocolException(
                code = "invalid_output_index",
                message = "$event carries negative output_index $outputIndex",
            )
        }

        /**
         * 三条身份线索（item_id / call_id / output_index）解析到同一账本：
         * - 空白 item_id 与负 output_index 是非法输入，直接拒绝；
         * - 任意两条已登记线索指向不同账本、或新线索与已解析账本的既有身份
         *   冲突（不同 call_id、不同真实 item_id、不同已绑 index），一律
         *   fail-closed——不同调用绝不能因线索碰撞被合并；
         * - 真实 item_id 仅在账本尚无（call-only 先建）时补绑；已登记的非空
         *   call_id 不可变更，旧规范的 fallback callId（=itemId）被迟到真
         *   call_id 顶替仍按冲突拒绝；
         * - 未登记的 output_index 只是归属声明：绑定到本次解析出的账本，
         *   但绝不能单凭它创建调用——item_id 与 call_id 全缺时拒绝；
         * - 新账本的 wire 身份（call_id，缺省回退 item_id）必须独占。
         *   已定位账本的事件省略 call_id 不代表重新声明 fallback：item 与
         *   call 是不同命名空间，保留已绑定的 wire id。
         */
        private fun resolveToolLedger(
            itemId: String?,
            wireCallId: String?,
            outputIndex: Int?,
            event: String,
        ): ToolCallLedger {
            requireValidToolIdentityShape(itemId, outputIndex, event)
            val byItem = itemId?.let { toolLedgersByItemId[it] }
            val byCall = wireCallId?.let { toolLedgersByCallId[it] }
            val byIndex = outputIndex?.let { toolLedgersByOutputIndex[it] }
            val resolved = byItem ?: byCall ?: byIndex
            if (resolved == null) {
                // 无已登记线索：只有 item_id 或 call_id 能创建账本；纯 index 身份
                // 不构成可定位的调用，缺身份事件不得瞎建工具。
                if (itemId == null && wireCallId == null) throw ToolStreamProtocolException(
                    code = "missing_item_id",
                    message = "$event requires a locatable tool call identity " +
                        "(item_id, call_id or a registered output_index)",
                )
                // 创建前的占用检查必须针对有效 callId（call_id，否则回退 item_id）：
                // 同一字符串被另一账本占用为真实 call_id 或 item fallback 时，
                // 两个不同调用会共享同一 wire 调用身份，保持原冲突拒绝。
                val callId = wireCallId ?: itemId!!
                toolLedgersByCallId[callId]?.let { other ->
                    throw ToolStreamProtocolException(
                        code = "conflicting_call_id",
                        message = "call_id '$callId' already bound to item '${other.itemId}', " +
                            "cannot merge item '${itemId ?: callId}'",
                    )
                }
                return ToolCallLedger(itemId = itemId, callId = callId, outputIndex = outputIndex).also {
                    if (itemId != null) toolLedgersByItemId[itemId] = it
                    toolLedgersByCallId[callId] = it
                    if (outputIndex != null) toolLedgersByOutputIndex[outputIndex] = it
                }
            }
            if ((byItem != null && byItem !== resolved) ||
                (byCall != null && byCall !== resolved) ||
                (byIndex != null && byIndex !== resolved)
            ) {
                throw ToolStreamProtocolException(
                    code = "conflicting_item_identity",
                    message = "$event identity clues point to different tool calls " +
                        "(item_id '$itemId', call_id '$wireCallId', output_index $outputIndex)",
                )
            }
            resolved.itemId?.let { boundItem ->
                // 两个不同真实 item 企图共享同一 call 身份：与创建期占用检查
                // 同语义，保持原 conflicting_call_id 分类。
                if (itemId != null && boundItem != itemId) throw ToolStreamProtocolException(
                    code = "conflicting_call_id",
                    message = "call '${resolved.callId}' already bound to item '$boundItem', " +
                        "cannot merge item '$itemId'",
                )
            }
            // 迟到的真实 call_id 与已登记身份不同（包括顶替掉 fallback 的 itemId）
            // 是身份冲突，明确拒绝而不是混合执行。
            if (wireCallId != null && wireCallId != resolved.callId) throw ToolStreamProtocolException(
                code = "conflicting_call_id",
                message = "tool call resolved to call_id '${resolved.callId}', " +
                    "but $event carries '$wireCallId'",
            )
            resolved.outputIndex?.let { boundIndex ->
                if (outputIndex != null && boundIndex != outputIndex) throw ToolStreamProtocolException(
                    code = "conflicting_output_index",
                    message = "call '${resolved.callId}' already bound to output_index $boundIndex, " +
                        "cannot be re-targeted by index $outputIndex",
                )
            }
            // 补绑：真实 item_id 只在账本还没有时登记；index 声明同理绑定到
            // 已解析账本（此时该 index 必然未登记给其他调用）。
            if (itemId != null && resolved.itemId == null) {
                resolved.itemId = itemId
                toolLedgersByItemId[itemId] = resolved
            }
            if (outputIndex != null && resolved.outputIndex == null) {
                resolved.outputIndex = outputIndex
                toolLedgersByOutputIndex[outputIndex] = resolved
            }
            return resolved
        }

        private fun ensureToolStarted(ledger: ToolCallLedger): List<StreamChunk> =
            if (!ledger.started) {
                ledger.started = true
                listOf(StreamChunk.ToolCallStart(ledger.callId, ledger.name))
            } else {
                emptyList()
            }

        fun startServerTool(tool: me.rerere.ai.ui.UIMessagePart.ServerTool): List<StreamChunk> =
            startServerTool(tool.toolCallId, tool.toolName, tool.input, tool.metadata)

        fun startServerTool(
            id: String,
            toolName: String,
            input: kotlinx.serialization.json.JsonElement? = null,
            metadata: JsonObject? = null,
        ): List<StreamChunk> = if (openServerToolIds.add(id)) {
            listOf(StreamChunk.ServerToolStart(id, toolName, input, metadata))
        } else if (input != null || metadata != null) {
            listOf(StreamChunk.ServerToolStart(id, toolName, input, metadata))
        } else emptyList()

        fun endServerTool(
            id: String,
            input: kotlinx.serialization.json.JsonElement? = null,
            output: kotlinx.serialization.json.JsonElement? = null,
            status: ServerToolStatus,
            metadata: JsonObject? = null,
        ): List<StreamChunk> {
            openServerToolIds.remove(id)
            return listOf(StreamChunk.ServerToolEnd(id, input, output, status, metadata))
        }

        fun finish(
            finishReason: String? = null,
            responseId: String? = null,
            model: String? = null,
        ): List<StreamChunk> {
            if (finished) return emptyList()
            finished = true
            return buildList {
                openTextIds.toList().forEach { addAll(endText(it)) }
                reasoningMetadata.toMap().forEach { (itemId, metadata) ->
                    addAll(endReasoningItem(itemId, metadata))
                }
                openReasoningIds.toList().forEach { addAll(endReasoning(it, null)) }
                openImageIds.toList().forEach { addAll(endImage(it)) }
                // 正常结束时收尾未 done 的调用：只有 initial 快照的调用一次性 flush 一次；
                // 异常/取消路径不经过这里（abort() / accept 抛错后 onClosed 不会被调用），
                // 不会把半成品调用 flush 成成功。
                // 遍历 call_id 别名表（唯一全量账本集合）：call-only 账本没有
                // item_id 也必须收尾，且每个账本只发一次 End。
                toolLedgersByCallId.values.toList().forEach { ledger ->
                    if (!ledger.ended) {
                        addAll(ensureToolStarted(ledger))
                        if (!ledger.deltaSeen && ledger.initialArguments.isNotEmpty()) {
                            ledger.emittedInput.setLength(0)
                            ledger.emittedInput.append(ledger.initialArguments)
                            add(StreamChunk.ToolCallDelta(
                                ledger.callId,
                                inputDelta = ledger.initialArguments,
                            ))
                        }
                        ledger.ended = true
                        add(StreamChunk.ToolCallEnd(ledger.callId))
                    }
                }
                openServerToolIds.clear()
                add(StreamChunk.Finish(finishReason, responseId, model))
            }
        }

        private fun argumentsEquivalent(emitted: String, done: String): Boolean {
            if (emitted == done) return true
            val emittedJson = runCatching { json.parseToJsonElement(emitted) }.getOrNull()
            val doneJson = runCatching { json.parseToJsonElement(done) }.getOrNull()
            // 两边都能解析成 JSON 时按结构比较（忽略格式差异）；
            // 任一侧解析失败时退化为字符串比较，不臆造等价。
            return emittedJson != null && doneJson != null && emittedJson == doneJson
        }
    }

    /** 一个 function_call 的生命周期账本；身份和参数状态都保留到响应结束。 */
    private class ToolCallLedger(
        /** wire item_id；call-only 输入先建账本时为 null，真实 id 到达后补绑 */
        var itemId: String?,
        /** wire call_id 非空白时用之，否则回退到 itemId；登记后不可变 */
        val callId: String,
        /** wire output_index；仅作归属线索登记，不作创建依据 */
        var outputIndex: Int? = null,
        var name: String = "",
        var initialArguments: String = "",
        var started: Boolean = false,
        var deltaSeen: Boolean = false,
        var doneSeen: Boolean = false,
        var ended: Boolean = false,
        val emittedInput: StringBuilder = StringBuilder(),
    )
}
