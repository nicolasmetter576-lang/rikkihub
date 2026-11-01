package me.rerere.rikkihub.data.ai

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.utils.JsonInstant
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real SDK -> decoder -> handler -> generation loop -> tools -> persisted history, offline. */
class InteractionsGenerationLoopTest {
    @get:Rule val temporary = TemporaryFolder()
    private val codec = JsonInstant
    private val model = testChatModel().copy(modelId = "gemini-offline", abilities = listOf(ModelAbility.TOOL))

    private fun response(round: Int, function: String? = "echo_alias"): JsonObject = buildJsonObject {
        put("id", "response-$round"); put("model", model.modelId)
        put("status", if (function == null) "completed" else "requires_action")
        put("steps", buildJsonArray {
            if (function != null) {
                add(buildJsonObject { put("type", "google_search_call"); put("id", "server-same-id"); put("arguments", buildJsonObject { put("query", "r$round") }) })
                add(buildJsonObject { put("type", "thought"); put("signature", "thought-$round") })
                add(buildJsonObject { put("type", "google_search_result"); put("call_id", "server-same-id"); put("result", buildJsonArray { add(JsonPrimitive("answer-$round")) }) })
                add(buildJsonObject {
                    put("type", "function_call"); put("name", function); put("id", "call-$round")
                    put("signature", "function-$round"); put("arguments", buildJsonObject { put("round", round) })
                })
            } else add(buildJsonObject {
                put("type", "model_output"); put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "done") }) })
            })
        })
    }

    private fun sse(interaction: JsonObject): String = buildString {
        fun event(type: String, body: JsonObject) { append("event: $type\ndata: $body\n\n") }
        event("interaction.created", buildJsonObject { put("interaction", JsonObject(interaction - "steps")) })
        interaction.getValue("steps").jsonArray.forEachIndexed { index, raw ->
            val step = raw.jsonObject
            if (step.getValue("type").jsonPrimitive.content == "function_call") {
                // The real protocol fixture opens with arguments:{} before populated deltas.
                event("step.start", buildJsonObject {
                    put("index", index); put("step", JsonObject(step + ("arguments" to JsonObject(emptyMap()))))
                })
                event("step.delta", buildJsonObject { put("index", index); put("delta", buildJsonObject {
                    put("type", "arguments_delta"); put("arguments", step.getValue("arguments").toString())
                }) })
            } else event("step.start", buildJsonObject { put("index", index); put("step", step) })
            event("step.stop", buildJsonObject { put("index", index) })
        }
        event("interaction.completed", buildJsonObject { put("interaction", JsonObject(interaction - "steps")) })
    }

    private inner class Harness(val scripted: List<JsonObject>, val stream: Boolean) : AutoCloseable {
        val bodies = CopyOnWriteArrayList<JsonObject>()
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("/v1beta/interactions", request.url.encodedPath)
            val buffer = Buffer().also { request.body!!.writeTo(it) }
            bodies += codec.parseToJsonElement(buffer.readUtf8()).jsonObject
            val next = scripted.getOrNull(calls.getAndIncrement()) ?: error("unexpected extra request")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (stream) sse(next) else next.toString())
                    .toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType())).build()
        }.build()
        val context = FakeContext(temporary.newFolder())
        val manager = ProviderManager(client, context).apply { registerProvider("google", GoogleProvider(client)) }
        val loop = GenerationLoop(context, manager, codec).apply { statusStrings = StatusStringResolver { _, _ -> "status" } }
        val setting = ProviderSetting.Google(baseUrl = "https://offline.invalid/v1beta", apiKey = "test", useInteractionsApi = true, models = listOf(model))
        val settings = Settings(providers = listOf(setting))
        override fun close() { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun `real stream and once loops preserve two exchanges server order signatures progress and observer counts`() = runBlocking {
        for (stream in listOf(true, false)) Harness(listOf(response(1), response(2), response(3, null)), stream).use { h ->
            var executed = 0
            var observed = 0
            val tool = echoTool("echo_alias").copy(executeWithContext = { args, context ->
                executed++
                context.publishMetadata(buildJsonObject { put("status", "running"); put("title", "worker") })
                listOf(UIMessagePart.Text("result-${args.jsonObject.getValue("round").jsonPrimitive.int}"))
            })
            val chunks = withTimeout(10_000) {
                h.loop.generateTextObserved(h.settings, model, listOf(UIMessage.user("hello")), assistant = testAssistant(stream),
                    tools = listOf(tool), maxSteps = 6, requestObserver = ProviderRequestObserver { _, _, _, _ -> observed++ }).toList()
            }
            assertEquals(3, observed)
            assertEquals(3, h.calls.get())
            assertEquals(2, executed)
            assertEquals(3, chunks.filterIsInstance<GenerationChunk.StepFinished>().size)
            assertEquals(GenerationChunk.GenerationEnd.Reason.COMPLETED, chunks.filterIsInstance<GenerationChunk.GenerationEnd>().single().reason)
            val messages = chunks.filterIsInstance<GenerationChunk.Messages>().last().messages
            val assistant = messages.last()
            assertEquals(MessageRole.ASSISTANT, assistant.role)
            val tools = assistant.getTools()
            assertEquals(2, tools.size)
            assertEquals(listOf("function-1", "function-2"), tools.map { it.metadata!!.getValue("interactions_signature").jsonPrimitive.content })
            assertEquals(2, tools.map { it.metadata!!.getValue("interactions_exchange_key").jsonPrimitive.content }.toSet().size)
            assertEquals(2, assistant.parts.filterIsInstance<UIMessagePart.ServerTool>().size)
            assertTrue(tools.all { it.isExecuted })
            val input = h.bodies[2].getValue("input").jsonArray.map { it.jsonObject }
            assertEquals(listOf("user_input", "google_search_call", "thought", "google_search_result", "function_call", "function_result",
                "google_search_call", "thought", "google_search_result", "function_call", "function_result"), input.map { it.getValue("type").jsonPrimitive.content })
            assertEquals(listOf("thought-1", "thought-2"), input.filter { it["type"] == JsonPrimitive("thought") }.map { it.getValue("signature").jsonPrimitive.content })
            assertEquals(listOf("result-1", "result-2"), input.filter { it["type"] == JsonPrimitive("function_result") }
                .map { it.getValue("result").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content })
            // Persistence roundtrip retains the provenance which orders merged rounds.
            val restored = codec.decodeFromString<UIMessage>(codec.encodeToString(UIMessage.serializer(), assistant))
            assertEquals(assistant, restored)
        }
    }

    @Test fun `approval stops before client tool execution without inventing a result or next request`() = runBlocking {
        Harness(listOf(response(1)), true).use { h ->
            var executed = 0
            val tool = echoTool("echo_alias").copy(needsApproval = { true }, execute = { executed++; emptyList() })
            val chunks = withTimeout(10_000) { h.loop.generateText(h.settings, model, listOf(UIMessage.user("hello")),
                assistant = testAssistant(), tools = listOf(tool), maxSteps = 4).toList() }
            assertEquals(0, executed)
            assertEquals(1, h.calls.get())
            assertEquals(GenerationChunk.GenerationEnd.Reason.AWAITING_APPROVAL, chunks.filterIsInstance<GenerationChunk.GenerationEnd>().single().reason)
            assertTrue(chunks.filterIsInstance<GenerationChunk.Messages>().last().messages.last().getTools().single().isPending)
            assertEquals(listOf("user_input"), h.bodies.single().getValue("input").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        }
    }

    @Test fun `unregistered response name fails closed without retry execution or successful step end`() = runBlocking {
        Harness(listOf(response(1, "unregistered_alias")), true).use { h ->
            var executed = 0
            val observed = mutableListOf<GenerationChunk>()
            val tool = echoTool("echo_alias").copy(execute = { executed++; emptyList() })
            val settings = h.settings.copy(networkSetting = h.settings.networkSetting.copy(enableAutoRetry = true))
            val failure = runCatching { withTimeout(10_000) {
                h.loop.generateTextObserved(settings, model, listOf(UIMessage.user("hello")), assistant = testAssistant(), tools = listOf(tool),
                    eventsObserver = GenerationEventObserver { observed += it }).toList()
            } }.exceptionOrNull()
            assertTrue("$failure", failure is GenerationAttemptProtocolException)
            assertEquals(1, h.calls.get())
            assertEquals(0, executed)
            assertTrue(observed.filterIsInstance<GenerationChunk.StepFinished>().isEmpty())
            assertTrue(observed.filterIsInstance<GenerationChunk.GenerationEnd>().isEmpty())
        }
    }
}
