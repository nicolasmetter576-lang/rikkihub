package me.rerere.rikkihub.web.routes

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoConfig
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoFormat
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoConfig
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoException
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoPrepared
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoProgress
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoPrompts
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoType
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/** `POST /api/zaizao` 校验与 SSE 事件、`GET/PUT /api/zaizao/config` 的纯函数测试。 */
class ZaizaoRoutesTest {
    private val settings = Settings(zaizao = ZaizaoConfig(includeAfter = false))
    private val source = "夜深了。他抄起铁锹，朝门口走去。\n\n门外站着一个人。"
    private val at = source.indexOf("他")
    private val end = source.indexOf("\n")

    private fun badRequest(block: () -> Unit) {
        try {
            block()
            fail("expected BadRequestException")
        } catch (_: BadRequestException) {
        }
    }

    private fun request(
        start: Int = at,
        to: Int = end,
        history: List<ZaizaoTurnDto> = emptyList(),
        type: String? = "MODIFY",
        requirement: String? = "铲子上有三斤大便",
        includeAfter: Boolean? = null,
        avoid: List<String> = emptyList(),
        fileName: String? = "ch1.md",
    ) = ZaizaoHttpRequest(fileName, source, start, to, includeAfter, history, type, requirement, avoid)

    @Test
    fun `prepare normalizes the range and follows the after-text setting`() {
        val prepared = prepareZaizaoRequest(settings, request(to = end + 1))
        assertEquals("他抄起铁锹，朝门口走去。", prepared.original)
        assertEquals(at, prepared.start)
        assertEquals(TuiqiaoFormat.MARKDOWN, prepared.format)
        val text = prepared.messages.single().toText()
        assertFalse("门外站着一个人" in text) // the setting says no after text
        assertTrue("<requirement>\n铲子上有三斤大便\n</requirement>" in text)
        val withAfter = prepareZaizaoRequest(settings, request(includeAfter = true)).messages.single().toText()
        assertTrue("门外站着一个人" in withAfter)
        assertTrue("<document name=\"untitled.md\">" in prepareZaizaoRequest(settings, request(fileName = " ")).messages.single().toText())
    }

    @Test
    fun `prepare replays the history and appends the drafts to avoid`() {
        val history = listOf(ZaizaoTurnDto("REWRITE", "换个写法", "<plan>p</plan><result>一稿</result>"))
        val prepared = prepareZaizaoRequest(settings, request(history = history, type = "INCREMENT", requirement = "加气味", avoid = listOf("二稿")))
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER), prepared.messages.map { it.role })
        assertTrue("本轮类型：重写" in prepared.messages[0].toText())
        assertEquals("<plan>p</plan><result>一稿</result>", prepared.messages[1].toText())
        assertTrue("本轮类型：增量" in prepared.messages[2].toText())
        assertTrue("<draft>\n二稿\n</draft>" in prepared.messages[2].toText())
    }

    @Test
    fun `range trims and expands to the sentence or paragraph`() {
        val word = source.indexOf("铁锹")
        assertEquals(ZaizaoRangeDto(word, word + 2, "铁锹", "MARKDOWN"),
            zaizaoRange(ZaizaoRangeHttpRequest("ch1.md", source, word, word + 2)))
        assertEquals(ZaizaoRangeDto(at, end, "他抄起铁锹，朝门口走去。", "MARKDOWN"),
            zaizaoRange(ZaizaoRangeHttpRequest("ch1.md", source, word, word + 2, "SENTENCE")))
        assertEquals("夜深了。他抄起铁锹，朝门口走去。", zaizaoRange(ZaizaoRangeHttpRequest("ch1.txt", source, word, word + 2, "PARAGRAPH")).original)
        assertEquals("PLAIN", zaizaoRange(ZaizaoRangeHttpRequest("ch1.txt", source, word, word + 2)).format)
        badRequest { zaizaoRange(ZaizaoRangeHttpRequest("ch1.md", source, word, word + 2, "WORLD")) }
        badRequest { zaizaoRange(ZaizaoRangeHttpRequest("ch1.md", source, end, end + 2)) }
        badRequest { zaizaoRange(ZaizaoRangeHttpRequest("ch1.md", null, 0, 1)) }
        val long = "字".repeat(5001)
        badRequest { zaizaoRange(ZaizaoRangeHttpRequest("a.md", long, 0, long.length)) }
    }

    @Test
    fun `prepare rejects bad input`() {
        badRequest { prepareZaizaoRequest(settings, request().copy(source = null)) }
        badRequest { prepareZaizaoRequest(settings, request(start = -1)) }
        badRequest { prepareZaizaoRequest(settings, request(to = source.length + 1)) }
        badRequest { prepareZaizaoRequest(settings, request(start = end, to = end + 2)) } // only whitespace
        badRequest { prepareZaizaoRequest(settings, request(type = null)) }
        badRequest { prepareZaizaoRequest(settings, request(type = "MAGIC")) }
        badRequest { prepareZaizaoRequest(settings, request(requirement = " ")) }
        badRequest { prepareZaizaoRequest(settings, request(requirement = "x".repeat(ZaizaoConfig.MAX_REQUIREMENT_CHARS + 1))) }
        badRequest { prepareZaizaoRequest(settings, request(history = listOf(ZaizaoTurnDto("MODIFY", "x", null)))) }
        badRequest { prepareZaizaoRequest(settings, request(history = listOf(ZaizaoTurnDto("BAD", "x", "y")))) }
        badRequest { prepareZaizaoRequest(settings, request(history = listOf(ZaizaoTurnDto("MODIFY", " ", "y")))) }
        badRequest { prepareZaizaoRequest(settings, request(history = List(20) { ZaizaoTurnDto("MODIFY", "x", "y") })) }
        badRequest { prepareZaizaoRequest(settings, request(history = listOf(ZaizaoTurnDto("MODIFY", "x", "y".repeat(ZAIZAO_MAX_OUTPUT_CHARS + 1))))) }
        badRequest { prepareZaizaoRequest(settings, request(avoid = List(11) { "x" })) }
        val long = "字".repeat(5001)
        badRequest { prepareZaizaoRequest(settings, ZaizaoHttpRequest("a.md", long, 0, long.length, type = "MODIFY", requirement = "x")) }
    }

    @Test
    fun `config projects defaults, custom prompts, the missing model and the shared file types`() {
        val dto = projectZaizaoConfig(settings)
        assertEquals("auto", dto.reasoningLevel)
        assertEquals(false, dto.includeAfter)
        assertEquals("MODIFY", dto.defaultType)
        assertEquals(mapOf("MODIFY" to null, "INCREMENT" to null, "REWRITE" to null), dto.prompts)
        assertEquals(ZaizaoPrompts.DEFAULT_REWRITE, dto.defaultPrompts["REWRITE"])
        assertEquals(TuiqiaoConfig.DEFAULT_EXTENSIONS, dto.extensions)
        val custom = projectZaizaoConfig(settings.copy(zaizao = ZaizaoConfig(modelId = Uuid.random(), rewritePrompt = "换", reasoningLevel = ReasoningLevel.XHIGH)))
        assertTrue(custom.modelMissing)
        assertEquals("换", custom.prompts["REWRITE"])
        assertEquals("xhigh", custom.reasoningLevel)
    }

    @Test
    fun `config merge validates every key`() {
        val chat = Model(modelId = "chat")
        val embedding = Model(modelId = "emb", type = ModelType.EMBEDDING)
        val withModels = settings.copy(providers = listOf(ProviderSetting.OpenAI(apiKey = "k", baseUrl = "http://x", models = listOf(chat, embedding))))
        val base = ZaizaoConfig()
        val merged = mergeZaizaoConfig(base, buildJsonObject {
            put("modelId", chat.id.toString())
            put("reasoningLevel", "high")
            put("temperature", 0.5)
            put("topP", 0.9)
            put("maxTokens", 4096)
            put("includeAfter", false)
            put("defaultType", "REWRITE")
            putJsonObject("prompts") { put("MODIFY", "只改动词"); put("REWRITE", JsonNull) }
            putJsonArray("recentRequirements") { add(" 甲 "); add("乙"); add("甲") }
        }, withModels)
        assertEquals(ZaizaoConfig(
            modelId = chat.id, reasoningLevel = ReasoningLevel.HIGH, temperature = 0.5f, topP = 0.9f, maxTokens = 4096,
            includeAfter = false, defaultType = ZaizaoType.REWRITE, modifyPrompt = "只改动词", recentRequirements = listOf("甲", "乙"),
        ), merged)
        val cleared = mergeZaizaoConfig(merged, buildJsonObject {
            put("modelId", JsonNull); put("temperature", JsonNull); put("topP", JsonNull); put("maxTokens", JsonNull)
            putJsonObject("prompts") { put("MODIFY", " ") }
        }, withModels)
        assertEquals(merged.copy(modelId = null, temperature = null, topP = null, maxTokens = null, modifyPrompt = null), cleared)

        badRequest { mergeZaizaoConfig(base, buildJsonObject { put("unknown", 1) }, withModels) }
        badRequest { mergeZaizaoConfig(base, buildJsonObject { put("modelId", Uuid.random().toString()) }, withModels) }
        badRequest { mergeZaizaoConfig(base, buildJsonObject { put("modelId", embedding.id.toString()) }, withModels) }
        badRequest { mergeZaizaoConfig(base, buildJsonObject { put("reasoningLevel", "HIGH") }, withModels) }
        badRequest { mergeZaizaoConfig(base, buildJsonObject { put("temperature", 3) }, withModels) }
        badRequest { mergeZaizaoConfig(base, buildJsonObject { put("temperature", "0.5") }, withModels) }
        badRequest { mergeZaizaoConfig(base, buildJsonObject { put("topP", 0) }, withModels) }
        badRequest { mergeZaizaoConfig(base, buildJsonObject { put("maxTokens", 0) }, withModels) }
        badRequest { mergeZaizaoConfig(base, buildJsonObject { put("includeAfter", "true") }, withModels) }
        badRequest { mergeZaizaoConfig(base, buildJsonObject { put("defaultType", "MAGIC") }, withModels) }
        badRequest { mergeZaizaoConfig(base, buildJsonObject { putJsonObject("prompts") { put("WORD", "x") } }, withModels) }
        badRequest { mergeZaizaoConfig(base, buildJsonObject { putJsonObject("prompts") { put("MODIFY", "x".repeat(ZaizaoConfig.MAX_PROMPT_CHARS + 1)) } }, withModels) }
        badRequest { mergeZaizaoConfig(base, buildJsonObject { putJsonArray("recentRequirements") { add(" ") } }, withModels) }
        badRequest { mergeZaizaoConfig(base, buildJsonObject { putJsonArray("recentRequirements") { repeat(11) { add("r$it") } } }, withModels) }
        // A model deleted since it was chosen can be kept as is.
        val gone = Uuid.random()
        assertEquals(gone, mergeZaizaoConfig(ZaizaoConfig(modelId = gone), buildJsonObject { put("modelId", gone.toString()) }, withModels).modelId)
    }

    private val prepared = ZaizaoPrepared(4, 16, "他抄起铁锹，朝门口走去。", TuiqiaoFormat.MARKDOWN, emptyList())

    private fun progress(vararg steps: ZaizaoProgress) = flowOf(*steps)

    @Test
    fun `stream sends prepared, updates and done`() = runBlocking {
        val events = mutableListOf<Pair<String, String>>()
        runZaizaoStream(prepared, progress(
            ZaizaoProgress("想", "", "", ""),
            ZaizaoProgress("想", "", "他抄", "<result>他抄"),
            ZaizaoProgress("想", "", "他抄起", "<result>他抄起</result>", done = true),
        ), updateInterval = Duration.ZERO) { n, d -> events += n to d }
        assertEquals(listOf("prepared", "update", "update", "done"), events.map { it.first })
        assertEquals("""{"start":4,"end":16,"original":"他抄起铁锹，朝门口走去。","format":"MARKDOWN"}""", events[0].second)
        assertEquals("""{"reasoning":"想","plan":"","result":"他抄"}""", events[2].second)
        assertEquals("""{"reasoning":"想","plan":"","result":"他抄起","output":"<result>他抄起</result>"}""", events[3].second)
    }

    @Test
    fun `updates are throttled but done always goes out`() = runBlocking {
        val events = mutableListOf<String>()
        runZaizaoStream(prepared, progress(
            ZaizaoProgress("", "", "a", "a"),
            ZaizaoProgress("", "", "ab", "ab"),
            ZaizaoProgress("", "", "abc", "abc"),
            ZaizaoProgress("", "", "abc", "abc", done = true),
        ), updateInterval = 10_000.milliseconds) { n, _ -> events += n }
        assertEquals(listOf("prepared", "update", "done"), events)
    }

    @Test
    fun `stream reports failures with codes`() = runBlocking {
        val events = mutableListOf<Pair<String, String>>()
        runZaizaoStream(prepared, flow { throw ZaizaoException(ZaizaoException.Reason.NO_MODEL) }) { n, d -> events += n to d }
        assertEquals("error" to """{"code":"NO_MODEL","message":"NO_MODEL"}""", events.last())
        events.clear()
        runZaizaoStream(prepared, flow { throw IllegalStateException("upstream 500 Authorization: Bearer sk-secretsecret") }) { n, d -> events += n to d }
        assertEquals("error", events.last().first)
        assertTrue("\"code\":\"MODEL_ERROR\"" in events.last().second)
        assertFalse("sk-secretsecret" in events.last().second)
        events.clear()
        runZaizaoStream(prepared, progress(ZaizaoProgress("", "", "半", "半"))) { n, d -> events += n to d } // ended without done
        assertEquals("error" to """{"code":"EMPTY_RESULT","message":"EMPTY_RESULT"}""", events.last())
    }

    @Test
    fun `stream times out with an error event`() = runBlocking {
        val events = mutableListOf<String>()
        runZaizaoStream(prepared, flow<ZaizaoProgress> { awaitCancellation() }, streamTimeout = 50.milliseconds) { n, _ -> events += n }
        assertEquals(listOf("prepared", "error"), events)
    }

    @Test
    fun `write failure propagates instead of becoming an error event`() = runBlocking {
        val events = mutableListOf<String>()
        try {
            runZaizaoStream(prepared, progress(ZaizaoProgress("", "", "a", "a", done = true))) { n, _ ->
                if (n == "done") throw IOException("client gone")
                events += n
            }
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals(listOf("prepared"), events)
    }
}
