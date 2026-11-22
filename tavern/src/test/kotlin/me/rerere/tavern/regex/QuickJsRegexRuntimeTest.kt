package me.rerere.tavern.regex

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.tavern.core.JsRegexSyntaxException
import me.rerere.tavern.core.JsRegexTimeoutException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QuickJsRegexRuntimeTest {
    private val runtime = QuickJsRegexRuntime()

    @After fun tearDown() = runtime.close()

    private fun res(name: String) = javaClass.getResource("/regex/$name")!!.readText()

    @Test fun matchAllReportsIndexGroupsAndNamedGroups() = runBlocking {
        val re = runtime.compile("(?<k>\\w+)@(\\w+)(x)?", "g")
        val ms = re.matchAll("bob@host and amy@home")
        assertEquals(2, ms.size)
        assertEquals(0, ms[0].index)
        assertEquals("bob@host", ms[0].text)
        assertEquals(listOf("bob", "host", null), ms[0].groups)
        assertEquals(mapOf<String, String?>("k" to "bob"), ms[0].named)
        assertEquals(13, ms[1].index)
    }

    @Test fun nonGlobalReturnsAtMostOneMatch() = runBlocking {
        assertEquals(1, runtime.compile("a", "").matchAll("aaa").size)
        assertTrue(runtime.compile("z", "").matchAll("aaa").isEmpty())
    }

    @Test fun testIgnoresLastIndexState() = runBlocking {
        val re = runtime.compile("a", "g")
        assertTrue(re.test("a"))
        assertTrue(re.test("a")) // 带 g 的 RegExp.test 在 JS 里会有 lastIndex 状态，这里每次从头
    }

    @Test fun syntaxErrorsAreReported() = runBlocking {
        try {
            runtime.compile("(", "")
            fail("expected JsRegexSyntaxException")
        } catch (_: JsRegexSyntaxException) {
        }
        try {
            runtime.compile("a", "zz")
            fail("expected JsRegexSyntaxException")
        } catch (_: JsRegexSyntaxException) {
        }
    }

    @Test fun runawayBacktrackingTimesOut() = runBlocking {
        val fast = QuickJsRegexRuntime(evalTimeoutMillis = 300)
        try {
            val re = fast.compile("^(a+)+$", "")
            try {
                re.test("a".repeat(40) + "b")
                fail("expected timeout")
            } catch (_: JsRegexTimeoutException) {
            }
            // 超时之后运行时还能继续用
            assertTrue(fast.compile("a", "").test("a"))
        } finally {
            fast.close()
        }
    }

    @Test fun compileCacheEvictsAndRecompilesTransparently() = runBlocking {
        val small = QuickJsRegexRuntime(cacheSize = 2)
        try {
            val a = small.compile("a", "")
            repeat(5) { small.compile("x$it", "") }
            assertTrue(a.test("a"))
        } finally {
            small.close()
        }
    }

    /**
     * 55 条常见写法（覆盖 Java/ICU 直译会出问题的情形），与 V8（node 22）比对“是否接受该写法”。
     * 替换结果的逐条一致（55/55）在选型时另行验证过。
     */
    @Test fun catalogueMatchesV8() = runBlocking {
        val cases = Json.parseToJsonElement(res("cases.json")).jsonArray
        val expected = Json.parseToJsonElement(res("node.out.json")).jsonObject
        val long = "a".repeat(200_000)
        val long2 = "<a>" + "z".repeat(300_000) + "</a>"
        val engine = QuickJsRegexRuntime()
        val failures = mutableListOf<String>()
        try {
            for (c in cases) {
                val o = c.jsonObject
                val id = o["id"]!!.jsonPrimitive.content
                val input = when (val raw = o["in"]!!.jsonPrimitive.content) { "@LONG@" -> long; "@LONG2@" -> long2; else -> raw }
                val exp = expected[id]!!.jsonObject
                val expOk = exp["ok"]!!.jsonPrimitive.boolean
                val got: Result<String> = runCatching {
                    val re = engine.compile(o["pat"]!!.jsonPrimitive.content, o["flags"]!!.jsonPrimitive.content)
                    re.matchAll(input).joinToString("|") { "${it.index}:${it.text.take(40)}" }
                }
                if (expOk != got.isSuccess) failures += "$id: V8 ok=$expOk, ours ok=${got.isSuccess} ${got.exceptionOrNull()?.message ?: ""}"
            }
        } finally {
            engine.close()
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
