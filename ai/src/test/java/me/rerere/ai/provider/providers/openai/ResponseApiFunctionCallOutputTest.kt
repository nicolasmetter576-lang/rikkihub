package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 部分兼容网关会在服务端执行函数工具，并把结果作为 `function_call_output` 一起返回。
 * 解析时要把结果挂到对应工具上，否则客户端会把这个工具当成未执行而再执行一遍。
 */
class ResponseApiFunctionCallOutputTest {
    private val api = ResponseAPI(OkHttpClient())

    @Test
    fun `non streaming response attaches server executed function call output`() {
        val result = api.parseResponseOutput(buildJsonObject {
            put("id", "resp_1")
            put("model", "test-model")
            put("status", "completed")
            put("output", buildJsonArray {
                add(buildJsonObject {
                    put("type", "function_call")
                    put("id", "fc_1")
                    put("call_id", "call_1")
                    put("name", "search_web")
                    put("arguments", "{\"query\":\"Kotlin\"}")
                })
                add(buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", "call_1")
                    put("output", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", "result")
                        })
                    })
                })
            })
        })

        val tool = result.message.getTools().single()
        assertTrue("工具应已被标记为执行过", tool.isExecuted)
        assertEquals("result", tool.output.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `non streaming string output is used as is`() {
        val result = api.parseResponseOutput(buildJsonObject {
            put("id", "resp_1")
            put("model", "test-model")
            put("status", "completed")
            put("output", buildJsonArray {
                add(buildJsonObject {
                    put("type", "function_call")
                    put("id", "fc_1")
                    put("call_id", "call_1")
                    put("name", "search_web")
                    put("arguments", "{}")
                })
                add(buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", "call_1")
                    put("output", "plain text result")
                })
            })
        })

        val tool = result.message.getTools().single()
        assertEquals("plain text result", tool.output.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `streaming response attaches server executed function call output`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(functionCallItemEvent("response.output_item.added", arguments = "")))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.function_call_arguments.done")
                put("item_id", "fc_1")
                put("arguments", "{\"query\":\"Kotlin\"}")
            }))
            addAll(decoder.decode(functionCallItemEvent("response.output_item.done", arguments = "{\"query\":\"Kotlin\"}")))
            addAll(decoder.decode(functionCallOutputItemEvent("response.output_item.added")))
            addAll(decoder.decode(functionCallOutputItemEvent("response.output_item.done")))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("search"))) { acc, chunk -> handler.handle(acc, chunk) }
        val tool = messages.last().getTools().single()
        assertTrue("工具应已被标记为执行过", tool.isExecuted)
        assertEquals("result", tool.output.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    private fun functionCallItemEvent(type: String, arguments: String) = buildJsonObject {
        put("type", type)
        put("output_index", 0)
        put("item", buildJsonObject {
            put("type", "function_call")
            put("id", "fc_1")
            put("call_id", "call_1")
            put("name", "search_web")
            put("arguments", arguments)
        })
    }

    private fun functionCallOutputItemEvent(type: String) = buildJsonObject {
        put("type", type)
        put("output_index", 1)
        put("item", buildJsonObject {
            put("type", "function_call_output")
            put("id", "fco_1")
            put("call_id", "call_1")
            put("output", "result")
        })
    }

    private fun ResponseApiStreamDecoder.decode(payload: JsonObject): List<StreamChunk> =
        accept(
            SseEvent(
                event = payload["type"]?.jsonPrimitive?.content,
                data = json.encodeToString(payload),
            )
        ).chunks
}
