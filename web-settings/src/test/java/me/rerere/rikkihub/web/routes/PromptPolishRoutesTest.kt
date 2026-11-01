package me.rerere.rikkihub.web.routes

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.ai.polish.PromptPolish
import me.rerere.rikkihub.data.ai.polish.PromptPolishConfig
import me.rerere.rikkihub.data.ai.polish.PromptPolishException
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/** `POST /api/prompt-polish` 校验与 SSE 事件、`GET/PUT /api/prompt-polish/config` 的纯函数测试。 */
class PromptPolishRoutesTest {
    private val chat = Model(modelId = "chat")
    private val embedding = Model(modelId = "embed", type = ModelType.EMBEDDING)
    private val settings = Settings(
        providers = listOf(ProviderSetting.OpenAI(apiKey = "k", baseUrl = "http://localhost", models = listOf(chat, embedding))),
    )

    private fun badRequest(block: () -> Unit) {
        try {
            block()
            fail("expected BadRequestException")
        } catch (_: BadRequestException) {
        }
    }

    @Test
    fun `request validation`() {
        val id = Uuid.random()
        assertEquals("文字" to id, parsePromptPolishRequest(PromptPolishHttpRequest("文字", id.toString())))
        assertEquals("文字" to null, parsePromptPolishRequest(PromptPolishHttpRequest("文字", "")))
        badRequest { parsePromptPolishRequest(PromptPolishHttpRequest(null)) }
        badRequest { parsePromptPolishRequest(PromptPolishHttpRequest("  ")) }
        badRequest { parsePromptPolishRequest(PromptPolishHttpRequest("x".repeat(PromptPolish.MAX_INPUT_CHARS + 1))) }
        badRequest { parsePromptPolishRequest(PromptPolishHttpRequest("文字", "not-a-uuid")) }
    }

    @Test
    fun `config projection reports defaults and a missing model`() {
        val dto = projectPromptPolishConfig(settings)
        assertNull(dto.modelId)
        assertFalse(dto.modelMissing)
        assertNull(dto.prompt)
        assertEquals(PromptPolish.DEFAULT_PROMPT, dto.defaultPrompt)
        assertFalse(dto.includeContext)
        assertEquals(4, dto.contextMessages)
        val missing = projectPromptPolishConfig(settings.copy(promptPolish = PromptPolishConfig(modelId = Uuid.random(), prompt = " ")))
        assertTrue(missing.modelMissing)
        assertNull(missing.prompt)
    }

    @Test
    fun `config merge validates every field`() {
        val base = PromptPolishConfig()
        val merged = mergePromptPolishConfig(base, buildJsonObject {
            put("modelId", chat.id.toString())
            put("prompt", "改写：{input}")
            put("includeContext", true)
            put("contextMessages", 6)
        }, settings)
        assertEquals(PromptPolishConfig(chat.id, "改写：{input}", true, 6), merged)
        val cleared = mergePromptPolishConfig(merged, buildJsonObject {
            put("modelId", JsonNull)
            put("prompt", "   ")
        }, settings)
        assertNull(cleared.modelId)
        assertNull(cleared.prompt)
        assertTrue(cleared.includeContext)

        badRequest { mergePromptPolishConfig(base, buildJsonObject { put("unknown", 1) }, settings) }
        badRequest { mergePromptPolishConfig(base, buildJsonObject { put("modelId", Uuid.random().toString()) }, settings) }
        badRequest { mergePromptPolishConfig(base, buildJsonObject { put("modelId", embedding.id.toString()) }, settings) }
        badRequest { mergePromptPolishConfig(base, buildJsonObject { put("modelId", "bad") }, settings) }
        badRequest { mergePromptPolishConfig(base, buildJsonObject { put("prompt", "x".repeat(PromptPolishConfig.MAX_PROMPT_CHARS + 1)) }, settings) }
        badRequest { mergePromptPolishConfig(base, buildJsonObject { put("includeContext", "true") }, settings) }
        badRequest { mergePromptPolishConfig(base, buildJsonObject { put("contextMessages", 0) }, settings) }
        badRequest { mergePromptPolishConfig(base, buildJsonObject { put("contextMessages", 21) }, settings) }
        badRequest { mergePromptPolishConfig(base, buildJsonObject { put("contextMessages", "4") }, settings) }
        // A model that was deleted after being chosen may be sent back unchanged.
        val gone = Uuid.random()
        assertEquals(gone, mergePromptPolishConfig(PromptPolishConfig(modelId = gone),
            buildJsonObject { put("modelId", gone.toString()) }, settings).modelId)
    }

    private fun collect(block: suspend (suspend (String, String) -> Unit) -> Unit): List<Pair<String, String>> {
        val events = mutableListOf<Pair<String, String>>()
        runBlocking { block { name, data -> events += name to data } }
        return events
    }

    @Test
    fun `stream sends deltas then the final text`() {
        val events = collect { send -> runPromptPolishStream(flowOf("一", "一二", "一二三"), sendEvent = send) }
        assertEquals(listOf("delta", "delta", "done"), events.map { it.first })
        assertEquals("""{"text":"一二三"}""", events.last().second)
    }

    @Test
    fun `stream errors carry a code`() {
        val failed = collect { send ->
            runPromptPolishStream(flow { throw PromptPolishException(PromptPolishException.Reason.NO_MODEL) }, sendEvent = send)
        }
        assertEquals("error" to """{"code":"NO_MODEL","message":"NO_MODEL"}""", failed.single())
        val model = collect { send -> runPromptPolishStream(flow { emit("a"); throw IllegalStateException("boom") }, sendEvent = send) }
        assertEquals("error", model.single().first)
        assertTrue("MODEL_ERROR" in model.single().second)
        val empty = collect { send -> runPromptPolishStream(flow { }, sendEvent = send) }
        assertTrue("EMPTY_RESULT" in empty.single().second)
        val timeout = collect { send ->
            runPromptPolishStream(flow { emit("a"); awaitCancellation() }, streamTimeout = 50.milliseconds, sendEvent = send)
        }
        assertEquals("error", timeout.single().first)
        assertTrue("TIMEOUT" in timeout.single().second)
    }

    @Test
    fun `a failed write propagates so the generation is cancelled`() {
        try {
            runBlocking {
                runPromptPolishStream(flowOf("a", "b")) { _, _ -> throw IOException("gone") }
            }
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("gone", e.message)
        }
    }
}
