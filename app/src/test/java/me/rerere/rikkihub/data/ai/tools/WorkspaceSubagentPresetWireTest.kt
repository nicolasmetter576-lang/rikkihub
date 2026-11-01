package me.rerere.rikkihub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.FakeContext
import me.rerere.rikkihub.data.ai.GenerationLoop
import me.rerere.rikkihub.data.ai.StatusStringResolver
import me.rerere.rikkihub.data.ai.testChatModel
import me.rerere.rikkihub.data.ai.testProviderSetting
import me.rerere.rikkihub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

/** Real provider request building with an in-process interceptor. DNS/networking is explicitly disabled. */
class WorkspaceSubagentPresetWireTest {
    @get:Rule val temp = TemporaryFolder()

    private val role = WorkspaceSubagentPreset(
        key = "reviewer", name = "Reviewer", description = "Review the delegated scope",
        systemPrompt = "PRIVATE WORKER ROLE: report verified findings only.",
        generationConfig = SubagentPresetGenerationConfig(0.2f, 0.6f, 321, ReasoningLevel.LOW),
    )

    private suspend fun runWire(
        preset: WorkspaceSubagentPreset = role,
        customBodies: List<CustomBody> = emptyList(),
    ): Triple<JsonObject, List<JsonObject>, File> {
        val context = FakeContext(temp.newFolder())
        val model = testChatModel().copy(
            modelId = "preset-wire-model", abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            customBodies = customBodies,
        )
        val requests = CopyOnWriteArrayList<JsonObject>()
        val responseText = """
            data: {"id":"wire","object":"chat.completion.chunk","created":0,"model":"preset-wire-model","choices":[{"index":0,"delta":{"role":"assistant","content":"Verified report"},"finish_reason":null}]}

            data: {"id":"wire","object":"chat.completion.chunk","created":0,"model":"preset-wire-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent() + "\n\n"
        val client = OkHttpClient.Builder()
            .dns { throw UnknownHostException("Live networking disabled in preset wire test") }
            .addInterceptor { chain ->
                val body = Buffer().also { requireNotNull(chain.request().body).writeTo(it) }.readUtf8()
                requests += JsonInstant.parseToJsonElement(body).jsonObject
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(responseText.toResponseBody("text/event-stream".toMediaType())).build()
            }.build()
        try {
            val provider = testProviderSetting(model).copy(baseUrl = "https://unit-test.invalid/v1")
            val settings = Settings(providers = listOf(provider))
            val loop = GenerationLoop(context, ProviderManager(client, context), JsonInstant).apply {
                statusStrings = StatusStringResolver { _, _ -> "test" }
            }
            val runner = WorkspaceSubagentRunner(loop, { settings }, object : InputMessageTransformer {}, context)
            val parts = withTimeout(10_000) {
                runner.run(
                    parentAssistant = Assistant(systemPrompt = "PARENT SECRET", temperature = 1.9f, maxTokens = 99),
                    parentModel = model, cwd = null, harnessMinimal = false, allowContext = false,
                    subagentModelId = null,
                    args = JsonInstant.parseToJsonElement("""{"agent":"reviewer","prompt":"Review the task","description":"Wire check"}"""),
                    childTools = emptyList(), preset = preset,
                )
            }
            val result = JsonInstant.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
            return Triple(result, requests.toList(), File(context.filesDir, FileFolders.SUBAGENT_RUNS))
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `preset role and generation settings reach actual OpenAI request JSON`() = runBlocking {
        val (result, requests) = runWire()
        assertEquals("ok", result["status"]!!.jsonPrimitive.content)
        val request = requests.single()
        assertEquals(0.2f, request["temperature"]!!.jsonPrimitive.float, 0.00001f)
        assertEquals(0.6f, request["top_p"]!!.jsonPrimitive.float, 0.00001f)
        assertEquals(321, request["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("low", request["reasoning_effort"]!!.jsonPrimitive.content)
        val system = request["messages"]!!.jsonArray.first().jsonObject
        assertEquals("system", system["role"]!!.jsonPrimitive.content)
        assertTrue(system["content"].toString().contains("PRIVATE WORKER ROLE"))
        assertFalse(request["messages"].toString().contains("PARENT SECRET"))
    }

    @Test
    fun `explicit reasoning conflicts reject before any actual HTTP or log write`() = runBlocking {
        val conflicts = listOf(
            CustomBody("reasoning_effort", JsonPrimitive("high")),
            CustomBody("reasoning", JsonInstant.parseToJsonElement("""{"effort":"high"}""")),
            CustomBody("thinking", JsonInstant.parseToJsonElement("""{"type":"enabled","budget_tokens":8000}""")),
            CustomBody("enable_thinking", JsonPrimitive(true)),
            CustomBody("output_config", JsonInstant.parseToJsonElement("""{"effort":"high"}""")),
            CustomBody("generationConfig", JsonInstant.parseToJsonElement("""{"thinkingConfig":{"thinkingBudget":8000}}""")),
            CustomBody("generationConfig", kotlinx.serialization.json.JsonNull),
            CustomBody("stream", JsonPrimitive(false)),
        )
        conflicts.forEach { body ->
            val (result, requests, logs) = runWire(customBodies = listOf(body))
            assertEquals("config_invalid", result["status"]!!.jsonPrimitive.content)
            assertTrue(result["error"]!!.jsonPrimitive.content.contains("conflicts"))
            assertTrue("Unexpected HTTP request for ${body.key}", requests.isEmpty())
            assertFalse("Unexpected run log for ${body.key}", logs.exists())
        }
        val allowedFormat = CustomBody("output_config", JsonInstant.parseToJsonElement("""{"format":{"type":"text"}}"""))
        assertNull(presetModelConfigConflict(testChatModel().copy(customBodies = listOf(allowedFormat)), role))
    }

    @Test
    fun `AUTO intentionally defers to model reasoning configuration`() = runBlocking {
        val (result, requests) = runWire(
            preset = role.copy(generationConfig = role.generationConfig.copy(reasoningLevel = ReasoningLevel.AUTO)),
            customBodies = listOf(CustomBody("reasoning_effort", JsonPrimitive("high"))),
        )
        assertEquals("ok", result["status"]!!.jsonPrimitive.content)
        assertEquals("high", requests.single()["reasoning_effort"]!!.jsonPrimitive.content)
    }
}
