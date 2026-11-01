package me.rerere.rikkihub.web.routes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * POST /api/translator/translate 的请求校验与模型解析（纯函数，无 Ktor server）。
 * 端到端（SSE 写出 / 断连取消 / JWT）需真机集成验证，见 E-IMPLEMENTATION.md。
 */
class TranslatorRequestValidationTest {

    // ---------- text ----------

    @Test(expected = BadRequestException::class)
    fun `text null is rejected`() {
        validateTranslatorText(null)
    }

    @Test(expected = BadRequestException::class)
    fun `text blank is rejected`() {
        validateTranslatorText("   \n\t ")
    }

    @Test
    fun `text within 32KiB passes`() {
        val text = "a".repeat(TRANSLATOR_MAX_TEXT_BYTES)
        assertEquals(text, validateTranslatorText(text))
    }

    @Test
    fun `text counts utf-8 bytes not chars`() {
        // 10922 个汉字 * 3 字节 = 32766 <= 32768
        val ok = "字".repeat(10922)
        assertEquals(ok, validateTranslatorText(ok))
    }

    @Test(expected = BadRequestException::class)
    fun `text over 32KiB in multibyte is rejected`() {
        validateTranslatorText("字".repeat(10923)) // 32769 bytes
    }

    @Test
    fun `text keeps original whitespace`() {
        val text = "  hello\nworld  "
        assertEquals(text, validateTranslatorText(text))
    }

    // ---------- language (BCP-47) ----------

    @Test(expected = BadRequestException::class)
    fun `language null is rejected`() {
        parseTranslatorTargetLocale(null)
    }

    @Test(expected = BadRequestException::class)
    fun `language blank is rejected`() {
        parseTranslatorTargetLocale("   ")
    }

    @Test(expected = BadRequestException::class)
    fun `language too long is rejected`() {
        parseTranslatorTargetLocale("a".repeat(65))
    }

    @Test(expected = BadRequestException::class)
    fun `language malformed tag is rejected`() {
        parseTranslatorTargetLocale("en--US!!")
    }

    @Test(expected = BadRequestException::class)
    fun `language und is rejected`() {
        parseTranslatorTargetLocale("und")
    }

    @Test
    fun `language valid tags parse`() {
        assertEquals("en", parseTranslatorTargetLocale("en").language)
        assertEquals("zh", parseTranslatorTargetLocale("zh-CN").language)
        assertEquals("CN", parseTranslatorTargetLocale("zh-CN").country)
        assertEquals("fr", parseTranslatorTargetLocale("  fr-CA ").language)
    }

    // ---------- model 解析 ----------

    private fun chatModel(id: Uuid = Uuid.random(), modelId: String = "gpt") =
        Model(id = id, modelId = modelId, type = ModelType.CHAT)

    private fun settingsWith(
        model: Model,
        providerEnabled: Boolean = true,
        translateModeId: Uuid = model.id,
    ) = Settings(
        translateModeId = translateModeId,
        providers = listOf(ProviderSetting.OpenAI(enabled = providerEnabled, models = listOf(model))),
    )

    @Test
    fun `modelId absent falls back to translateModeId`() {
        val model = chatModel()
        val settings = settingsWith(model)
        assertEquals(model, resolveTranslatorRequestModel(settings, null))
        assertEquals(model, resolveTranslatorRequestModel(settings, "  "))
    }

    @Test(expected = BadRequestException::class)
    fun `modelId absent with unconfigured default is rejected`() {
        resolveTranslatorRequestModel(Settings(providers = emptyList()), null)
    }

    @Test(expected = BadRequestException::class)
    fun `modelId malformed uuid is rejected`() {
        resolveTranslatorRequestModel(settingsWith(chatModel()), "not-a-uuid")
    }

    @Test(expected = BadRequestException::class)
    fun `modelId unknown is rejected`() {
        resolveTranslatorRequestModel(settingsWith(chatModel()), Uuid.random().toString())
    }

    @Test
    fun `modelId override resolves known chat model`() {
        val default = chatModel(modelId = "default-model")
        val override = chatModel(modelId = "other-model")
        val settings = settingsWith(default).let {
            it.copy(
                providers = listOf(
                    ProviderSetting.OpenAI(enabled = true, models = listOf(default, override))
                )
            )
        }
        assertEquals(override, resolveTranslatorRequestModel(settings, override.id.toString()))
    }

    @Test(expected = BadRequestException::class)
    fun `non-chat modelId is rejected`() {
        val imageModel = Model(modelId = "dall-e", type = ModelType.IMAGE)
        resolveTranslatorRequestModel(settingsWith(imageModel), imageModel.id.toString())
    }

    @Test(expected = BadRequestException::class)
    fun `default model of non-chat type is rejected`() {
        val imageModel = Model(modelId = "dall-e", type = ModelType.IMAGE)
        resolveTranslatorRequestModel(settingsWith(imageModel), null)
    }

    @Test
    fun `provider enabled passes validation`() {
        val model = chatModel()
        validateTranslatorModelProvider(settingsWith(model), model)
    }

    @Test(expected = BadRequestException::class)
    fun `disabled provider is rejected`() {
        val model = chatModel()
        validateTranslatorModelProvider(settingsWith(model, providerEnabled = false), model)
    }

    @Test
    fun `per-request override uses copy and never mutates settings`() {
        val default = chatModel(modelId = "default-model")
        val override = chatModel(modelId = "other-model")
        val settings = settingsWith(default).let {
            it.copy(
                providers = listOf(
                    ProviderSetting.OpenAI(enabled = true, models = listOf(default, override))
                )
            )
        }
        val resolved = resolveTranslatorRequestModel(settings, override.id.toString())
        // 路由内的实际做法：copy 出有效 settings 传给 handler，原 settings 不变
        val effective = settings.copy(translateModeId = resolved.id)
        assertEquals(override.id, effective.translateModeId)
        assertEquals(default.id, settings.translateModeId)
        assertNotEquals(effective, settings)
    }

    // ---------- 错误脱敏 ----------

    @Test
    fun `sanitize strips bearer tokens`() {
        val out = sanitizePublicError("401 Unauthorized: Bearer sk-abcdef1234567890 was rejected")
        assertTrue(!out.contains("sk-abcdef1234567890"))
        assertTrue(out.contains("[redacted]") || out == "Translation failed")
    }

    @Test
    fun `sanitize strips api_key params`() {
        val out = sanitizePublicError("GET https://x.test/v1?api_key=SECRETKEY123 failed")
        assertTrue(!out.contains("SECRETKEY123"))
    }

    @Test
    fun `sanitize keeps normal message`() {
        assertEquals("HTTP 429 too many requests", sanitizePublicError("HTTP 429\ntoo many  requests"))
    }

    @Test
    fun `sanitize blank becomes generic`() {
        assertEquals("Translation failed", sanitizePublicError(null))
        assertEquals("Translation failed", sanitizePublicError("   "))
    }

    @Test
    fun `sanitize truncates long messages`() {
        val out = sanitizePublicError("e".repeat(500))
        assertTrue(out.length <= 281)
        assertTrue(out.endsWith("…"))
    }

    // ---------- 流式收集 seam（真实取消/超时路径，不需要 Ktor server） ----------

    private class StreamProbe {
        val events = mutableListOf<Pair<String, String>>()
        var failAtEvent = -1
        var failWith: Throwable = RuntimeException("channel closed")

        val send: suspend (String, String) -> Unit = { event, json ->
            events += event to json
            if (events.size - 1 == failAtEvent) throw failWith
        }
    }

    @Test
    fun `stream collect emits text snapshots then done`() = runBlocking {
        val probe = StreamProbe()
        runTranslatorStreamCollect(
            textFlow = flow {
                emit("hel")
                emit("hello")
            },
            sendEvent = probe.send,
        )
        assertEquals(
            listOf("text", "text", "done"),
            probe.events.map { it.first },
        )
        assertTrue(probe.events[2].second.contains("\"text\":\"hello\""))
    }

    @Test
    fun `stream collect records history once before done and only on success`() = runBlocking {
        val recorded = mutableListOf<String>()
        val probe = StreamProbe()
        runTranslatorStreamCollect(
            textFlow = flow {
                emit("hel")
                emit("hello")
            },
            sendEvent = { name, data ->
                probe.send(name, data)
                if (name == "done") assertEquals(listOf("hello"), recorded)
            },
            onDone = { recorded += it },
        )
        assertEquals(listOf("hello"), recorded)

        val failed = mutableListOf<String>()
        runTranslatorStreamCollect(
            textFlow = flow {
                emit("partial")
                throw RuntimeException("boom")
            },
            sendEvent = StreamProbe().send,
            onDone = { failed += it },
        )
        assertTrue(failed.isEmpty())
    }

    @Test
    fun `history recording failure does not break the done event`() = runBlocking {
        val probe = StreamProbe()
        runTranslatorStreamCollect(
            textFlow = flow { emit("ok") },
            sendEvent = probe.send,
            onDone = { throw IllegalStateException("db closed") },
        )
        assertEquals(listOf("text", "done"), probe.events.map { it.first })
    }

    @Test
    fun `history page parameters default and validate`() {
        assertEquals(0 to 20, parseTranslatorHistoryPage(null, null))
        assertEquals(40 to 100, parseTranslatorHistoryPage("40", "100"))
        listOf("-1" to "20", "0" to "0", "0" to "101", "x" to "20", "0" to "y").forEach { (offset, limit) ->
            try {
                parseTranslatorHistoryPage(offset, limit)
                fail("expected 400 for offset=$offset limit=$limit")
            } catch (_: BadRequestException) {
            }
        }
    }

    @Test
    fun `stream collect upstream error becomes sanitized error event`() = runBlocking {
        val probe = StreamProbe()
        runTranslatorStreamCollect(
            textFlow = flow {
                emit("partial")
                throw RuntimeException("upstream Bearer sk-secret999 failed")
            },
            sendEvent = probe.send,
        )
        assertEquals(listOf("text", "error"), probe.events.map { it.first })
        val errorPayload = probe.events[1].second
        assertTrue(!errorPayload.contains("sk-secret999"))
        assertTrue(errorPayload.contains("[redacted]") || errorPayload.contains("Translation failed"))
    }

    @Test
    fun `stream collect timeout sends timeout error`() = runBlocking {
        val probe = StreamProbe()
        runTranslatorStreamCollect(
            textFlow = flow {
                emit("x")
                delay(10_000) // 远超 streamTimeout -> TimeoutCancellationException
            },
            sendEvent = probe.send,
            streamTimeout = 30.milliseconds,
        )
        assertEquals(listOf("text", "error"), probe.events.map { it.first })
        assertTrue(probe.events[1].second.contains("Translation timed out"))
    }

    @Test
    fun `stream collect propagates cancellation and cancels upstream`() = runBlocking {
        var upstreamFinishedNormally = false
        var upstreamCancelled = false
        val probe = StreamProbe()
        val upstream = flow<String> {
            try {
                emit("first")
                kotlinx.coroutines.awaitCancellation()
                upstreamFinishedNormally = true
            } finally {
                upstreamCancelled = true
            }
        }
        val job = launch {
            runTranslatorStreamCollect(textFlow = upstream, sendEvent = probe.send)
        }
        // 等到第一个 text 事件写出后再取消（在 send 完成后、上游挂起时）
        while (probe.events.isEmpty()) {
            kotlinx.coroutines.yield()
        }
        job.cancel()
        job.join()
        assertTrue(upstreamCancelled)
        assertFalse(upstreamFinishedNormally)
        assertEquals(listOf("text"), probe.events.map { it.first })
    }

    @Test
    fun `stream collect send failure during text aborts upstream`() = runBlocking {
        var upstreamCancelled = false
        val probe = StreamProbe()
        probe.failAtEvent = 0 // 第一个 text 写出就失败（模拟断连）
        val upstream = flow<String> {
            try {
                emit("a")
                emit("b")
            } finally {
                upstreamCancelled = true
            }
        }
        try {
            runTranslatorStreamCollect(textFlow = upstream, sendEvent = probe.send)
            fail("send failure should propagate")
        } catch (e: RuntimeException) {
            assertEquals("channel closed", e.message)
        }
        assertTrue(upstreamCancelled)
        assertEquals(1, probe.events.size)
    }

    @Test
    fun `stream collect send failure during error report is swallowed`() = runBlocking {
        // 上游抛错 -> 走 error 事件 -> error 写出也失败：静默，不抛第二个错
        val probe = StreamProbe()
        probe.failAtEvent = 0 // sendEvent 第一次调用（error 事件）就失败
        runTranslatorStreamCollect(
            textFlow = flow { throw RuntimeException("upstream dead") },
            sendEvent = probe.send,
        )
        assertEquals(1, probe.events.size)
        assertEquals("error", probe.events[0].first)
    }

    @Test
    fun `stream collect cancellation during error report propagates`() = runBlocking {
        val probe = StreamProbe()
        probe.failAtEvent = 0
        probe.failWith = CancellationException("client gone")
        try {
            runTranslatorStreamCollect(
                textFlow = flow { throw RuntimeException("upstream dead") },
                sendEvent = probe.send,
            )
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("client gone", e.message)
        }
    }

    @Test
    fun `stream collect empty flow still sends done`() = runBlocking {
        val probe = StreamProbe()
        runTranslatorStreamCollect(textFlow = flow { }, sendEvent = probe.send)
        assertEquals(listOf("done"), probe.events.map { it.first })
        assertTrue(probe.events[0].second.contains("\"text\":\"\""))
    }
}
