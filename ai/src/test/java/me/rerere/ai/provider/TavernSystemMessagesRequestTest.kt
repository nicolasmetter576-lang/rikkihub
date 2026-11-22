package me.rerere.ai.provider

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.providers.LEADING_USER_PLACEHOLDER
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.devin.buildDevinMessages
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 酒馆预设组装出的多条系统消息（squash_system_messages 关）在各协议上的去向，照酒馆的转换（prompt-converters.js）：
 * 开头连续的系统消息是系统提示词，后面的原位改成用户消息并入相邻的用户消息；ChatCompletions 原位保留。
 * 走各家公开入口和真实请求构建，传输层是假的。
 */
class TavernSystemMessagesRequestTest {
    /** 形状同《我即浪潮》：两段开头系统块、助手承诺回合、规则与 rikkihubContext、聊天、聊天之后的 jailbreak。 */
    private val tavern = listOf(
        UIMessage.system("main"),
        UIMessage.system("license"),
        UIMessage.assistant("commitment"),
        UIMessage.system("rules"),
        UIMessage.system("context"),
        UIMessage.user("hi"),
        UIMessage.assistant("reply"),
        UIMessage.user("next"),
        UIMessage.system("jailbreak"),
    )
    private val inOrder = listOf("commitment", "rules", "context", "hi", "reply", "next", "jailbreak")
    private val model = Model(modelId = "test-model")
    private val params = TextGenerationParams(model = model)

    private class Capture(private val response: String) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        override fun close() {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun Request.bodyJson(): JsonObject =
        json.parseToJsonElement(Buffer().also { requireNotNull(body).writeTo(it) }.readUtf8()).jsonObject

    /** 按出现顺序取出所有文字（`text` 字段，以及 Responses 单段文字时的字符串 `content`）。 */
    private fun texts(element: JsonElement?): List<String> = when (element) {
        is JsonObject -> element.flatMap { (key, value) ->
            if ((key == "text" || key == "content") && value is JsonPrimitive && value.isString) listOf(value.content) else texts(value)
        }
        is JsonArray -> element.flatMap { texts(it) }
        else -> emptyList()
    }

    private fun roles(array: JsonArray) = array.map { it.jsonObject["role"]!!.jsonPrimitive.content }

    private val chat = """{"id":"c","model":"test-model","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
    private val responses = """{"id":"r","model":"test-model","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}"""
    private val gemini = """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
    private val anthropic = """{"id":"m","model":"test-model","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""
    private val interaction = """{"id":"i","status":"completed","model":"test-model","steps":[{"type":"model_output","content":[{"type":"text","text":"ok"}]}]}"""

    private fun send(response: String, call: suspend (OkHttpClient) -> Unit): JsonObject = Capture(response).use { capture ->
        runBlocking { withTimeout(10_000) { call(capture.client) } }
        capture.requests.single().bodyJson()
    }

    @Test fun `Claude keeps every leading system block and moves later ones into user turns`() {
        val body = send(anthropic) { client ->
            ClaudeProvider(client).generateText(ProviderSetting.Claude(baseUrl = "https://offline.invalid/v1", apiKey = "k"), tavern, params)
        }
        assertEquals(listOf("main", "license"), texts(body["system"]))
        val messages = body["messages"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "user", "assistant", "user"), roles(messages))
        assertEquals(
            listOf(listOf(LEADING_USER_PLACEHOLDER), listOf("commitment"), listOf("rules", "context", "hi"), listOf("reply"), listOf("next", "jailbreak")),
            messages.map { texts(it) },
        )
    }

    @Test fun `Claude cache breakpoint stays on the previous user turn when system blocks follow the chat`() {
        val body = send(anthropic) { client ->
            ClaudeProvider(client).generateText(
                ProviderSetting.Claude(baseUrl = "https://offline.invalid/v1", apiKey = "k", promptCaching = true), tavern, params,
            )
        }
        val marked = body["messages"]!!.jsonArray.flatMap { it.jsonObject["content"]!!.jsonArray }
            .filter { it.jsonObject.containsKey("cache_control") }
        assertEquals(listOf("hi"), marked.map { it.jsonObject["text"]!!.jsonPrimitive.content })
    }

    @Test fun `system messages that are only whitespace are not sent`() {
        // 预设里只含 {{setvar}} 之类宏的条目求值后只剩空白；Claude 拒收空白文字块
        val withBlanks = listOf(UIMessage.system(" \n "), UIMessage.system("main"), UIMessage.user("hi"), UIMessage.system("\n\n"), UIMessage.system("jailbreak"))
        val claude = send(anthropic) { client ->
            ClaudeProvider(client).generateText(ProviderSetting.Claude(baseUrl = "https://offline.invalid/v1", apiKey = "k"), withBlanks, params)
        }
        assertEquals(listOf("main"), texts(claude["system"]))
        assertEquals(listOf(listOf("hi", "jailbreak")), claude["messages"]!!.jsonArray.map { texts(it) })
        val gemini = send(gemini) { client ->
            GoogleProvider(client).generateText(ProviderSetting.Google(baseUrl = "https://offline.invalid/v1beta", apiKey = "k"), withBlanks, params)
        }
        assertEquals(listOf("main"), texts(gemini["systemInstruction"]))
        assertEquals(listOf("hi", "jailbreak"), texts(gemini["contents"]))
    }

    @Test fun `Gemini puts each leading system block in systemInstruction and later ones into user turns`() {
        val body = send(gemini) { client ->
            GoogleProvider(client).generateText(ProviderSetting.Google(baseUrl = "https://offline.invalid/v1beta", apiKey = "k"), tavern, params)
        }
        assertEquals(listOf("main", "license"), texts(body["systemInstruction"]))
        val contents = body["contents"]!!.jsonArray
        assertEquals(listOf("user", "model", "user", "model", "user"), roles(contents))
        assertEquals(listOf(LEADING_USER_PLACEHOLDER) + inOrder, texts(contents))
    }

    @Test fun `Gemini without a leading system block sends no systemInstruction`() {
        val body = send(gemini) { client ->
            GoogleProvider(client).generateText(
                ProviderSetting.Google(baseUrl = "https://offline.invalid/v1beta", apiKey = "k"),
                listOf(UIMessage.assistant("greeting"), UIMessage.system("rules"), UIMessage.user("hi")),
                params,
            )
        }
        assertFalse(body.containsKey("systemInstruction"))
        assertEquals(listOf(LEADING_USER_PLACEHOLDER, "greeting", "rules", "hi"), texts(body["contents"]))
    }

    @Test fun `Interactions joins leading system blocks and keeps later ones in the input`() {
        val body = send(interaction) { client ->
            GoogleProvider(client).generateText(
                ProviderSetting.Google(baseUrl = "https://offline.invalid/v1beta", apiKey = "k", useInteractionsApi = true), tavern, params,
            )
        }
        assertEquals("main\nlicense", body["system_instruction"]!!.jsonPrimitive.content)
        assertEquals(inOrder, texts(body["input"]))
    }

    @Test fun `Responses joins leading system blocks into instructions and keeps later ones in the input`() {
        val body = send(responses) { client ->
            OpenAIProvider(client).generateText(
                ProviderSetting.OpenAI(baseUrl = "https://offline.invalid/v1", apiKey = "k", useResponseApi = true), tavern, params,
            )
        }
        assertEquals("main\nlicense", body["instructions"]!!.jsonPrimitive.content)
        assertEquals(inOrder, texts(body["input"]))
    }

    @Test fun `ChatCompletions keeps every system message where it is`() {
        val body = send(chat) { client ->
            OpenAIProvider(client).generateText(ProviderSetting.OpenAI(baseUrl = "https://offline.invalid/v1", apiKey = "k"), tavern, params)
        }
        val messages = body["messages"]!!.jsonArray
        assertEquals(
            listOf("system", "system", "assistant", "system", "system", "user", "assistant", "user", "system"),
            roles(messages),
        )
        assertEquals(listOf("main", "license") + inOrder, messages.map { m ->
            m.jsonObject["content"]!!.let { c -> (c as? JsonPrimitive)?.content ?: texts(c).joinToString("") }
        })
    }

    @Test fun `Devin keeps only leading system blocks in the system prompt`() {
        val bundle = buildDevinMessages(tavern, emptyList())
        assertEquals("main\nlicense", bundle.systemPrompt)
        assertEquals(
            listOf(2 to "commitment", 1 to "rules\ncontext\nhi", 2 to "reply", 1 to "next\njailbreak"),
            bundle.prompts.map { it.source to it.content },
        )
    }
}
