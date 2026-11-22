package me.rerere.ai.provider.providers.google

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames follow the real Vertex capture (gemini-4-argon, streamFunctionCallArguments=true). */
class VertexStreamedArgumentsTest {
    private fun frame(part: String, finish: String? = null): String {
        val reason = if (finish != null) ",\"finishReason\":\"$finish\"" else ""
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[$part]}$reason}]}"
    }

    private val start = frame("""{"functionCall":{"name":"workspace_write_file","id":"call1","willContinue":true},"thoughtSignature":"sig1"}""")
    private val end = frame("""{"functionCall":{}}""")
    private val stop = frame("""{"text":""}""", "STOP")

    private fun piece(path: String, value: String, more: Boolean = true): String {
        val willContinue = if (more) ",\"willContinue\":true" else ""
        return frame(
            "{\"functionCall\":{\"partialArgs\":[{\"jsonPath\":\"$path\",\"stringValue\":${json.encodeToString(value)}$willContinue}],\"willContinue\":true}}",
        )
    }

    private fun decode(frames: List<String>, eof: Boolean = true): List<StreamChunk> {
        val decoder = GoogleStreamDecoder("resp", "gemini-4-argon", vertexAI = true)
        val chunks = mutableListOf<StreamChunk>()
        frames.forEach { chunks += decoder.accept(SseEvent(data = it)).chunks }
        if (eof) chunks += decoder.onClosed()
        return chunks
    }

    private fun inputOf(chunks: List<StreamChunk>) =
        chunks.filterIsInstance<StreamChunk.ToolCallDelta>().joinToString("") { it.inputDelta }

    @Test
    fun `streamed arguments become an append-only object and end only after STOP`() {
        val text = "第一章 \"引号\" \\ 反斜杠\n换行\t制表 🔥"
        val frames = listOf(
            start, piece("$.path", "/workspace/write"), piece("$.path", "/c1.txt"), piece("$.path", "", more = false),
            piece("$.text", text.substring(0, 6)), piece("$.text", text.substring(6)), piece("$.text", "", more = false), end,
        )
        val decoder = GoogleStreamDecoder("resp", "gemini-4-argon", vertexAI = true)
        val chunks = mutableListOf<StreamChunk>()
        frames.forEach { chunks += decoder.accept(SseEvent(data = it)).chunks }
        // Arguments arrive while streaming, but the call stays open until terminal validation.
        assertTrue(chunks.none { it is StreamChunk.ToolCallEnd || it is StreamChunk.Finish })
        assertEquals("call1", chunks.filterIsInstance<StreamChunk.ToolCallStart>().single().id)
        assertEquals("workspace_write_file", chunks.filterIsInstance<StreamChunk.ToolCallStart>().single().toolName)
        assertTrue(chunks.filterIsInstance<StreamChunk.ToolCallStart>().single().metadata.toString().contains("sig1"))
        val prefix = inputOf(chunks)
        assertTrue(prefix.startsWith("""{"path":"/workspace/write/c1.txt","text":""""))
        chunks += decoder.accept(SseEvent(data = stop)).chunks
        chunks += decoder.onClosed()
        val parsed = json.parseToJsonElement(inputOf(chunks)).jsonObject
        assertEquals("/workspace/write/c1.txt", parsed["path"]!!.jsonPrimitive.content)
        assertEquals(text, parsed["text"]!!.jsonPrimitive.content)
        assertEquals(1, chunks.filterIsInstance<StreamChunk.ToolCallEnd>().size)
        assertEquals("STOP", chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
        assertTrue(chunks.indexOfFirst { it is StreamChunk.ToolCallEnd } > chunks.indexOfLast { it is StreamChunk.ToolCallDelta })
    }

    @Test
    fun `numbers booleans and null pieces and a call without arguments`() {
        val numbers = frame("""{"functionCall":{"partialArgs":[{"jsonPath":"$.n","numberValue":12},{"jsonPath":"$.ok","boolValue":true},{"jsonPath":"$.none","nullValue":null}],"willContinue":true}}""")
        val chunks = decode(listOf(start, piece("$.s", "a", more = false), numbers, end, stop))
        val parsed = json.parseToJsonElement(inputOf(chunks)).jsonObject
        assertEquals("a", parsed["s"]!!.jsonPrimitive.content)
        assertEquals("12", parsed["n"]!!.jsonPrimitive.content)
        assertEquals("true", parsed["ok"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), json.parseToJsonElement(inputOf(decode(listOf(start, end, stop)))))
    }

    @Test
    fun `two sequential streamed calls keep separate inputs`() {
        val second = start.replace("call1", "call2")
        val chunks = decode(listOf(
            start, piece("$.path", "/a", more = false), end,
            second, piece("$.path", "/b", more = false), end, stop,
        ))
        assertEquals(listOf("call1", "call2"), chunks.filterIsInstance<StreamChunk.ToolCallStart>().map { it.id })
        val byId = chunks.filterIsInstance<StreamChunk.ToolCallDelta>().groupBy({ it.id }, { it.inputDelta })
        assertEquals("""{"path":"/a"}""", byId["call1"]!!.joinToString(""))
        assertEquals("""{"path":"/b"}""", byId["call2"]!!.joinToString(""))
        assertEquals(2, chunks.filterIsInstance<StreamChunk.ToolCallEnd>().size)
    }

    @Test
    fun `unfinished or rejected streamed calls fail the attempt without ending the call`() {
        val cases = mapOf(
            "MAX_TOKENS in arguments" to listOf(start, piece("$.text", "partial"), frame("""{"text":""}""", "MAX_TOKENS")),
            "MALFORMED in arguments" to listOf(start, piece("$.text", "partial"), frame("""{"text":""}""", "MALFORMED_FUNCTION_CALL")),
            "STOP without the closing frame" to listOf(start, piece("$.text", "partial"), stop),
        )
        for ((name, frames) in cases) {
            val failure = runCatching { decode(frames) }.exceptionOrNull()
            assertTrue("$name: $failure", failure is ToolStreamProtocolException)
            assertTrue(name, (failure as ToolStreamProtocolException).code.startsWith("google_"))
        }
        // A cut stream is retried as a network failure; the half-streamed call never ends.
        val cut = mutableListOf<StreamChunk>()
        val decoder = GoogleStreamDecoder("resp", "gemini-4-argon", vertexAI = true)
        listOf(start, piece("$.text", "partial")).forEach { cut += decoder.accept(SseEvent(data = it)).chunks }
        assertTrue(runCatching { decoder.onClosed() }.exceptionOrNull() is IOException)
        assertTrue(cut.none { it is StreamChunk.ToolCallEnd })
    }

    @Test
    fun `malformed streamed shapes are protocol errors`() {
        fun code(frames: List<String>) =
            (runCatching { decode(frames) }.exceptionOrNull() as? ToolStreamProtocolException)?.code
        assertEquals("google_orphan_partial_args", code(listOf(piece("$.text", "x"), stop)))
        assertEquals("google_unsupported_partial_args", code(listOf(start, piece("$.a.b", "x"), end, stop)))
        assertEquals("google_unsupported_partial_args", code(listOf(start, piece("$.a[0]", "x"), end, stop)))
        assertEquals("google_duplicate_partial_args", code(listOf(start, piece("$.a", "x", more = false), piece("$.a", "y"), end, stop)))
        assertEquals("google_interleaved_tool_call", code(listOf(start, start.replace("call1", "call2"), end, stop)))
        assertEquals("google_interleaved_tool_call", code(listOf(
            start, frame("""{"functionCall":{"name":"other","args":{"x":1}}}"""), end, stop,
        )))
    }

    // ---- request switch and fallback, through the real provider ----

    private val writeFile = Tool(
        name = "workspace_write_file",
        description = "write",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap()), required = null) },
        execute = { emptyList() },
    )

    private fun model(id: String = "gemini-4-argon", tools: Set<BuiltInTools> = emptySet()) =
        Model(modelId = id, abilities = listOf(ModelAbility.TOOL), tools = tools)

    /** Runs one streamText per scripted response and returns the toolConfig of every request body. */
    private fun requests(
        setting: ProviderSetting.Google,
        params: TextGenerationParams,
        responses: List<(Int) -> Response.Builder.() -> Unit>,
        provider: (OkHttpClient) -> GoogleProvider = { GoogleProvider(it) },
    ): Pair<List<JsonObject?>, List<Throwable?>> {
        val toolConfigs = mutableListOf<JsonObject?>()
        var index = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            toolConfigs += json.parseToJsonElement(body).jsonObject["toolConfig"] as? JsonObject
            val script = responses[minOf(index++, responses.lastIndex)]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).apply(script(index - 1)).build()
        }.build()
        val failures = mutableListOf<Throwable?>()
        val google = provider(client)
        try {
            repeat(responses.size) {
                failures += runBlocking {
                    withTimeout(10_000) {
                        runCatching { google.streamText(setting, listOf(UIMessage.user("go")), params).collect {} }.exceptionOrNull()
                    }
                }
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        return toolConfigs to failures
    }

    private fun ok(vararg frames: String): (Int) -> Response.Builder.() -> Unit = {
        {
            code(200).message("OK").body(frames.joinToString("") { "data: $it\n\n" }.toResponseBody("text/event-stream".toMediaType()))
        }
    }

    private fun http(status: Int): (Int) -> Response.Builder.() -> Unit = {
        { code(status).message("x").body("""{"error":{"message":"synthetic-secret"}}""".toResponseBody("application/json".toMediaType())) }
    }

    private val fieldName = "streamFunctionCallArguments"
    private fun JsonObject?.streams() =
        (this?.get("functionCallingConfig") as? JsonObject)?.get(fieldName)?.jsonPrimitive?.content == "true"

    @Test
    fun `signature-only parts from built-in search fold into the thought part with the last signature`() {
        // Shape of the real search + function tools capture (gemini-4-argon, 2026-11-12).
        val frames = listOf(
            frame("""{"thoughtSignature":"search-1"}"""),
            frame("""{"thoughtSignature":"search-2"}"""),
            frame("""{"text":"Looking it up","thought":true}"""),
            frame("""{"thoughtSignature":"search-3"}"""),
            frame("""{"functionCall":{"name":"workspace_write_file","args":{"path":"a.md"},"id":"c1"},"thoughtSignature":"call-sig"}"""),
            stop,
        )
        val handler = StreamChunkHandler(model())
        var messages = listOf(UIMessage.user("go"))
        decode(frames).forEach { messages = handler.handle(messages, it) }
        val parts = messages.last().parts
        val thought = parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("Looking it up", thought.reasoning)
        assertEquals("search-3", thought.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        val call = parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("""{"path":"a.md"}""", call.input)
        assertEquals("call-sig", call.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
    }

    @Test
    fun `request asks for streamed arguments only where it is supported`() {
        val vertex = ProviderSetting.Google(vertexAI = true, useServiceAccount = false, apiKey = "k")
        fun streams(
            setting: ProviderSetting.Google = vertex,
            model: Model = model(),
            tools: List<Tool> = listOf(writeFile),
        ) = requests(setting, TextGenerationParams(model, tools = tools), listOf(ok(stop))).first.single()

        assertTrue(streams().streams())
        assertTrue(streams(model = model("gemini-3.1-pro-preview")).streams())
        assertFalse("Gemini 2.5 has no streamed arguments", streams(model = model("gemini-2.5-flash")).streams())
        assertFalse("Flash-Lite is unsupported", streams(model = model("gemini-3.1-flash-lite-preview")).streams())
        assertFalse("setting off", streams(setting = vertex.copy(streamFunctionCallArguments = false)).streams())
        assertFalse("no function tools", streams(tools = emptyList()).streams())
        assertFalse("AI Studio route", streams(setting = ProviderSetting.Google(apiKey = "k")).streams())
        val search = requests(vertex, TextGenerationParams(model(tools = setOf(BuiltInTools.Search)), tools = listOf(writeFile)),
            listOf(ok(stop))).first.single()!!
        assertFalse("Vertex rejects it next to built-in tools", search.streams())
        assertEquals("true", search["includeServerSideToolInvocations"]!!.jsonPrimitive.content)
    }

    @Test
    fun `HTTP 400 before any event retries once without the field and remembers it`() {
        val vertex = ProviderSetting.Google(vertexAI = true, apiKey = "k")
        val params = TextGenerationParams(model(), tools = listOf(writeFile))
        val (configs, failures) = requests(vertex, params, listOf(http(400), ok(stop), ok(stop)))
        assertTrue(failures[0] is StreamedArgumentsFallbackException)
        assertNull((failures[0] as StreamedArgumentsFallbackException).code)
        assertFalse(failures[0]!!.stackTraceToString().contains("synthetic-secret"))
        assertNull(failures[1])
        assertEquals(listOf(true, false, false), configs.map { it.streams() })
        // 429 and 5xx are not fallback triggers.
        val (others, otherFailures) = requests(vertex, params, listOf(http(429)))
        assertTrue(others.single().streams())
        assertFalse(otherFailures.single()!!.message.orEmpty().contains("streamed tool arguments"))
    }

    @Test
    fun `MALFORMED inside streamed arguments retries once without the field then streams again`() {
        val vertex = ProviderSetting.Google(vertexAI = true, apiKey = "k")
        val params = TextGenerationParams(model(), tools = listOf(writeFile))
        val malformed = ok(start, piece("$.path", "/a", more = false), piece("$.text", "x"), frame("""{"text":""}""", "MALFORMED_FUNCTION_CALL"))
        val plain = ok(frame("""{"functionCall":{"name":"workspace_write_file","id":"c","args":{"path":"/a","text":"x"}}}"""), stop)
        val (configs, failures) = requests(vertex, params, listOf(malformed, plain, ok(stop)))
        assertTrue("fallback must be a retryable IOException: ${failures[0]}", failures[0] is StreamedArgumentsFallbackException)
        assertEquals("google_incomplete_tool_call", (failures[0] as StreamedArgumentsFallbackException).code)
        assertTrue(failures[0]!!.message!!.contains("retrying without"))
        assertNull(failures[1])
        assertEquals("the retry skips the field once, later requests stream again", listOf(true, false, true), configs.map { it.streams() })
    }

    @Test
    fun `STOP without the closing frame retries once without the field`() {
        val vertex = ProviderSetting.Google(vertexAI = true, apiKey = "k")
        val params = TextGenerationParams(model(), tools = listOf(writeFile))
        val unterminated = ok(start, piece("$.path", "/a", more = false), piece("$.text", "x"), stop)
        val plain = ok(frame("""{"functionCall":{"name":"workspace_write_file","id":"c","args":{"path":"/a","text":"x"}}}"""), stop)
        val (configs, failures) = requests(vertex, params, listOf(unterminated, plain, ok(stop)))
        assertTrue("fallback must be a retryable IOException: ${failures[0]}", failures[0] is StreamedArgumentsFallbackException)
        assertEquals(UNTERMINATED_STREAMED_ARGS_CODE, (failures[0] as StreamedArgumentsFallbackException).code)
        assertTrue(failures[0]!!.message!!.contains("retrying without"))
        assertNull(failures[1])
        assertEquals(listOf(true, false, true), configs.map { it.streams() })
    }

    @Test
    fun `other protocol failures keep failing the attempt`() {
        val vertex = ProviderSetting.Google(vertexAI = true, apiKey = "k")
        val params = TextGenerationParams(model(), tools = listOf(writeFile))
        // An output-limit stop is not retried: the classic request would hit the same limit.
        val maxTokens = ok(start, piece("$.text", "x"), frame("""{"text":""}""", "MAX_TOKENS"))
        val (configs, failures) = requests(vertex, params, listOf(maxTokens, maxTokens))
        assertTrue("${failures[0]}", failures[0] is ToolStreamProtocolException)
        assertEquals(listOf(true, true), configs.map { it.streams() })
        val duplicate = ok(start, piece("$.a", "x", more = false), piece("$.a", "y"), end, stop)
        val (_, duplicateFailures) = requests(vertex, params, listOf(duplicate))
        assertTrue("duplicate keys are retried the classic way: ${duplicateFailures.single()}", duplicateFailures.single() is IOException)
    }
}
