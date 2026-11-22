package me.rerere.tavern.regex

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与真酒馆的差分（golden 由 tavern/oracle/regex/record_regex.js 在 ST 1.19.0 上录制）。
 *
 * 覆盖清单：
 * - runRegexScript：数字组、命名组、{{match}}（含大小写）、$0、trimStrings（含宏）、替换串与查找串里的宏、
 *   无斜杠模式、非法模式、非法标志（整串当模式）、PCRE 标志被拒、未参与匹配的组、disabled、空查找、
 *   全局空匹配、孤立花括号 `{{user}}`、无界后行断言、CJK 空白、替换串里的 `$$ $& $\``（回调结果不再展开）、
 *   substituteRegex 三种模式、sticky 非全局、Unicode 属性。
 * - getRegexedString：markdownOnly/promptOnly/普通三类与 isMarkdown/isPrompt 的组合、isEdit、放置位置、
 *   minDepth/maxDepth（含 -1、0、边界）、禁用、多脚本串联顺序。
 * 未覆盖：脚本来源（全局/预设/角色）与允许名单（由调用方在拼列表时处理，不属于引擎）；`$N` 超出组数（有意偏离上游缺陷）。
 */
class RegexEngineGoldenTest {
    private val runtime = QuickJsRegexRuntime()
    private val engine = RegexEngine(runtime)

    @After fun tearDown() = runtime.close()

    /** 对应真酒馆里 name1 = User、name2 = Seraphina，变量 x = "a.b"。 */
    private val host = object : RegexMacroHost {
        override fun substitute(text: String, nameOverride: String?, postProcess: ((String) -> String)?): String {
            val pp = postProcess ?: { it }
            return text
                .replace("{{user}}", pp("User"))
                .replace("{{char}}", pp(nameOverride ?: "Seraphina"))
                .replace("{{getvar::x}}", pp("a.b"))
        }
    }

    private fun golden(): JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/golden/regex/regex.json")!!.readText()).jsonObject

    @Test fun runRegexScriptMatchesSillyTavern() = runBlocking {
        val cases = golden()["run"]!!.jsonArray
        assertTrue(cases.size >= 25)
        val failures = mutableListOf<String>()
        for (c in cases) {
            val o = c.jsonObject
            val script = RegexScript.fromJson(o["script"]!!.jsonObject)
            val raw = o["raw"]!!.jsonPrimitive.content
            val expected = o["expected"]!!.jsonPrimitive.content
            val got = engine.runRegexScript(script, raw, host)
            if (got != expected) failures += "${o["name"]!!.jsonPrimitive.content}: expected ${expected.quote()} got ${got.quote()}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun getRegexedStringMatchesSillyTavern() = runBlocking {
        val cases = golden()["get"]!!.jsonArray
        assertTrue(cases.size >= 14)
        val failures = mutableListOf<String>()
        for (c in cases) {
            val o = c.jsonObject
            val scripts = (o["scripts"] as JsonArray).map { RegexScript.fromJson(it.jsonObject) }
            val p = o["params"]!!.jsonObject
            val params = RegexParams(
                isMarkdown = p["isMarkdown"]?.jsonPrimitive?.booleanOrNull ?: false,
                isPrompt = p["isPrompt"]?.jsonPrimitive?.booleanOrNull ?: false,
                isEdit = p["isEdit"]?.jsonPrimitive?.booleanOrNull ?: false,
                depth = p["depth"]?.jsonPrimitive?.intOrNull,
            )
            val got = engine.getRegexedString(
                scripts, o["raw"]!!.jsonPrimitive.content, o["placement"]!!.jsonPrimitive.content.toInt(), host, params,
            )
            val expected = o["expected"]!!.jsonPrimitive.content
            if (got != expected) failures += "${o["name"]!!.jsonPrimitive.content}: expected $expected got $got"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun scriptJsonRoundTripKeepsUnknownFields() {
        val src = Json.parseToJsonElement(
            """{"id":"1","scriptName":"n","findRegex":"/a/g","replaceString":"b","trimStrings":["x"],"placement":[1,2],
               "disabled":false,"markdownOnly":true,"promptOnly":false,"runOnEdit":true,"substituteRegex":2,
               "minDepth":null,"maxDepth":3,"futureField":{"k":[1,2]}}""",
        ).jsonObject
        val script = RegexScript.fromJson(src)
        assertEquals(3, script.maxDepth)
        assertEquals(null, script.minDepth)
        assertEquals(setOf("futureField"), script.extra.keys)
        assertEquals(src, script.toJson())
    }

    @Test fun sanitizeRegexMacroEscapesMetacharacters() {
        assertEquals("a\\.b\\*\\(c\\)\\[d\\]\\{e\\}\\\\\\/\\|\\^\\$\\?\\+", sanitizeRegexMacro("a.b*(c)[d]{e}\\/|^$?+"))
        assertEquals("\\n\\r\\t\\v\\f\\0", sanitizeRegexMacro("\n\r\t\u000B\u000C\u0000"))
    }

    private fun String.quote() = "\"" + replace("\n", "\\n") + "\""
}
