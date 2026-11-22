package me.rerere.rikkihub.web.routes

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.ai.qubagu.Qubagu
import me.rerere.rikkihub.data.ai.qubagu.QubaguConfig
import me.rerere.rikkihub.data.ai.qubagu.QubaguMode
import me.rerere.rikkihub.data.ai.qubagu.QubaguRefine
import me.rerere.rikkihub.data.ai.qubagu.QubaguRule
import me.rerere.rikkihub.data.ai.qubagu.QubaguRulePack
import me.rerere.rikkihub.data.ai.qubagu.QubaguRulesJson
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.ApiException
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.tavern.regex.QuickJsRegexRuntime
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

class QubaguRoutesTest {
    companion object {
        private val runtime = QuickJsRegexRuntime()

        @AfterClass
        @JvmStatic
        fun close() = runtime.close()
    }

    private val engine = Qubagu(runtime)
    private val chatModel = Model(modelId = "chat")
    private val embeddingModel = Model(modelId = "embed", type = ModelType.EMBEDDING)
    private val settings = Settings(
        providers = listOf(ProviderSetting.OpenAI(apiKey = "k", baseUrl = "http://localhost", models = listOf(chatModel, embeddingModel))),
    )
    private val existing = QubaguConfig(packs = listOf(QubaguRulePack(id = "old", name = "旧", rules = listOf(QubaguRule.Replace(id = "r", find = "旧规则", replacements = listOf("新"))))))

    private fun merge(body: JsonObject, config: QubaguConfig = existing) = runBlocking { mergeQubaguConfig(config, body, settings, engine) }

    private fun assertBadRequest(body: JsonObject, config: QubaguConfig = existing) {
        try {
            merge(body, config)
            fail("expected BadRequestException for $body")
        } catch (expected: BadRequestException) {
            // ok
        }
    }

    private fun packs(vararg packs: QubaguRulePack): JsonElement = JsonInstant.encodeToJsonElement(packs.toList())

    @Test
    fun `only the keys in the body change`() {
        val merged = merge(buildJsonObject {
            put("enabled", true)
            put("mode", "REVIEW")
            put("merge", true)
            put("mergeMaxChars", 12)
            put("protectDialogue", false)
        })
        assertEquals(existing.copy(enabled = true, mode = QubaguMode.REVIEW, merge = true, mergeMaxChars = 12, protectDialogue = false), merged)
        assertEquals(existing, merge(JsonObject(emptyMap())))
    }

    @Test
    fun `unknown keys wrong types and out of range values are rejected`() {
        val bad = listOf(
            buildJsonObject { put("nope", true) },
            // 规则改成规则包以后，旧的键都不认了
            buildJsonObject { put("negation", true) },
            buildJsonObject { put("customRules", JsonArray(emptyList())) },
            buildJsonObject { put("quotaMax", 2) },
            buildJsonObject { put("mode", "SOMETIMES") },
            buildJsonObject { put("enabled", "yes") },
            buildJsonObject { put("mergeMaxChars", 1) },
            buildJsonObject { put("packs", "x") },
            buildJsonObject { put("mode", JsonNull) },
        )
        for (body in bad) assertBadRequest(body)
    }

    @Test
    fun `packs replace the whole list`() {
        val pack = QubaguRulePack(
            id = "new",
            name = "新包",
            rules = listOf(
                QubaguRule.Replace(id = "a", find = "/smirk(ed)?/i", regex = true, replacements = listOf("smiled"), refinePrompt = "写具体"),
                QubaguRule.Quota(id = "b", words = listOf("微微"), max = 1),
            ),
        )
        assertEquals(listOf(pack), merge(buildJsonObject { put("packs", packs(pack)) }).packs)
        assertTrue(merge(buildJsonObject { put("packs", JsonArray(emptyList())) }).packs.isEmpty())
    }

    @Test
    fun `broken packs are rejected`() {
        val rule = QubaguRule.Replace(id = "r", find = "a")
        val bad = mapOf(
            "duplicate pack ids" to packs(QubaguRulePack(id = "p"), QubaguRulePack(id = "p")),
            "duplicate rule ids" to packs(QubaguRulePack(id = "p", rules = listOf(rule, rule))),
            "empty find" to packs(QubaguRulePack(id = "p", rules = listOf(rule.copy(find = "")))),
            "find too long" to packs(QubaguRulePack(id = "p", rules = listOf(rule.copy(find = "字".repeat(QubaguRule.MAX_FIND_CHARS + 1))))),
            "broken regex" to packs(QubaguRulePack(id = "p", rules = listOf(rule.copy(find = "(未闭合", regex = true)))),
            "quota out of range" to packs(QubaguRulePack(id = "p", rules = listOf(QubaguRule.Quota(id = "q", words = listOf("a"), max = 11)))),
            "too many packs" to packs(*Array(QubaguConfig.MAX_PACKS + 1) { QubaguRulePack(id = "p$it") }),
        )
        val accepted = bad.filter { (_, value) ->
            try {
                merge(buildJsonObject { put("packs", value) })
                true
            } catch (_: BadRequestException) {
                false
            }
        }.keys
        assertTrue("accepted: $accepted", accepted.isEmpty())
        // 没开正则时括号就是字面量，不算错
        assertEquals("(未闭合", (merge(buildJsonObject { put("packs", packs(QubaguRulePack(id = "p", rules = listOf(rule.copy(find = "(未闭合"))))) }).packs.single().rules.single() as QubaguRule.Replace).find)
    }

    @Test
    fun `refine model must be an existing chat model unless it is the stored one`() {
        assertEquals(chatModel.id, merge(buildJsonObject { put("refineModelId", chatModel.id.toString()) }).refineModelId)
        assertBadRequest(buildJsonObject { put("refineModelId", embeddingModel.id.toString()) })
        assertBadRequest(buildJsonObject { put("refineModelId", Uuid.random().toString()) })
        assertBadRequest(buildJsonObject { put("refineModelId", "not-a-uuid") })
        val gone = existing.copy(refineModelId = Uuid.random())
        assertEquals(gone.refineModelId, merge(buildJsonObject { put("refineModelId", gone.refineModelId.toString()); put("refine", true) }, gone).refineModelId)
        assertNull(merge(buildJsonObject { put("refineModelId", JsonNull) }, gone).refineModelId)
    }

    @Test
    fun `refine prompt falls back to the default when blank or unchanged`() {
        assertEquals("自定义", merge(buildJsonObject { put("refinePrompt", "自定义") }).refinePrompt)
        val custom = existing.copy(refinePrompt = "自定义")
        assertNull(merge(buildJsonObject { put("refinePrompt", "  ") }, custom).refinePrompt)
        assertNull(merge(buildJsonObject { put("refinePrompt", JsonNull) }, custom).refinePrompt)
        assertNull(merge(buildJsonObject { put("refinePrompt", QubaguRefine.DEFAULT_PROMPT) }, custom).refinePrompt)
        assertBadRequest(buildJsonObject { put("refinePrompt", "字".repeat(QubaguConfig.MAX_REFINE_PROMPT_CHARS + 1)) })
    }

    @Test
    fun `projection carries the config with its packs and the default refine instructions`() {
        val projected = projectQubaguConfig(settings.copy(qubagu = existing))
        assertEquals(existing, projected.config)
        assertEquals(QubaguRefine.DEFAULT_PROMPT, projected.defaults.refinePrompt)
    }

    // ---- 导入 ----

    private fun errorCodeOf(text: String): String? = try {
        runBlocking { readQubaguRules(text, engine) }
        null
    } catch (e: ApiException) {
        e.errorCode ?: e.status.value.toString()
    }

    @Test
    fun `rule files that cannot be imported answer with a coded error`() {
        val wrong = mapOf(
            "瞳孔地震" to "QUBAGU_RULES_NOT_JSON",
            """{"type":"x","version":1,"packs":[]}""" to "QUBAGU_RULES_WRONG_TYPE",
            """{"type":"rikkihub.qubagu.rules","version":9,"packs":[]}""" to "QUBAGU_RULES_NEWER_VERSION",
            """{"type":"rikkihub.qubagu.rules","version":1,"packs":[{"id":"p"},{"id":"p"}]}""" to "QUBAGU_RULES_INVALID",
            "x".repeat(QUBAGU_MAX_IMPORT_CHARS + 1) to "400",
        ).filter { (text, code) -> errorCodeOf(text) != code }
        assertTrue("wrong: ${wrong.values}", wrong.isEmpty())
    }

    @Test
    fun `imported rules with a broken regex are switched off and listed`() = runBlocking {
        val text = QubaguRulesJson.encode(listOf(
            QubaguRulePack(id = "p", name = "包", rules = listOf(
                QubaguRule.Replace(id = "ok", find = "a"),
                QubaguRule.Replace(id = "bad", name = "坏", find = "(", regex = true),
            )),
        ))
        val imported = readQubaguRules(text, engine)
        assertEquals(listOf("包 › 坏"), imported.invalid)
        assertEquals(listOf(true, false), imported.packs.single().rules.map { it.enabled })
    }
}
