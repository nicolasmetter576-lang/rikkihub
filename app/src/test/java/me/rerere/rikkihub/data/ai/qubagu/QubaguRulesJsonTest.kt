package me.rerere.rikkihub.data.ai.qubagu

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.tavern.regex.QuickJsRegexRuntime
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 规则文件（JSON v1）的解析、校验、导入合并，以及「App 原来内置的规则」导出成文件后再导入，
 * 在同一份语料上给出和原来内置引擎同样的改动（`golden-v1.json` 是删内置数据前用旧引擎录的）。
 */
class QubaguRulesJsonTest {
    companion object {
        private val runtime = QuickJsRegexRuntime()

        @AfterClass
        @JvmStatic
        fun close() = runtime.close()
    }

    private val engine = Qubagu(runtime)
    private fun resource(name: String) = checkNotNull(javaClass.getResource("/qubagu/$name")).readText()
    private val rulesFile by lazy { resource("rules-v1.json") }
    private val packs by lazy { QubaguRulesJson.decode(rulesFile) }

    // ---- 原内置规则导出成文件后再导入：效果不变 ----

    private fun configFor(name: String): QubaguConfig {
        val base = QubaguConfig(packs = packs, merge = true)
        return when (name) {
            "default" -> base
            "dialogue" -> base.copy(protectDialogue = false, protectCornerQuotes = false, protectDetails = false)
            else -> base.copy(packs = packs.map { if (it.id == name) it.copy(enabled = true) else it })
        }
    }

    /** 旧规则 id 对应的新规则 id 前缀：词包规则带上所在的包，句式规则拆成了好几条。 */
    private fun expectedKey(old: String, config: String): String = when {
        old.startsWith("pack:") -> {
            val id = old.removePrefix("pack:")
            val pack = if (id.startsWith("nsfw_")) config else packs.first { p -> p.id != config && p.rules.any { it.id == id } && !p.id.startsWith("explicit_") }.id
            "$pack/$id"
        }
        old == "negation" -> "sentence/negation_"
        old == "finale" -> "sentence/finale_"
        old == "quota" -> "quota/adverb_quota"
        else -> old
    }

    /** 带 `$1` 的候选只认字面部分：替换结果得能由某个候选拼出来。 */
    private fun producibleBy(after: String, candidates: List<String>): Boolean = candidates.ifEmpty { listOf("") }.any { template ->
        val pattern = template.split(Regex("\\$[1-9]")).joinToString("[\\s\\S]*") { Regex.escape(it) }
        Regex(pattern).matches(after)
    }

    @Test
    fun formerBuiltInRulesBehaveTheSameAfterImport() = runBlocking {
        val cases = Json.parseToJsonElement(resource("golden-v1.json")).jsonArray
        val problems = mutableListOf<String>()
        var compared = 0
        for (case in cases) {
            val obj = case.jsonObject
            val configName = obj.getValue("config").jsonPrimitive.content
            val text = obj.getValue("text").jsonPrimitive.content
            val config = configFor(configName)
            val expected = obj.getValue("edits").jsonArray.map { it.jsonObject }
            val actual = engine.clean(text, config).edits
            val label = "[$configName] ${text.take(30).replace('\n', '⏎')}"
            if (expected.size != actual.size) {
                problems += "$label: ${expected.size} edits expected, got ${actual.size}: ${actual.map { it.before }}"
                continue
            }
            for ((want, got) in expected.zip(actual)) {
                compared++
                val start = want.getValue("s").jsonPrimitive.int
                val before = want.getValue("b").jsonPrimitive.content
                val after = want.getValue("a").jsonPrimitive.content
                val key = expectedKey(want.getValue("r").jsonPrimitive.content, configName)
                if (got.start != start || got.before != before) problems += "$label: edit at $start '$before' became ${got.start} '${got.before}'"
                if (!got.rule.startsWith(key)) problems += "$label: '$before' was ${want["r"]} → expected $key*, got ${got.rule}"
                when (val rule = config.rule(got.rule)) {
                    // 碎句合并把行里别的改动并进来，那些改动挑的替换词随规则 id 变了，只比位置
                    null -> Unit
                    is QubaguRule.Quota -> if (got.after != after) problems += "$label: '$before' → '${got.after}', expected '$after'"
                    is QubaguRule.Replace -> {
                        val deterministic = rule.replacements.size <= 1
                        if (deterministic && got.after != after) problems += "$label: '$before' → '${got.after}', expected '$after'"
                        if (!producibleBy(got.after, rule.replacements)) problems += "$label: '${got.after}' is not one of ${rule.replacements}"
                    }
                }
            }
        }
        if (problems.isNotEmpty()) fail("${problems.size} mismatches (of $compared edits):\n" + problems.take(40).joinToString("\n"))
        assertTrue(compared > 600)
    }

    @Test
    fun ruleFileHasEveryFormerBuiltInPackWithExplicitTiersOff() {
        assertEquals(
            listOf("sentence", "expression", "simile", "jargon", "punctuation", "explicit_plain", "explicit_erotic", "explicit_vulgar", "quota"),
            packs.map { it.id },
        )
        assertEquals(listOf("explicit_plain", "explicit_erotic", "explicit_vulgar"), packs.filterNot { it.enabled }.map { it.id })
        assertTrue(packs.all { pack -> pack.rules.all { it.enabled && it.refinePrompt.isEmpty() } })
        assertEquals(emptyList<String>(), runBlocking { QubaguRulesJson.checkRegex(packs, engine).invalid })
    }

    // ---- 解析与校验 ----

    @Test
    fun encodeThenDecodeKeepsEverything() {
        val edited = packs.take(2).map { pack ->
            pack.copy(rules = pack.rules.mapIndexed { i, rule ->
                when (rule) {
                    is QubaguRule.Replace -> rule.copy(refinePrompt = "写具体动作 $i", enabled = i % 2 == 0)
                    is QubaguRule.Quota -> rule.copy(max = 3)
                }
            })
        } + QubaguRulePack(id = "mine", name = "我的", rules = listOf(QubaguRule.Quota(id = "q", words = listOf("微微"), max = 0, refinePrompt = "x")))
        assertEquals(edited, QubaguRulesJson.decode(QubaguRulesJson.encode(edited)))
    }

    @Test
    fun missingOptionalFieldsTakeDefaults() {
        val decoded = QubaguRulesJson.decode(
            """{"type":"rikkihub.qubagu.rules","version":1,"packs":[{"id":"p","rules":[{"kind":"replace","id":"r","find":"缓缓"},{"kind":"quota","id":"q","words":["微微"]}]}]}"""
        )
        val pack = decoded.single()
        assertTrue(pack.enabled)
        assertEquals(QubaguRule.Replace(id = "r", find = "缓缓"), pack.rules[0])
        assertEquals(QubaguRule.Quota(id = "q", words = listOf("微微")), pack.rules[1])
    }

    private fun reasonOf(text: String): QubaguRulesException.Reason? = try {
        QubaguRulesJson.decode(text)
        null
    } catch (e: QubaguRulesException) {
        e.reason
    }

    private fun file(packs: String, type: String = "rikkihub.qubagu.rules", version: Int = 1) =
        """{"type":"$type","version":$version,"packs":$packs}"""

    @Test
    fun rejectsFilesThatAreNotRuleFiles() {
        val cases = mapOf(
            "not json" to ("瞳孔地震" to QubaguRulesException.Reason.NOT_JSON),
            "array" to ("[]" to QubaguRulesException.Reason.NOT_JSON),
            "other type" to (file("[]", type = "rikkihub.character") to QubaguRulesException.Reason.WRONG_TYPE),
            "no version" to ("""{"type":"rikkihub.qubagu.rules","packs":[]}""" to QubaguRulesException.Reason.WRONG_TYPE),
            "newer version" to (file("[]", version = 2) to QubaguRulesException.Reason.NEWER_VERSION),
            "unknown kind" to (file("""[{"id":"p","rules":[{"kind":"magic","id":"r"}]}]""") to QubaguRulesException.Reason.INVALID),
            "packs not a list" to (file("{}") to QubaguRulesException.Reason.INVALID),
            "duplicate pack ids" to (file("""[{"id":"p"},{"id":"p"}]""") to QubaguRulesException.Reason.INVALID),
            "blank pack id" to (file("""[{"id":" "}]""") to QubaguRulesException.Reason.INVALID),
            "duplicate rule ids" to (file("""[{"id":"p","rules":[{"kind":"replace","id":"r","find":"a"},{"kind":"quota","id":"r","words":["b"]}]}]""") to QubaguRulesException.Reason.INVALID),
            "empty find" to (file("""[{"id":"p","rules":[{"kind":"replace","id":"r","find":""}]}]""") to QubaguRulesException.Reason.INVALID),
            "find too long" to (file("""[{"id":"p","rules":[{"kind":"replace","id":"r","find":"${"a".repeat(QubaguRule.MAX_FIND_CHARS + 1)}"}]}]""") to QubaguRulesException.Reason.INVALID),
            "quota max out of range" to (file("""[{"id":"p","rules":[{"kind":"quota","id":"q","words":["a"],"max":11}]}]""") to QubaguRulesException.Reason.INVALID),
            "refine prompt too long" to (file("""[{"id":"p","rules":[{"kind":"quota","id":"q","words":["a"],"refinePrompt":"${"x".repeat(QubaguRule.MAX_REFINE_PROMPT_CHARS + 1)}"}]}]""") to QubaguRulesException.Reason.INVALID),
            "too many packs" to (file((0..QubaguConfig.MAX_PACKS).joinToString(",", "[", "]") { """{"id":"p$it"}""" }) to QubaguRulesException.Reason.INVALID),
        )
        val wrong = cases.mapNotNull { (name, case) ->
            val got = reasonOf(case.first)
            if (got != case.second) "$name: expected ${case.second}, got $got" else null
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
        assertEquals(null, reasonOf(file("[]")))
    }

    @Test
    fun brokenRegexRulesAreImportedSwitchedOffAndListed() = runBlocking {
        val packs = listOf(
            QubaguRulePack(
                id = "p", name = "包",
                rules = listOf(
                    QubaguRule.Replace(id = "ok", name = "好的", find = "瞳孔(地震)", regex = true),
                    QubaguRule.Replace(id = "bad", name = "坏的", find = "瞳孔(", regex = true),
                    QubaguRule.Replace(id = "literal", find = "瞳孔(", regex = false),
                    QubaguRule.Quota(id = "q", words = listOf("(")),
                ),
            ),
        )
        val checked = QubaguRulesJson.checkRegex(packs, engine)
        assertEquals(listOf("包 › 坏的"), checked.invalid)
        assertEquals(listOf(true, false, true, true), checked.packs.single().rules.map { it.enabled })
    }

    // ---- 导入合并 ----

    private fun pack(id: String, name: String = id) = QubaguRulePack(id = id, name = name)

    @Test
    fun mergeAppendsNewPacksAndResolvesConflictsByChoice() {
        val existing = listOf(pack("a", "旧 A"), pack("b", "旧 B"), pack("c", "旧 C"))
        val imported = listOf(pack("b", "新 B"), pack("d", "新 D"), pack("a", "新 A"), pack("c", "新 C"))
        val choices = mapOf("a" to QubaguImportChoice.OVERWRITE, "b" to QubaguImportChoice.COPY, "c" to QubaguImportChoice.SKIP)
        val asked = mutableListOf<String>()
        val merged = QubaguRulesJson.merge(existing, imported) { asked += it.id; choices.getValue(it.id) }
        assertEquals(listOf("b", "a", "c"), asked)
        assertEquals(listOf("新 A", "旧 B", "旧 C", "新 B (2)", "新 D"), merged.map { it.name })
        assertEquals(listOf("a", "b", "c"), merged.take(3).map { it.id })
        val copy = merged[3]
        assertNotEquals("b", copy.id)
        assertEquals("d", merged[4].id)
        assertEquals(null, QubaguRulesJson.validate(merged))
    }

    @Test
    fun mergeWithoutConflictsNeverAsks() {
        val merged = QubaguRulesJson.merge(listOf(pack("a")), listOf(pack("b"))) { error("asked about ${it.id}") }
        assertEquals(listOf("a", "b"), merged.map { it.id })
    }

    // ---- 规则查找与只认结尾 ----

    @Test
    fun ruleKeysResolveToThePackRule() {
        val config = QubaguConfig(packs = packs)
        assertEquals("眼神地震/骤缩", config.rule("expression/phys_pupil_quake")?.name)
        assertEquals(null, config.rule("simile/phys_pupil_quake"))
        assertEquals(null, config.rule("phys_pupil_quake"))
        assertTrue(config.endsText("sentence/finale_sentence"))
        assertFalse(config.endsText("sentence/negation_not_but"))
        assertFalse(config.endsText(QubaguRules.MERGE))
    }

    @Test
    fun activeRulesFollowPackAndRuleSwitchesInOrder() {
        val config = QubaguConfig(
            packs = listOf(
                QubaguRulePack(id = "x", rules = listOf(QubaguRule.Replace(id = "1", find = "a"), QubaguRule.Replace(id = "2", find = "b", enabled = false))),
                QubaguRulePack(id = "y", enabled = false, rules = listOf(QubaguRule.Replace(id = "3", find = "c"))),
                QubaguRulePack(id = "z", rules = listOf(QubaguRule.Quota(id = "4", words = listOf("d")))),
            ),
        )
        assertEquals(listOf("x/1", "z/4"), config.activeRules.map { it.first })
        assertTrue(config.hasWork)
        assertFalse(QubaguConfig(packs = listOf(QubaguRulePack(id = "y", enabled = false, rules = listOf(QubaguRule.Replace(id = "3", find = "c"))))).hasWork)
        assertTrue(QubaguConfig(merge = true).hasWork)
    }
}
