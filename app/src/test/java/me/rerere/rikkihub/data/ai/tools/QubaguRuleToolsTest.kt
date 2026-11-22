package me.rerere.rikkihub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.qubagu.Qubagu
import me.rerere.rikkihub.data.ai.qubagu.QubaguConfig
import me.rerere.rikkihub.data.ai.qubagu.QubaguRule
import me.rerere.rikkihub.data.ai.qubagu.QubaguRulePack
import me.rerere.rikkihub.data.ai.qubagu.QubaguRulesJson
import me.rerere.rikkihub.data.files.SkillFrontmatterParser
import me.rerere.tavern.regex.QuickJsRegexRuntime
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 去八股技能的读 / 测 / 写工具，以及技能包本身（说明书能解析、范例就是那套规则）。 */
class QubaguRuleToolsTest {
    companion object {
        private val runtime = QuickJsRegexRuntime()

        @AfterClass
        @JvmStatic
        fun close() = runtime.close()
    }

    private val engine = Qubagu(runtime)

    private val throat = QubaguRule.Replace(id = "throat", name = "喉结", find = "喉结滚动", replacements = listOf("顿了顿"))
    private val sigh = QubaguRule.Replace(id = "sigh", find = "叹了口气", replacements = listOf("沉默"))
    private var config = QubaguConfig(
        packs = listOf(
            QubaguRulePack(id = "a", name = "甲", rules = listOf(throat)),
            QubaguRulePack(id = "b", name = "乙", rules = listOf(sigh)),
        ),
    )
    private var saves = 0
    private val tools = createQubaguRuleTools(engine, { config }) { packs ->
        saves++
        config = config.copy(packs = packs)
    }

    private fun call(id: String, args: JsonObject): String = runBlocking {
        (tools.single { it.name == id }.execute(args).single() as UIMessagePart.Text).text
    }

    private fun json(id: String, args: JsonObject = JsonObject(emptyMap())) = Json.parseToJsonElement(call(id, args)).jsonObject

    private fun packJson(pack: QubaguRulePack): JsonElement =
        Json.parseToJsonElement(QubaguRulesJson.encode(listOf(pack))).jsonObject.getValue("packs").jsonArray.single()

    private fun packsArg(vararg packs: QubaguRulePack) = JsonArray(packs.map(::packJson))

    private fun JsonObject.ids(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.error() = this["error"]?.jsonPrimitive?.content

    @Test
    fun readGivesAnOverviewOrOnePackAsARulesFile() {
        val overview = json(BuiltinToolIds.QUBAGU_RULES_READ)
        assertFalse(overview.getValue("chatCleanupOn").jsonPrimitive.boolean)
        val packs = overview.getValue("packs").jsonArray.map { it.jsonObject }
        assertEquals(listOf("a", "b"), packs.map { it.getValue("id").jsonPrimitive.content })
        assertEquals("throat", packs[0].getValue("rules").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)

        val pack = call(BuiltinToolIds.QUBAGU_RULES_READ, buildJsonObject { put("pack", "a") })
        assertEquals(listOf(config.packs[0]), QubaguRulesJson.decode(pack))

        val missing = json(BuiltinToolIds.QUBAGU_RULES_READ, buildJsonObject { put("pack", "zzz") }).error()
        assertTrue(missing, missing!!.contains("a, b"))
    }

    @Test
    fun testRunsCandidatesTheWayAWriteWouldWithoutSaving() {
        val text = "他喉结滚动，叹了口气。"
        val current = json(BuiltinToolIds.QUBAGU_RULES_TEST, buildJsonObject { put("text", text) })
        assertEquals("他顿了顿，沉默。", current.getValue("result").jsonPrimitive.content)
        assertEquals("a/throat", current.getValue("edits").jsonArray[0].jsonObject.getValue("rule").jsonPrimitive.content)
        assertEquals("喉结", current.getValue("edits").jsonArray[0].jsonObject.getValue("ruleName").jsonPrimitive.content)

        // 同 id 的候选包替换当前包；候选包关着也照跑
        val candidate = QubaguRulePack(id = "b", enabled = false, rules = listOf(sigh.copy(replacements = listOf("摇了摇头"))))
        val merged = json(BuiltinToolIds.QUBAGU_RULES_TEST, buildJsonObject {
            put("text", text)
            put("packs", packsArg(candidate))
        })
        assertEquals("他顿了顿，摇了摇头。", merged.getValue("result").jsonPrimitive.content)

        val only = json(BuiltinToolIds.QUBAGU_RULES_TEST, buildJsonObject {
            put("text", text)
            put("packs", packsArg(candidate))
            put("only", true)
        })
        assertEquals("他喉结滚动，摇了摇头。", only.getValue("result").jsonPrimitive.content)
        assertEquals(0, saves)
    }

    @Test
    fun testReportsABrokenRegexOrOversizedText() {
        val broken = QubaguRulePack(id = "x", rules = listOf(QubaguRule.Replace(id = "bad", find = "(", regex = true)))
        val error = json(BuiltinToolIds.QUBAGU_RULES_TEST, buildJsonObject {
            put("text", "随便")
            put("packs", packsArg(broken))
        }).error()
        assertTrue(error, error!!.contains("Rule 'bad' in pack 'x'"))
        assertTrue(json(BuiltinToolIds.QUBAGU_RULES_TEST, buildJsonObject { put("text", "字".repeat(20_001)) }).error() != null)
    }

    @Test
    fun writeAlwaysNeedsApproval() {
        val write = tools.single { it.name == BuiltinToolIds.QUBAGU_RULES_WRITE }
        assertTrue(write.needsApproval(buildJsonObject { putJsonArray("delete") { add(JsonPrimitive("a")) } }))
        assertTrue(write.needsApproval(JsonObject(emptyMap())))
        assertFalse(tools.single { it.name == BuiltinToolIds.QUBAGU_RULES_READ }.needsApproval(JsonObject(emptyMap())))
    }

    @Test
    fun writeReplacesInPlaceAppendsNewPacksDeletesAndReorders() {
        val replaced = QubaguRulePack(id = "a", name = "甲2", rules = listOf(throat.copy(replacements = listOf("咽了咽"))))
        val added = QubaguRulePack(id = "c", name = "丙")
        val first = json(BuiltinToolIds.QUBAGU_RULES_WRITE, buildJsonObject { put("packs", packsArg(added, replaced)) })
        assertEquals(listOf("c"), first.ids("created"))
        assertEquals(listOf("a"), first.ids("replaced"))
        assertEquals(listOf("a", "b", "c"), config.packs.map { it.id })
        assertEquals(replaced, config.packs[0])

        val second = json(BuiltinToolIds.QUBAGU_RULES_WRITE, buildJsonObject {
            putJsonArray("delete") { add(JsonPrimitive("b")) }
            putJsonArray("order") { add(JsonPrimitive("c")); add(JsonPrimitive("a")) }
        })
        assertEquals(listOf("c", "a"), second.ids("order"))
        assertEquals(listOf("c", "a"), config.packs.map { it.id })
        assertEquals(2, saves)
    }

    @Test
    fun anInvalidWriteIsRejectedWholeAndSavesNothing() {
        fun rejected(args: JsonObject): String {
            val before = config
            val error = json(BuiltinToolIds.QUBAGU_RULES_WRITE, args).error()
            assertEquals(before, config)
            return checkNotNull(error) { "accepted: $args" }
        }
        val good = QubaguRulePack(id = "c")
        val broken = QubaguRulePack(id = "d", rules = listOf(QubaguRule.Replace(id = "bad", find = "[", regex = true)))
        assertTrue(rejected(buildJsonObject { put("packs", packsArg(good, broken)) }).contains("Invalid regex"))
        assertTrue(rejected(buildJsonObject { putJsonArray("delete") { add(JsonPrimitive("zzz")) } }).contains("zzz"))
        assertTrue(rejected(buildJsonObject {
            put("packs", packsArg(QubaguRulePack(id = "a")))
            putJsonArray("delete") { add(JsonPrimitive("a")) }
        }).contains("at once"))
        assertTrue(rejected(buildJsonObject { putJsonArray("order") { add(JsonPrimitive("b")) } }).contains("every pack id"))
        assertTrue(rejected(buildJsonObject {
            putJsonArray("order") { add(JsonPrimitive("a")); add(JsonPrimitive("a")) }
        }).contains("every pack id"))
        assertTrue(rejected(buildJsonObject { put("packs", JsonPrimitive("a")) }).contains("array"))
        assertTrue(rejected(buildJsonObject {
            put("packs", JsonArray(listOf(buildJsonObject { put("id", "e"); put("name", "名".repeat(101)) })))
        }).contains("too long"))
        val many = (1..QubaguConfig.MAX_PACKS - 1).map { QubaguRulePack(id = "p$it") }.toTypedArray()
        assertTrue(rejected(buildJsonObject { put("packs", packsArg(*many)) }).contains("At most"))
        assertTrue(rejected(JsonObject(emptyMap())).contains("Nothing to change"))
        assertEquals(0, saves)
    }

    private fun asset(path: String) = sequenceOf(File("app/src/main/assets/builtin_skills/qubagu-rules/$path"),
        File("src/main/assets/builtin_skills/qubagu-rules/$path")).first { it.exists() }

    @Test
    fun theSkillParsesAndItsExamplesAreTheWholeRuleSetPackByPack() {
        val content = asset("SKILL.md").readText()
        val frontmatter = SkillFrontmatterParser.parse(content)
        assertEquals(QUBAGU_RULES_SKILL, frontmatter["name"])
        assertTrue(requireNotNull(frontmatter["description"]).length <= MAX_SKILL_DESCRIPTION_LENGTH)
        // 说明书里写的工具名就是目录里的工具 id
        for (id in listOf(BuiltinToolIds.QUBAGU_RULES_READ, BuiltinToolIds.QUBAGU_RULES_TEST, BuiltinToolIds.QUBAGU_RULES_WRITE)) {
            assertTrue(id, content.contains("`$id`"))
        }
        val linked = Regex("\\]\\((examples/[^)]+)\\)").findAll(content).map { it.groupValues[1] }.toList()
        assertEquals(linked.toSet(), asset("examples").listFiles()!!.map { "examples/${it.name}" }.toSet())

        val expected = QubaguRulesJson.decode(checkNotNull(javaClass.getResource("/qubagu/rules-v1.json")).readText())
        val byId = linked.map { QubaguRulesJson.decode(asset(it).readText()).single() }.associateBy { it.id }
        assertEquals(expected.map { it.id }.toSet(), byId.keys)
        for (pack in expected) assertEquals(pack.id, pack, byId[pack.id])
        assertNull(QubaguRulesJson.validate(expected))
    }
}
