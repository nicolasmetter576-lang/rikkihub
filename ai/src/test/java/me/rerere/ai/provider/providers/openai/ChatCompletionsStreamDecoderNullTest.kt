package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Some OpenAI-compatible relays send `data: null` lines or null elements inside
 * reasoning_details / annotations / tool_calls; they must be skipped instead of
 * aborting the whole reply with "JsonNull is not a JsonObject".
 */
class ChatCompletionsStreamDecoderNullTest {
    @Test
    fun `explicit null fields from compatible relays do not break decoding`() {
        val decoder = ChatCompletionsStreamDecoder()
        val lines = listOf(
            """{"id":"r1","choices":[{"index":0,"delta":{"content":null,"reasoning_content":"We"},"finish_reason":null}],"usage":null}""",
            "null",
            """{"id":"r1","choices":[{"index":0,"delta":{"content":null,"reasoning_content":" need","reasoning_details":[null]},"finish_reason":null}],"usage":null}""",
            """{"id":"r1","choices":[{"index":0,"delta":{"content":"Hi","annotations":[null,{"type":"url_citation","url_citation":null},{"type":"url_citation","url_citation":{"title":"t","url":"https://a.example"}}]},"finish_reason":null}],"usage":null}""",
        )
        val chunks = lines.flatMap { decoder.accept(SseEvent(data = it)).chunks }

        assertEquals(
            "We need",
            chunks.filterIsInstance<StreamChunk.ReasoningDelta>().joinToString("") { it.text },
        )
        assertEquals("Hi", chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
        assertEquals(1, chunks.filterIsInstance<StreamChunk.Annotations>().single().annotations.size)
    }

    @Test
    fun `reasoning_details holding only nulls does not open an empty reasoning block`() {
        val decoder = ChatCompletionsStreamDecoder()
        val chunks = listOf(
            """{"id":"r1","choices":[{"index":0,"delta":{"content":null,"reasoning_details":[null]},"finish_reason":null}]}""",
            """{"id":"r1","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}""",
        ).flatMap { decoder.accept(SseEvent(data = it)).chunks }

        assertEquals(emptyList<StreamChunk>(), chunks.filter { it is StreamChunk.ReasoningStart || it is StreamChunk.ReasoningDelta })
        assertEquals("Hi", chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
    }

    @Test
    fun `non-streaming message skips null annotations and tool calls`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val parseMessage = ChatCompletionsAPI::class.java.getDeclaredMethod("parseMessage", JsonObject::class.java)
            .apply { isAccessible = true }
        val message = Json.parseToJsonElement(
            """
            {"role":"assistant","content":"Hi",
             "tool_calls":[null,{"id":"call_1","type":"function","function":{"name":"search","arguments":"{}"}}],
             "annotations":[null,{"type":"url_citation","url_citation":null},{"type":"url_citation","url_citation":{"title":"t","url":"https://a.example"}}]}
            """.trimIndent()
        ).jsonObject

        val parsed = parseMessage.invoke(api, message) as UIMessage

        assertEquals(listOf("call_1"), parsed.parts.filterIsInstance<UIMessagePart.Tool>().map { it.toolCallId })
        assertEquals("Hi", parsed.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals(1, parsed.annotations.size)
    }
}
