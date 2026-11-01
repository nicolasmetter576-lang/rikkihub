package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseApiStreamDecoderTest {
    private val api = ResponseAPI(OkHttpClient())

    @Test
    fun `incomplete response should preserve terminal metadata and usage`() {
        val decoder = ResponseApiStreamDecoder()
        val result = decoder.accept(SseEvent(
            event = "response.incomplete",
            data = json.encodeToString(buildJsonObject {
                put("type", "response.incomplete")
                put("response", buildJsonObject {
                    put("id", "resp_incomplete")
                    put("model", "test-model")
                    put("status", "incomplete")
                    put("incomplete_details", buildJsonObject {
                        put("reason", "max_output_tokens")
                    })
                    put("usage", buildJsonObject {
                        put("input_tokens", 10)
                        put("output_tokens", 20)
                        put("total_tokens", 30)
                        put("input_tokens_details", buildJsonObject {
                            put("cached_tokens", 4)
                        })
                    })
                })
            }),
        ))

        assertTrue(result.completed)
        assertEquals(
            TokenUsage(promptTokens = 10, completionTokens = 20, cachedTokens = 4, totalTokens = 30),
            (result.chunks[0] as StreamChunk.Usage).usage,
        )
        assertEquals(
            StreamChunk.Finish(
                finishReason = "incomplete:max_output_tokens",
                responseId = "resp_incomplete",
                model = "test-model",
            ),
            result.chunks[1],
        )
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `failed response should terminate with provider error`() {
        val decoder = ResponseApiStreamDecoder()

        val exception = assertThrows(HttpException::class.java) {
            decoder.accept(SseEvent(
                event = "response.failed",
                data = json.encodeToString(buildJsonObject {
                    put("type", "response.failed")
                    put("response", buildJsonObject {
                        put("id", "resp_failed")
                        put("status", "failed")
                        put("error", buildJsonObject {
                            put("code", "server_error")
                            put("message", "Upstream generation failed")
                        })
                    })
                }),
            ))
        }

        assertEquals("Upstream generation failed", exception.message)
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `reasoning item without summary should preserve encrypted content`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(reasoningItemEvent("response.output_item.added")))
            addAll(decoder.decode(reasoningItemEvent("response.output_item.done", "encrypted")))
        }

        assertEquals(2, chunks.size)
        assertTrue(chunks[0] is StreamChunk.ReasoningStart)
        assertTrue(chunks[1] is StreamChunk.ReasoningEnd)

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val reasoning = messages.last().parts.single() as UIMessagePart.Reasoning

        assertEquals("", reasoning.reasoning)
        assertEquals("rs_test", reasoning.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
        assertEquals("encrypted", reasoning.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent)
        assertFalse(messages.last().isValidToUpload())

        // ResponseAPI must still replay metadata-only reasoning even though generic providers consider it empty.
        val reasoningItem = api.buildMessages(messages).last().jsonObject
        assertEquals("reasoning", reasoningItem["type"]?.jsonPrimitive?.content)
        assertEquals("rs_test", reasoningItem["id"]?.jsonPrimitive?.content)
        assertEquals("encrypted", reasoningItem["encrypted_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `reasoning item should keep final metadata after summary done event`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(reasoningItemEvent("response.output_item.added")))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_summary_text.delta")
                put("item_id", "rs_test")
                put("summary_index", 0)
                put("delta", "summary")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_summary_text.done")
                put("item_id", "rs_test")
                put("summary_index", 0)
            }))
            addAll(decoder.decode(reasoningItemEvent("response.output_item.done", "encrypted")))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val reasoningParts = messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>()

        assertEquals(1, reasoningParts.size)
        assertEquals("summary", reasoningParts.single().reasoning)
        assertEquals(
            "encrypted",
            reasoningParts.single().metadataAs<OpenAIReasoningMetadata>()?.encryptedContent,
        )
        assertEquals(ReasoningType.SUMMARY_TEXT, reasoningParts.single().reasoningType)
    }

    @Test
    fun `raw reasoning and summary with the same index should remain distinct without replaying plaintext`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(reasoningItemEvent("response.output_item.added")))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_text.delta")
                put("item_id", "rs_test")
                put("content_index", 0)
                put("delta", "raw")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_summary_text.delta")
                put("item_id", "rs_test")
                put("summary_index", 0)
                put("delta", "summary")
            }))
            addAll(decoder.decode(reasoningItemEvent("response.output_item.done", "encrypted")))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val reasoningParts = messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>()

        assertEquals(2, reasoningParts.size)
        assertEquals("raw", reasoningParts.single { it.reasoningType == ReasoningType.REASONING_TEXT }.reasoning)
        assertEquals("summary", reasoningParts.single { it.reasoningType == ReasoningType.SUMMARY_TEXT }.reasoning)

        val reasoningItem = api.buildMessages(messages).last().jsonObject
        assertEquals(
            "summary",
            reasoningItem["summary"]?.jsonArray?.single()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content,
        )
        assertEquals("encrypted", reasoningItem["encrypted_content"]?.jsonPrimitive?.content)
        assertFalse(reasoningItem.containsKey("content"))
    }

    @Test
    fun `web search events should produce one completed server tool`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.output_item.added")
                put("item", webSearchItem("in_progress"))
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.web_search_call.searching")
                put("item_id", "ws_1")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.web_search_call.completed")
                put("item_id", "ws_1")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.output_item.done")
                put("item", webSearchItem("completed"))
            }))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("search"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val tool = messages.last().parts.single() as UIMessagePart.ServerTool

        assertEquals("ws_1", tool.toolCallId)
        assertEquals("web_search", tool.toolName)
        assertEquals("search", tool.input?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertEquals(
            "web_search_call",
            tool.metadataAs<ServerToolMetadata>()?.call?.get("type")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `non streaming response should parse web search without synthetic output`() {
        val result = api.parseResponseOutput(buildJsonObject {
            put("id", "resp_1")
            put("model", "test-model")
            put("status", "completed")
            put("output", buildJsonArray { add(webSearchItem("completed")) })
        })

        val tool = result.message.parts.single() as UIMessagePart.ServerTool
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertEquals(null, tool.output)
        assertEquals("Kotlin", tool.input?.jsonObject?.get("query")?.jsonPrimitive?.content)
    }

    @Test
    fun `non streaming response should preserve metadata-only reasoning`() {
        val result = api.parseResponseOutput(buildJsonObject {
            put("id", "resp_1")
            put("model", "test-model")
            put("status", "completed")
            put("output", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning")
                    put("id", "rs_test")
                    put("encrypted_content", "encrypted")
                    put("summary", buildJsonArray {})
                    put("content", buildJsonArray {})
                })
            })
        })

        val reasoning = result.message.parts.single() as UIMessagePart.Reasoning
        assertEquals("", reasoning.reasoning)
        assertEquals("rs_test", reasoning.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
        assertEquals("encrypted", reasoning.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent)

        val reasoningItem = api.buildMessages(listOf(result.message)).single().jsonObject
        assertEquals("reasoning", reasoningItem["type"]?.jsonPrimitive?.content)
        assertEquals("rs_test", reasoningItem["id"]?.jsonPrimitive?.content)
        assertEquals("encrypted", reasoningItem["encrypted_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `non streaming response should distinguish raw reasoning and summary`() {
        val result = api.parseResponseOutput(buildJsonObject {
            put("id", "resp_1")
            put("model", "test-model")
            put("status", "completed")
            put("output", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning")
                    put("id", "rs_test")
                    put("encrypted_content", "encrypted")
                    put("summary", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "summary_text")
                            put("text", "summary")
                        })
                    })
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "reasoning_text")
                            put("text", "raw")
                        })
                    })
                })
            })
        })

        val reasoningParts = result.message.parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals(2, reasoningParts.size)
        assertEquals("raw", reasoningParts.single { it.reasoningType == ReasoningType.REASONING_TEXT }.reasoning)
        assertEquals("summary", reasoningParts.single { it.reasoningType == ReasoningType.SUMMARY_TEXT }.reasoning)
        reasoningParts.forEach {
            assertEquals("rs_test", it.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
            assertEquals("encrypted", it.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent)
        }
    }

    // ---------- function_call 参数生命周期与身份账本（A7） ----------

    @Test
    fun `initial arguments are replaced once deltas arrive`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = decodeAll(decoder, listOf(
            functionCallItem("response.output_item.added", "fc_1", "call_1", "search", "{\"a\":1}"),
            argsDelta("fc_1", "{\"b\":"),
            argsDelta("fc_1", "2}"),
            argsDone("fc_1", "{\"b\":2}"),
        ))

        // added 里的 initial 快照登记不发射，首个非空 delta 接管
        assertEquals(
            "{\"b\":2}",
            chunks.filterIsInstance<StreamChunk.ToolCallDelta>().joinToString("") { it.inputDelta },
        )
    }

    @Test
    fun `arguments done without deltas emits the final snapshot once`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = decodeAll(decoder, listOf(
            functionCallItem("response.output_item.added", "fc_1", "call_1", "search", ""),
            argsDone("fc_1", "{\"q\":\"Kotlin\"}"),
        ))

        assertEquals(
            listOf(StreamChunk.ToolCallStart("call_1", "search")),
            chunks.filterIsInstance<StreamChunk.ToolCallStart>(),
        )
        assertEquals(
            listOf("{\"q\":\"Kotlin\"}"),
            chunks.filterIsInstance<StreamChunk.ToolCallDelta>().map { it.inputDelta },
        )
        assertEquals(listOf("call_1"), chunks.filterIsInstance<StreamChunk.ToolCallEnd>().map { it.id })
    }

    @Test
    fun `output item done alone fills empty input from its final arguments`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = decodeAll(decoder, listOf(
            functionCallItem("response.output_item.added", "fc_1", "call_1", "search", ""),
            functionCallItem("response.output_item.done", "fc_1", "call_1", "search", "{\"q\":1}"),
        ))

        assertEquals(
            "{\"q\":1}",
            chunks.filterIsInstance<StreamChunk.ToolCallDelta>().single().inputDelta,
        )
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallEnd })
    }

    @Test
    fun `repeated done with same arguments is idempotent`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = decodeAll(decoder, listOf(
            functionCallItem("response.output_item.added", "fc_1", "call_1", "search", ""),
            argsDone("fc_1", "{\"q\":1}"),
            argsDone("fc_1", "{\"q\":1}"),
            functionCallItem("response.output_item.done", "fc_1", "call_1", "search", "{\"q\":1}"),
        ))

        // 账本保留至响应结束：重复 done 不会重新追加参数也不会重复 End
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallDelta })
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallEnd })
    }

    @Test
    fun `output item done before arguments done stays idempotent`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = decodeAll(decoder, listOf(
            functionCallItem("response.output_item.added", "fc_1", "call_1", "search", ""),
            argsDelta("fc_1", "{\"q\":1}"),
            functionCallItem("response.output_item.done", "fc_1", "call_1", "search", "{\"q\":1}"),
            argsDone("fc_1", "{\"q\":1}"),
        ))

        // 两种 done 任意先后同值幂等
        assertEquals("{\"q\":1}", chunks.filterIsInstance<StreamChunk.ToolCallDelta>()
            .joinToString("") { it.inputDelta })
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallEnd })
    }

    @Test
    fun `done arguments equivalent under different JSON formatting do not conflict`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = decodeAll(decoder, listOf(
            functionCallItem("response.output_item.added", "fc_1", "call_1", "search", ""),
            argsDelta("fc_1", "{\"q\":1,\"page\":2}"),
            argsDone("fc_1", "{ \"q\": 1, \"page\": 2 }"),
        ))

        // 解析后 JSON 结构一致即 no-op，不按字符串格式判冲突
        assertEquals(1, chunks.count { it is StreamChunk.ToolCallEnd })
    }

    @Test
    fun `done arguments conflicting with streamed deltas fail closed`() {
        val decoder = ResponseApiStreamDecoder()
        decoder.accept(SseEvent(data = json.encodeToString(
            functionCallItemPayload("response.output_item.added", "fc_1", "call_1", "search", ""))))
        decoder.accept(SseEvent(data = json.encodeToString(argsDelta("fc_1", "{\"q\":1}"))))

        val exception = assertThrows(ToolStreamProtocolException::class.java) {
            decoder.accept(SseEvent(data = json.encodeToString(argsDone("fc_1", "{\"q\":2}"))))
        }
        assertEquals("conflicting_arguments", exception.code)
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `non empty delta after done fails closed`() {
        val decoder = ResponseApiStreamDecoder()
        decoder.accept(SseEvent(data = json.encodeToString(
            functionCallItemPayload("response.output_item.added", "fc_1", "call_1", "search", ""))))
        decoder.accept(SseEvent(data = json.encodeToString(argsDone("fc_1", "{\"q\":1}"))))

        assertThrows(ToolStreamProtocolException::class.java) {
            decoder.accept(SseEvent(data = json.encodeToString(argsDelta("fc_1", "{\"q\":9}"))))
        }
    }

    @Test
    fun `initial only call flushes once at normal finish`() {
        val decoder = ResponseApiStreamDecoder()
        decoder.accept(SseEvent(data = json.encodeToString(
            functionCallItemPayload("response.output_item.added", "fc_1", "call_1", "search", "{\"q\":1}"))))

        val done = decoder.accept(SseEvent(data = json.encodeToString(buildJsonObject {
            put("type", "response.completed")
            put("response", buildJsonObject {
                put("id", "resp_1")
                put("model", "test-model")
                put("status", "completed")
            })
        })))

        // 无 delta 无 per-call done：initial 快照在 finish 时只发一次
        assertEquals(
            listOf("{\"q\":1}"),
            done.chunks.filterIsInstance<StreamChunk.ToolCallDelta>().map { it.inputDelta },
        )
        assertEquals(1, done.chunks.count { it is StreamChunk.ToolCallEnd })
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `blank call id falls back to the unique item id`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = decodeAll(decoder, listOf(
            functionCallItem("response.output_item.added", "fc_1", "  ", "search", ""),
            argsDelta("fc_1", "{\"q\":1}"),
            argsDone("fc_1", "{\"q\":1}"),
        ))

        assertEquals("fc_1", chunks.filterIsInstance<StreamChunk.ToolCallStart>().single().id)
        assertEquals("fc_1", chunks.filterIsInstance<StreamChunk.ToolCallEnd>().single().id)
    }

    @Test
    fun `duplicate real call id across items fails closed`() {
        val decoder = ResponseApiStreamDecoder()
        decoder.accept(SseEvent(data = json.encodeToString(
            functionCallItemPayload("response.output_item.added", "fc_1", "call_1", "a", ""))))

        assertThrows(ToolStreamProtocolException::class.java) {
            decoder.accept(SseEvent(data = json.encodeToString(
                functionCallItemPayload("response.output_item.added", "fc_2", "call_1", "b", ""))))
        }
    }

    @Test
    fun `late real call id replacing a fallback id fails closed`() {
        val decoder = ResponseApiStreamDecoder()
        decoder.accept(SseEvent(data = json.encodeToString(
            functionCallItemPayload("response.output_item.added", "fc_1", null, "a", ""))))

        // fallback 到 item.id 后，又出现不同的真实 call_id：明确拒绝而不是混合
        assertThrows(ToolStreamProtocolException::class.java) {
            decoder.accept(SseEvent(data = json.encodeToString(
                functionCallItemPayload("response.output_item.done", "fc_1", "call_real", "a", "{}"))))
        }
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `blank function call item id fails closed`() {
        val decoder = ResponseApiStreamDecoder()
        assertThrows(ToolStreamProtocolException::class.java) {
            decoder.accept(SseEvent(data = json.encodeToString(
                functionCallItemPayload("response.output_item.added", " ", "call_1", "a", ""))))
        }
    }

    @Test
    fun `protocol error emits no tool chunks for the failing event`() {
        val decoder = ResponseApiStreamDecoder()
        decoder.accept(SseEvent(data = json.encodeToString(
            functionCallItemPayload("response.output_item.added", "fc_1", "call_1", "a", ""))))
        decoder.accept(SseEvent(data = json.encodeToString(argsDone("fc_1", "{\"q\":1}"))))

        assertThrows(ToolStreamProtocolException::class.java) {
            decoder.accept(SseEvent(data = json.encodeToString(
                functionCallItemPayload("response.output_item.done", "fc_1", "call_other", "a", "{}"))))
        }
    }

    private fun decodeAll(decoder: ResponseApiStreamDecoder, payloads: List<kotlinx.serialization.json.JsonObject>): List<StreamChunk> =
        payloads.flatMap { decoder.accept(SseEvent(data = json.encodeToString(it))).chunks }

    private fun argsDelta(itemId: String, delta: String): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("type", "response.function_call_arguments.delta")
        put("item_id", itemId)
        put("delta", delta)
    }

    private fun argsDone(itemId: String, arguments: String): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("type", "response.function_call_arguments.done")
        put("item_id", itemId)
        put("arguments", arguments)
    }

    private fun functionCallItem(
        type: String,
        itemId: String,
        callId: String?,
        name: String,
        arguments: String,
    ): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("type", type)
        put("item", functionCallItemPayload(itemId, callId, name, arguments))
    }

    private fun functionCallItemPayload(
        type: String,
        itemId: String,
        callId: String?,
        name: String,
        arguments: String,
    ): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("type", type)
        put("item", functionCallItemPayload(itemId, callId, name, arguments))
    }

    private fun functionCallItemPayload(
        itemId: String,
        callId: String?,
        name: String,
        arguments: String,
    ): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("type", "function_call")
        put("id", itemId)
        callId?.let { put("call_id", it) }
        put("name", name)
        put("arguments", arguments)
    }

    private fun webSearchItem(status: String) = buildJsonObject {
        put("type", "web_search_call")
        put("id", "ws_1")
        put("status", status)
        put("action", buildJsonObject {
            put("type", "search")
            put("query", "Kotlin")
        })
    }

    private fun reasoningItemEvent(type: String, encryptedContent: String? = null) = buildJsonObject {
        put("type", type)
        put("item", buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_test")
            encryptedContent?.let { put("encrypted_content", it) }
        })
    }

    private fun ResponseApiStreamDecoder.decode(payload: kotlinx.serialization.json.JsonObject): List<StreamChunk> =
        accept(SseEvent(
            event = payload["type"]?.jsonPrimitive?.content,
            data = json.encodeToString(payload),
        )).chunks
}
