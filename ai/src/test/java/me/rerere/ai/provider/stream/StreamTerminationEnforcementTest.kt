package me.rerere.ai.provider.stream

import java.io.IOException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.antigravity.AntigravityChatApi
import me.rerere.ai.provider.providers.antigravity.AntigravityStreamRequest
import me.rerere.ai.provider.providers.antigravity.unwrapAntigravityEvent
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.claude.ClaudeStreamDecoder
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.google.GoogleStreamDecoder
import me.rerere.ai.provider.providers.openai.ChatCompletionsStreamDecoder
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.provider.providers.openai.ResponseApiStreamDecoder
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.UpstreamStreamRateLimitException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 协议流终止校验与传输层 onClosed 异常传播回归测试（问题 02）。
 */
class StreamTerminationEnforcementTest {

    // ---- OpenAI Chat Completions ----

    @Test
    fun `chat completions EOF and DONE without finish_reason throw IOException without flushing tools or finish`() {
        val cases = listOf(
            "text" to """{"id":"c1","choices":[{"index":0,"delta":{"content":"partial"}}]}""",
            "tool" to """{"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read","arguments":"{\"path\":\"a"}}]}}]}""",
        )
        for ((name, frame) in cases) {
            for (viaDone in listOf(false, true)) {
                val decoder = ChatCompletionsStreamDecoder(knownToolNames = setOf("read"))
                val initial = decoder.accept(SseEvent(data = frame)).chunks
                assertTrue("$name initial should not emit ToolCallEnd/Finish", initial.none { it is StreamChunk.ToolCallEnd || it is StreamChunk.Finish })

                assertThrows("$name viaDone=$viaDone", IOException::class.java) {
                    if (viaDone) decoder.accept(SseEvent(data = "[DONE]")) else decoder.onClosed()
                }
                // 失败后二次 onClosed 必须幂等返回空，不再补发 ToolCallEnd / Finish
                assertTrue("$name viaDone=$viaDone repeat onClosed", decoder.onClosed().isEmpty())
            }
        }
    }

    @Test
    fun `chat completions blank finish_reason does not count as terminal or overwrite real finish_reason`() {
        for (blank in listOf("", "   ")) {
            val decoder = ChatCompletionsStreamDecoder()
            decoder.accept(SseEvent(data = """{"id":"c1","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"$blank"}]}"""))
            assertThrows(IOException::class.java) { decoder.onClosed() }
        }

        val decoder = ChatCompletionsStreamDecoder()
        decoder.accept(SseEvent(data = """{"id":"c1","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}"""))
        // 后续 usage chunk 携带空白 finish_reason，不能抹掉真实的 "stop"
        val usageChunks = decoder.accept(
            SseEvent(data = """{"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":""}],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}"""),
        ).chunks
        assertTrue(usageChunks.any { it is StreamChunk.Usage })
        val closed = decoder.onClosed()
        val finish = closed.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals("stop", finish.finishReason)
    }

    @Test
    fun `chat completions without DONE completes when finish_reason and trailing usage arrive`() {
        val decoder = ChatCompletionsStreamDecoder()
        decoder.accept(SseEvent(data = """{"id":"c1","model":"gpt-test","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        val usageResult = decoder.accept(
            SseEvent(data = """{"id":"c1","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""),
        )
        assertFalse(usageResult.completed)
        assertEquals(1, usageResult.chunks.filterIsInstance<StreamChunk.Usage>().size)

        val closed = decoder.onClosed()
        val finish = closed.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals("stop", finish.finishReason)
        assertEquals("gpt-test", finish.model)
    }

    @Test
    fun `chat completions multi-line and whitespace DONE and null error frame are handled cleanly`() {
        val decoder = ChatCompletionsStreamDecoder()
        val combined = decoder.accept(
            SseEvent(
                data = """
                    {"id":"c1","error":null,"choices":[{"index":0,"delta":{"content":"hello"},"finish_reason":"stop"}]}
                    [DONE]
                """.trimIndent(),
            ),
        )
        assertTrue(combined.completed)
        assertEquals("stop", combined.chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
        assertTrue(decoder.onClosed().isEmpty())

        val wsDone = ChatCompletionsStreamDecoder()
        wsDone.accept(SseEvent(data = """{"id":"c2","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        val doneRes = wsDone.accept(SseEvent(data = "  [DONE]  "))
        assertTrue(doneRes.completed)
        assertEquals("stop", doneRes.chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
    }

    @Test
    fun `chat completions error in accept aborts decoder so subsequent onClosed does not flush`() {
        val decoder = ChatCompletionsStreamDecoder()
        decoder.accept(SseEvent(data = """{"id":"c1","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        assertThrows(HttpException::class.java) {
            decoder.accept(SseEvent(data = """{"error":{"message":"upstream boom"}}"""))
        }
        assertTrue(decoder.onClosed().isEmpty())
    }

    // ---- OpenAI Responses API ----

    @Test
    fun `responses EOF and DONE without terminal event throw IOException without flushing tools or finish`() {
        val textEvent = SseEvent(
            event = "response.output_text.delta",
            data = """{"type":"response.output_text.delta","item_id":"m1","delta":"partial"}""",
        )
        val toolEvent = SseEvent(
            event = "response.output_item.added",
            data = """{"type":"response.output_item.added","output_index":0,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"read","arguments":"{\"p\":1}"}}""",
        )
        for ((name, event) in listOf("text" to textEvent, "tool" to toolEvent)) {
            for (viaDone in listOf(false, true)) {
                val decoder = ResponseApiStreamDecoder()
                val initial = decoder.accept(event).chunks
                assertTrue(initial.none { it is StreamChunk.ToolCallEnd || it is StreamChunk.Finish })

                assertThrows("$name viaDone=$viaDone", IOException::class.java) {
                    if (viaDone) decoder.accept(SseEvent(data = " [DONE] ")) else decoder.onClosed()
                }
                assertTrue("$name viaDone=$viaDone repeat onClosed", decoder.onClosed().isEmpty())
            }
        }
    }

    @Test
    fun `responses completed flag follows decoder state not mismatched SSE event header`() {
        val decoder = ResponseApiStreamDecoder()
        val result = decoder.accept(
            SseEvent(
                event = "response.completed",
                data = """{"type":"response.output_text.delta","item_id":"m1","delta":"hi"}""",
            ),
        )
        assertFalse("mismatched SSE event header must not mark stream completed", result.completed)
        assertThrows(IOException::class.java) { decoder.onClosed() }
    }

    @Test
    fun `responses cancelled event fails with non-retryable HttpException instead of EOF IOException`() {
        val nullError = ResponseApiStreamDecoder()
        val ex1 = assertThrows(HttpException::class.java) {
            nullError.accept(
                SseEvent(
                    event = "response.cancelled",
                    data = """{"type":"response.cancelled","response":{"id":"r1","status":"cancelled","error":null}}""",
                ),
            )
        }
        assertNotEquals("null", ex1.message)
        assertTrue(nullError.onClosed().isEmpty())

        val withError = ResponseApiStreamDecoder()
        val ex2 = assertThrows(HttpException::class.java) {
            withError.accept(
                SseEvent(
                    event = "response.cancelled",
                    data = """{"type":"response.cancelled","response":{"id":"r2","status":"cancelled","error":{"message":"cancelled by server"}}}""",
                ),
            )
        }
        assertEquals("cancelled by server", ex2.message)
        assertTrue(withError.onClosed().isEmpty())
    }

    @Test
    fun `responses completed remains idempotent across trailing DONE and onClosed`() {
        val decoder = ResponseApiStreamDecoder()
        val completed = decoder.accept(
            SseEvent(
                event = "response.completed",
                data = """{"type":"response.completed","response":{"id":"r1","model":"gpt-5","status":"completed"}}""",
            ),
        )
        assertTrue(completed.completed)
        assertEquals("completed", completed.chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
        val trailingDone = decoder.accept(SseEvent(data = "[DONE]"))
        assertTrue(trailingDone.completed)
        assertTrue(trailingDone.chunks.isEmpty())
        assertTrue(decoder.onClosed().isEmpty())
    }

    // ---- Claude Messages API ----

    @Test
    fun `claude truncated EOF and DONE throw IOException without flushing tools or finish`() {
        val cases = listOf(
            "mid-text" to listOf(
                SseEvent(event = "content_block_start", data = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""),
                SseEvent(event = "content_block_delta", data = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"half"}}"""),
            ),
            "mid-tool" to listOf(
                SseEvent(event = "content_block_start", data = """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_1","name":"write","input":{"path":"a.txt"}}}"""),
            ),
            "between-blocks" to listOf(
                SseEvent(event = "content_block_start", data = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""),
                SseEvent(event = "content_block_stop", data = """{"type":"content_block_stop","index":0}"""),
            ),
            "empty-stream" to emptyList(),
        )
        for ((name, events) in cases) {
            for (viaDone in listOf(false, true)) {
                val decoder = ClaudeStreamDecoder()
                val initial = events.flatMap { decoder.accept(it).chunks }
                assertTrue(initial.none { it is StreamChunk.Finish })
                assertThrows("$name viaDone=$viaDone", IOException::class.java) {
                    if (viaDone) decoder.accept(SseEvent(data = "[DONE]")) else decoder.onClosed()
                }
                assertTrue("$name viaDone=$viaDone repeat onClosed", decoder.onClosed().isEmpty())
            }
        }
    }

    @Test
    fun `claude 4B completes at EOF or DONE when all blocks stopped and stop_reason received`() {
        for (viaDone in listOf(false, true)) {
            val decoder = ClaudeStreamDecoder()
            decoder.accept(SseEvent(event = "content_block_start", data = """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_1","name":"read","input":{}}}"""))
            decoder.accept(SseEvent(event = "content_block_delta", data = """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"p\":1}"}}"""))
            decoder.accept(SseEvent(event = "content_block_stop", data = """{"type":"content_block_stop","index":0}"""))
            decoder.accept(SseEvent(event = "message_delta", data = """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""))

            val tail = if (viaDone) decoder.accept(SseEvent(data = " [DONE] ")).chunks else decoder.onClosed()
            assertEquals("tool_use", tail.filterIsInstance<StreamChunk.Finish>().single().finishReason)
            assertTrue(decoder.onClosed().isEmpty())
        }

        // 若仍有未关闭 block，即使收到了 stop_reason，缺 message_stop 仍必须失败
        val openBlock = ClaudeStreamDecoder()
        openBlock.accept(SseEvent(event = "content_block_start", data = """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_1","name":"read","input":{"p":1}}}"""))
        openBlock.accept(SseEvent(event = "message_delta", data = """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""))
        assertThrows(IOException::class.java) { openBlock.onClosed() }
    }

    @Test
    fun `claude decodes via payload type when SSE event header is absent or generic`() {
        for (header in listOf(null, "", "message", "data")) {
            val decoder = ClaudeStreamDecoder()
            val chunks = mutableListOf<StreamChunk>()
            chunks += decoder.accept(SseEvent(event = header, data = """{"type":"message_start","message":{"id":"m1","model":"claude-test"}}""")).chunks
            chunks += decoder.accept(SseEvent(event = header, data = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")).chunks
            chunks += decoder.accept(SseEvent(event = header, data = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""")).chunks
            chunks += decoder.accept(SseEvent(event = header, data = """{"type":"content_block_stop","index":0}""")).chunks
            chunks += decoder.accept(SseEvent(event = header, data = """{"type":"message_delta","delta":{"stop_reason":"   "}}""")).chunks
            chunks += decoder.accept(SseEvent(event = header, data = """{"type":"message_delta","delta":null}""")).chunks
            chunks += decoder.accept(SseEvent(event = header, data = """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")).chunks
            val stop = decoder.accept(SseEvent(event = header, data = """{"type":"message_stop"}"""))
            assertTrue("header=$header should complete at message_stop", stop.completed)
            chunks += stop.chunks
            assertEquals("ok", chunks.filterIsInstance<StreamChunk.TextDelta>().single().text)
            assertEquals("end_turn", chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
            // OpenRouter 风格尾部 [DONE] 幂等
            assertTrue(decoder.accept(SseEvent(event = "data", data = "[DONE]")).chunks.isEmpty())
            assertTrue(decoder.onClosed().isEmpty())
        }
    }

    // ---- Google Gemini (vertexAI = false) & Antigravity ----

    @Test
    fun `google non-vertex EOF and DONE without valid finishReason throw IOException with Google label`() {
        val framesByCase = listOf(
            "text" to """{"candidates":[{"content":{"role":"model","parts":[{"text":"half"}]}}]}""",
            "tool" to """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"c1","name":"read","args":{"p":1}}}]}}]}""",
            "blank-reason" to """{"candidates":[{"content":{"role":"model","parts":[{"text":"half"}]},"finishReason":"  "}]}""",
            "unspecified-reason" to """{"candidates":[{"content":{"role":"model","parts":[{"text":"half"}]},"finishReason":"FINISH_REASON_UNSPECIFIED"}]}""",
        )
        for ((name, frame) in framesByCase) {
            for (viaDone in listOf(false, true)) {
                val decoder = GoogleStreamDecoder("resp-1", "gemini-test", vertexAI = false)
                val initial = decoder.accept(SseEvent(data = frame)).chunks
                assertTrue(initial.none { it is StreamChunk.ToolCallEnd || it is StreamChunk.Finish })

                val error = assertThrows("$name viaDone=$viaDone", IOException::class.java) {
                    if (viaDone) decoder.accept(SseEvent(data = " [DONE] ")) else decoder.onClosed()
                }
                assertTrue("error message should label Google: ${error.message}", error.message!!.contains("Google"))
                assertFalse("error message should not label Vertex: ${error.message}", error.message!!.contains("Vertex"))
                assertTrue(decoder.onClosed().isEmpty())
            }
        }
    }

    @Test
    fun `google non-vertex handles in-stream error frames with redaction and tolerates null error`() {
        val rateLimit = GoogleStreamDecoder("resp-1", "gemini-test", vertexAI = false)
        val rlEx = assertThrows(UpstreamStreamRateLimitException::class.java) {
            rateLimit.accept(SseEvent(data = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Bearer synthetic-secret"}}"""))
        }
        assertEquals(429, rlEx.statusCode)
        assertTrue(rlEx.message!!.contains("Google"))
        assertFalse(rlEx.stackTraceToString().contains("synthetic-secret"))
        assertTrue(rateLimit.onClosed().isEmpty())

        val server500 = GoogleStreamDecoder("resp-1", "gemini-test", vertexAI = false)
        val s500Ex = assertThrows(IOException::class.java) {
            server500.accept(SseEvent(data = """{"error":{"code":500,"status":"INTERNAL","message":"synthetic-secret"}}"""))
        }
        assertFalse(s500Ex is UpstreamStreamRateLimitException)
        assertTrue(s500Ex.message!!.contains("Google"))
        assertFalse(s500Ex.stackTraceToString().contains("synthetic-secret"))

        val bad400 = GoogleStreamDecoder("resp-1", "gemini-test", vertexAI = false)
        val b400Ex = assertThrows(ToolStreamProtocolException::class.java) {
            bad400.accept(SseEvent(data = """{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"synthetic-secret"}}"""))
        }
        assertEquals("google_stream_upstream_error", b400Ex.code)
        assertTrue(b400Ex.message!!.contains("Google"))
        assertFalse(b400Ex.stackTraceToString().contains("synthetic-secret"))

        // 兼容中转："error": null、"candidates": null、"content": null、非 STOP 工具 finishReason 均正常放行
        val compat = GoogleStreamDecoder("resp-1", "gemini-test", vertexAI = false)
        compat.accept(SseEvent(data = """{"error":null,"candidates":null}"""))
        compat.accept(SseEvent(data = """{"candidates":[{"content":null}]}"""))
        compat.accept(SseEvent(data = """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"c1","name":"read","args":{"p":1}}}]},"finishReason":"TOOL_CALLS"}]}"""))
        val closed = compat.onClosed()
        assertEquals("TOOL_CALLS", closed.filterIsInstance<StreamChunk.Finish>().single().finishReason)
        assertEquals(1, closed.filterIsInstance<StreamChunk.ToolCallEnd>().size)
    }

    @Test
    fun `antigravity unwraps response-nested error into HttpException preserving status and retry delay`() {
        val wrappedError = """
            {"response":{"error":{"code":429,"message":"quota exceeded","status":"RESOURCE_EXHAUSTED","details":[
              {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"2.5s"}
            ]}}}
        """.trimIndent()
        try {
            unwrapAntigravityEvent(wrappedError, null)
            fail("expected HttpException for response-nested error")
        } catch (error: HttpException) {
            assertEquals(429, error.statusCode)
            assertEquals(2_500L, error.retryAfterMillis)
            assertTrue(error.message!!.contains("quota exceeded"))
        }
    }

    // ---- 真实 OkHttp SSE 传输层 onClosed 异常传播（不挂起） ----

    @Test
    fun `all SSE providers surface IOException promptly on truncated EOF without hanging`() = runBlocking {
        val model = Model(modelId = "test-model")
        val messages = listOf(UIMessage.user("hi"))
        val params = TextGenerationParams(model = model)

        val cases: List<Pair<String, suspend (OkHttpClient) -> Unit>> = listOf(
            "ChatCompletionsAPI" to { client ->
                OpenAIProvider(client).streamText(
                    ProviderSetting.OpenAI(baseUrl = "https://offline.invalid/v1", apiKey = "k", useResponseApi = false),
                    messages,
                    params,
                ).collect { chunk -> assertNoTerminalChunk("ChatCompletionsAPI", chunk) }
            },
            "ResponseAPI" to { client ->
                OpenAIProvider(client).streamText(
                    ProviderSetting.OpenAI(baseUrl = "https://offline.invalid/v1", apiKey = "k", useResponseApi = true),
                    messages,
                    params,
                ).collect { chunk -> assertNoTerminalChunk("ResponseAPI", chunk) }
            },
            "ClaudeProvider" to { client ->
                ClaudeProvider(client).streamText(
                    ProviderSetting.Claude(baseUrl = "https://offline.invalid/v1", apiKey = "k"),
                    messages,
                    params,
                ).collect { chunk -> assertNoTerminalChunk("ClaudeProvider", chunk) }
            },
            "GoogleProvider(non-Vertex)" to { client ->
                GoogleProvider(client).streamText(
                    ProviderSetting.Google(baseUrl = "https://offline.invalid/v1beta", apiKey = "k", vertexAI = false),
                    messages,
                    params,
                ).collect { chunk -> assertNoTerminalChunk("GoogleProvider(non-Vertex)", chunk) }
            },
            "AntigravityChatApi" to { client ->
                AntigravityChatApi(client, baseUrl = "https://offline.invalid").streamGenerate(
                    AntigravityStreamRequest(
                        accessToken = "token",
                        body = buildJsonObject { put("model", "gemini-3-flash") },
                        modelId = "gemini-3-flash",
                    ),
                ).collect { chunk -> assertNoTerminalChunk("AntigravityChatApi", chunk) }
            },
        )

        val sseBodies = mapOf(
            "ChatCompletionsAPI" to "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"p\\\":1\"}}]}}]}\n\n",
            "ResponseAPI" to "event: response.output_item.added\ndata: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"read\",\"arguments\":\"{\\\"p\\\":1}\"}}\n\n",
            "ClaudeProvider" to "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"read\",\"input\":{\"p\":1}}}\n\n",
            "GoogleProvider(non-Vertex)" to "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"id\":\"call_1\",\"name\":\"read\",\"args\":{\"p\":1}}}]}}]}\n\n",
            "AntigravityChatApi" to "data: {\"response\":{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"id\":\"call_1\",\"name\":\"read\",\"args\":{\"p\":1}}}]}}]}}\n\n",
        )

        for ((name, runStream) in cases) {
            val sseBody = sseBodies.getValue(name)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(sseBody.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }.build()
            try {
                val caught = try {
                    withTimeout(5_000) { runStream(client) }
                    null
                } catch (e: IOException) {
                    e
                }
                assertTrue("$name should fail with IOException on truncated EOF, got=$caught", caught is IOException)
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    private fun assertNoTerminalChunk(name: String, chunk: StreamChunk) {
        assertFalse("$name must not emit ToolCallEnd on truncated stream", chunk is StreamChunk.ToolCallEnd)
        assertFalse("$name must not emit Finish on truncated stream", chunk is StreamChunk.Finish)
    }
}
