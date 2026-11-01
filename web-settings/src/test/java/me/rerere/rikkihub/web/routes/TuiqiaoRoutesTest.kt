package me.rerere.rikkihub.web.routes

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.ai.tuiqiao.Tuiqiao
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoCandidate
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoConfig
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoEvent
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoException
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoExplanation
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoFormat
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoMode
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoPrepared
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoPrompts
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoRole
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoSlot
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/** `POST /api/tuiqiao` 校验与 SSE 事件、`GET/PUT /api/tuiqiao/config` 的纯函数测试。 */
class TuiqiaoRoutesTest {
    private val settings = Settings(tuiqiao = TuiqiaoConfig(candidateCount = 4))

    private fun badRequest(block: () -> Unit) {
        try {
            block()
            fail("expected BadRequestException")
        } catch (_: BadRequestException) {
        }
    }

    @Test
    fun `prepare normalizes the range and applies mode and settings`() {
        val source = "鸟宿池边树，僧 推 月下门。"
        val start = source.indexOf(" 推")
        val prepared = prepareTuiqiaoRequest(settings, TuiqiaoHttpRequest("诗.md", source, start, start + 3))
        assertEquals(start + 1, prepared.start)
        assertEquals(start + 2, prepared.end)
        assertEquals("推", prepared.original)
        assertEquals(TuiqiaoMode.WORD, prepared.mode)
        assertTrue(prepared.slots.single().prompt.contains("给出 4 个"))
        assertTrue(prepared.slots.single().prompt.contains("<document name=\"诗.md\">"))
        val forced = prepareTuiqiaoRequest(settings, TuiqiaoHttpRequest(null, source, start, start + 3, "SENTENCE"))
        assertEquals(TuiqiaoMode.SENTENCE, forced.mode)
        assertTrue(forced.slots.single().prompt.contains("<document name=\"untitled.md\">"))
    }

    @Test
    fun `prepare expands to sentence or paragraph and re-detects the mode`() {
        val source = "夜深了。他轻轻推开门，屋里很暗。\n下一行也在段里。\n\n另一段。"
        val at = source.indexOf("推开")
        val sentence = prepareTuiqiaoRequest(settings, TuiqiaoHttpRequest("a.md", source, at, at + 2, mode = "WORD", expand = "SENTENCE"))
        assertEquals("他轻轻推开门，屋里很暗。", sentence.original)
        assertEquals(TuiqiaoMode.SENTENCE, sentence.mode)
        val paragraph = prepareTuiqiaoRequest(settings, TuiqiaoHttpRequest("a.md", source, at, at + 2, expand = "PARAGRAPH"))
        assertEquals("夜深了。他轻轻推开门，屋里很暗。\n下一行也在段里。", paragraph.original)
        assertEquals(TuiqiaoMode.PARAGRAPH, paragraph.mode)
        badRequest { prepareTuiqiaoRequest(settings, TuiqiaoHttpRequest("a.md", source, at, at + 2, expand = "WORLD")) }
        // Plain text: a line is a paragraph.
        val plain = prepareTuiqiaoRequest(settings, TuiqiaoHttpRequest("a.txt", source, at, at + 2, expand = "PARAGRAPH"))
        assertEquals("夜深了。他轻轻推开门，屋里很暗。", plain.original)
        assertEquals(TuiqiaoFormat.PLAIN, plain.format)
    }

    @Test
    fun `prepare puts matching roles first and can rerun a single slot`() {
        val role = TuiqiaoRole(name = "幽默旁白", prompt = "加旁白", modes = setOf(TuiqiaoMode.SENTENCE))
        val withRole = Settings(tuiqiao = TuiqiaoConfig(candidateCount = 3, roles = listOf(role)))
        val source = "他推开门，屋里很暗。"
        val prepared = prepareTuiqiaoRequest(withRole, TuiqiaoHttpRequest("a.md", source, 0, source.length))
        assertEquals(listOf(role.id.toString(), "default"), prepared.slots.map { it.key })
        val only = prepareTuiqiaoRequest(withRole, TuiqiaoHttpRequest("a.md", source, 0, source.length, slot = role.id.toString()))
        assertEquals(listOf(role.id.toString()), only.slots.map { it.key })
        badRequest { prepareTuiqiaoRequest(withRole, TuiqiaoHttpRequest("a.md", source, 0, source.length, slot = "nope")) }
    }

    @Test
    fun `prepare rejects missing, out of range, blank, too long and bad mode`() {
        badRequest { prepareTuiqiaoRequest(settings, TuiqiaoHttpRequest(source = null, start = 0, end = 1)) }
        badRequest { prepareTuiqiaoRequest(settings, TuiqiaoHttpRequest(source = "abc", start = null, end = 1)) }
        badRequest { prepareTuiqiaoRequest(settings, TuiqiaoHttpRequest(source = "abc", start = 2, end = 2)) }
        badRequest { prepareTuiqiaoRequest(settings, TuiqiaoHttpRequest(source = "abc", start = 0, end = 4)) }
        badRequest { prepareTuiqiaoRequest(settings, TuiqiaoHttpRequest(source = "a  b", start = 1, end = 3)) }
        badRequest { prepareTuiqiaoRequest(settings, TuiqiaoHttpRequest(source = "abc", start = 0, end = 1, mode = "LINE")) }
        val long = "长".repeat(3001)
        badRequest { prepareTuiqiaoRequest(settings, TuiqiaoHttpRequest(source = long, start = 0, end = long.length)) }
        val huge = "a".repeat(TUIQIAO_MAX_SOURCE_CHARS + 1)
        badRequest { prepareTuiqiaoRequest(settings, TuiqiaoHttpRequest(source = huge, start = 0, end = 1)) }
    }

    private val chatModel = Model(modelId = "chat")
    private val embedding = Model(modelId = "embed", type = ModelType.EMBEDDING)
    private val withModels = settings.copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(chatModel, embedding))))

    @Test
    fun `config projection and merge`() {
        val dto = projectTuiqiaoConfig(settings)
        assertEquals(4, dto.candidateCount)
        assertEquals("ONE_LINE", dto.explanation)
        assertEquals(TuiqiaoConfig.DEFAULT_EXTENSIONS, dto.extensions)
        assertEquals(false, dto.extensionsCustomized)
        assertEquals(null, dto.prompts["WORD"])
        assertEquals(TuiqiaoPrompts.DEFAULT_WORD, dto.defaultPrompts["WORD"])
        assertTrue("json" in dto.availableExtensions)
        assertEquals(5, projectTuiqiaoConfig(Settings(tuiqiao = TuiqiaoConfig(candidateCount = 9))).candidateCount)
        val merged = mergeTuiqiaoConfig(settings.tuiqiao, buildJsonObject { put("explanation", "DETAILED") }, settings)
        assertEquals(TuiqiaoConfig(candidateCount = 4, explanation = TuiqiaoExplanation.DETAILED), merged)
        val prompt = TuiqiaoConfig(wordPrompt = "keep me")
        assertEquals("keep me", mergeTuiqiaoConfig(prompt, buildJsonObject { put("candidateCount", 5) }, settings).wordPrompt)
        badRequest { mergeTuiqiaoConfig(settings.tuiqiao, buildJsonObject { put("candidateCount", 2) }, settings) }
        badRequest { mergeTuiqiaoConfig(settings.tuiqiao, buildJsonObject { put("candidateCount", "3") }, settings) }
        badRequest { mergeTuiqiaoConfig(settings.tuiqiao, buildJsonObject { put("explanation", "LOUD") }, settings) }
        badRequest { mergeTuiqiaoConfig(settings.tuiqiao, buildJsonObject { put("wordPrompt", "x") }, settings) }
        assertEquals(settings.tuiqiao, mergeTuiqiaoConfig(settings.tuiqiao, JsonObject(emptyMap()), settings))
    }

    @Test
    fun `merge prompts and extensions`() {
        val prompts = mergeTuiqiaoConfig(settings.tuiqiao, buildJsonObject {
            putJsonObject("prompts") { put("WORD", "近义词"); put("SENTENCE", "  "); put("PARAGRAPH", JsonNull) }
        }, settings)
        assertEquals("近义词", prompts.wordPrompt)
        assertEquals(null, prompts.sentencePrompt)
        badRequest { mergeTuiqiaoConfig(settings.tuiqiao, buildJsonObject { putJsonObject("prompts") { put("LINE", "x") } }, settings) }
        val ext = mergeTuiqiaoConfig(settings.tuiqiao, buildJsonObject {
            putJsonArray("extensions") { add(".TXT"); add("json"); add("txt") }
        }, settings)
        assertEquals(listOf("txt", "json"), ext.extensions)
        assertEquals(null, mergeTuiqiaoConfig(ext, buildJsonObject { put("extensions", JsonNull) }, settings).extensions)
        badRequest { mergeTuiqiaoConfig(settings.tuiqiao, buildJsonObject { putJsonArray("extensions") { add("exe") } }, settings) }
    }

    @Test
    fun `merge roles validates fields and models`() {
        fun role(name: String = "幽默旁白", prompt: String = "加旁白", modes: List<String> = listOf("SENTENCE", "PARAGRAPH"),
            modelId: String? = null, id: String? = null) = buildJsonObject {
            putJsonArray("roles") {
                add(buildJsonObject {
                    id?.let { put("id", it) }
                    put("name", name); put("prompt", prompt)
                    putJsonArray("modes") { modes.forEach { add(it) } }
                    modelId?.let { put("modelId", it) }
                })
            }
        }
        val merged = mergeTuiqiaoConfig(withModels.tuiqiao, role(modelId = chatModel.id.toString()), withModels)
        val saved = merged.roles.single()
        assertEquals("幽默旁白", saved.name)
        assertEquals(setOf(TuiqiaoMode.SENTENCE, TuiqiaoMode.PARAGRAPH), saved.modes)
        assertEquals(chatModel.id, saved.modelId)
        assertTrue(saved.enabled)
        badRequest { mergeTuiqiaoConfig(withModels.tuiqiao, role(name = " "), withModels) }
        badRequest { mergeTuiqiaoConfig(withModels.tuiqiao, role(name = "名".repeat(17)), withModels) }
        badRequest { mergeTuiqiaoConfig(withModels.tuiqiao, role(prompt = ""), withModels) }
        badRequest { mergeTuiqiaoConfig(withModels.tuiqiao, role(modes = emptyList()), withModels) }
        badRequest { mergeTuiqiaoConfig(withModels.tuiqiao, role(modes = listOf("LINE")), withModels) }
        badRequest { mergeTuiqiaoConfig(withModels.tuiqiao, role(modelId = embedding.id.toString()), withModels) }
        badRequest { mergeTuiqiaoConfig(withModels.tuiqiao, role(modelId = Uuid.random().toString()), withModels) }
        badRequest { mergeTuiqiaoConfig(withModels.tuiqiao, role(id = "not-a-uuid"), withModels) }
        // A role whose model was deleted can still be saved unchanged; the projection flags it.
        val gone = Uuid.random()
        val stale = withModels.tuiqiao.copy(roles = listOf(saved.copy(modelId = gone)))
        val kept = mergeTuiqiaoConfig(stale, role(id = saved.id.toString(), modelId = gone.toString()), withModels)
        assertEquals(gone, kept.roles.single().modelId)
        assertTrue(projectTuiqiaoConfig(withModels.copy(tuiqiao = kept)).roles.single().modelMissing)
        val tooMany = buildJsonObject {
            putJsonArray("roles") {
                repeat(TuiqiaoConfig.MAX_ROLES + 1) {
                    add(buildJsonObject { put("name", "r$it"); put("prompt", "p"); putJsonArray("modes") { add("WORD") } })
                }
            }
        }
        badRequest { mergeTuiqiaoConfig(withModels.tuiqiao, tooMany, withModels) }
    }

    private val roleSlot = TuiqiaoSlot("r1", TuiqiaoRole(id = Uuid.parse("00000000-0000-0000-0000-000000000001"), name = "旁白", prompt = "p"),
        1, "p", Tuiqiao.MarkupPolicy.LENIENT)
    private val defaultSlot = TuiqiaoSlot(TuiqiaoSlot.DEFAULT_KEY, null, 2, "prompt", Tuiqiao.MarkupPolicy.STRICT)
    private val prepared = TuiqiaoPrepared(3, 4, "推", TuiqiaoMode.WORD, TuiqiaoFormat.MARKDOWN, listOf(defaultSlot))

    private fun candidates(vararg c: TuiqiaoCandidate) = flowOf(*c.map { TuiqiaoEvent.Candidate(TuiqiaoSlot.DEFAULT_KEY, it) }.toTypedArray())

    @Test
    fun `stream sends prepared, candidates and done`() = runBlocking {
        val events = mutableListOf<Pair<String, String>>()
        runTuiqiaoStream(prepared, candidates(TuiqiaoCandidate("敲", "清脆", "有声"), TuiqiaoCandidate("叩", "郑重"))) { name, data ->
            events += name to data
        }
        assertEquals(listOf("prepared", "candidate", "candidate", "done"), events.map { it.first })
        assertEquals("""{"start":3,"end":4,"original":"推","mode":"WORD","format":"MARKDOWN","slots":[{"slot":"default","kind":"default","roleId":null,"name":null}]}""", events[0].second)
        assertEquals("""{"slot":"default","text":"敲","tone":"清脆","why":"有声"}""", events[1].second)
        assertEquals("""{"slot":"default","text":"叩","tone":"郑重","why":""}""", events[2].second)
    }

    @Test
    fun `a failed slot is reported alone and the stream still finishes`() = runBlocking {
        val events = mutableListOf<Pair<String, String>>()
        val twoSlots = prepared.copy(slots = listOf(roleSlot, defaultSlot))
        runTuiqiaoStream(twoSlots, flowOf(
            TuiqiaoEvent.SlotFailed("r1", TuiqiaoException.Reason.MARKUP_BROKEN, null),
            TuiqiaoEvent.Candidate(TuiqiaoSlot.DEFAULT_KEY, TuiqiaoCandidate("敲", "")),
        )) { n, d -> events += n to d }
        assertEquals(listOf("prepared", "slot_error", "candidate", "done"), events.map { it.first })
        assertTrue(events[0].second.contains("""{"slot":"r1","kind":"role","roleId":"00000000-0000-0000-0000-000000000001","name":"旁白"}"""))
        assertEquals("""{"slot":"r1","code":"MARKUP_BROKEN","message":"MARKUP_BROKEN"}""", events[1].second)
    }

    @Test
    fun `stream reports tuiqiao and model errors with codes`() = runBlocking {
        val events = mutableListOf<Pair<String, String>>()
        runTuiqiaoStream(prepared, flowOf(TuiqiaoEvent.SlotFailed(TuiqiaoSlot.DEFAULT_KEY, TuiqiaoException.Reason.MARKUP_BROKEN, null))) { n, d -> events += n to d }
        assertEquals("error" to """{"code":"MARKUP_BROKEN","message":"MARKUP_BROKEN"}""", events.last())
        events.clear()
        runTuiqiaoStream(prepared, flowOf(TuiqiaoEvent.SlotFailed(TuiqiaoSlot.DEFAULT_KEY, null, "upstream 500 Authorization: Bearer sk-secretsecret"))) { n, d -> events += n to d }
        val (name, data) = events.last()
        assertEquals(listOf("prepared", "slot_error", "error"), events.map { it.first })
        assertTrue(!events[1].second.contains("sk-secretsecret"))
        assertEquals("error", name)
        assertTrue(data.contains("\"code\":\"MODEL_ERROR\""))
        assertTrue(!data.contains("sk-secretsecret"))
    }

    @Test
    fun `stream times out with an error event`() = runBlocking {
        val events = mutableListOf<String>()
        runTuiqiaoStream(prepared, flow<TuiqiaoEvent> { awaitCancellation() }, streamTimeout = 50.milliseconds) { n, _ -> events += n }
        assertEquals(listOf("prepared", "error"), events)
    }

    @Test
    fun `write failure propagates instead of becoming an error event`() = runBlocking {
        val events = mutableListOf<String>()
        try {
            runTuiqiaoStream(prepared, candidates(TuiqiaoCandidate("敲", ""))) { n, _ ->
                if (n == "candidate") throw IOException("client gone")
                events += n
            }
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals(listOf("prepared"), events)
    }
}
