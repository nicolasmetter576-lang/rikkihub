package me.rerere.tavern.prompt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 提示词组装对真 SillyTavern 1.19.0 的差分测试。golden 由 tavern/oracle/prompt/record_*.js 在真酒馆里
 * 走 sc.Generate 的真发送路径录制：拦截 /api/backends/chat-completions/generate 的 fetch、返回假应答，
 * 录下请求体的 messages（含 squash）。做法详见 tavern/oracle/prompt/README.md。
 * 回放时组装器消费同样的输入（预设、选项、卡片字段、历史、getWorldInfoPrompt 结果、extension prompts），
 * 宏求值通过 PromptHost.substitute 查录制时同环境求出的 subs 对。
 *
 * golden 覆盖清单（各 record_*.js 顶部有对应主题表）。已录制并在 FILES 里的：
 *   smoke     默认预设冒烟（PromptSmokeTest）
 *   basic     默认预设 normal、squash 开关、空历史、names 三档、send_if_empty（normal 与 impersonate 两条路径）、
 *             自定义相对提示词、quiet、bias
 * 待录（录好一份就加进 FILES，不要先列名字）：
 *   markers   各 marker 禁用、绝对注入 depth/order/role 组合、injection_trigger、相对注入
 *   worldinfo 世界书 before/after/深度/示例/outlet 各落点、anBefore/anAfter 合并
 *   authors   作者注释位置/间隔/禁用/chara、persona 描述三种落点
 *   gentype   continue/impersonate/swipe/regenerate/quiet、例子 pin/strip、预算截断、bias、格式模板
 *
 * history 是 openai.js 组装器看到的聊天：真发送路径里 script.js:4455 会先把 send_if_empty 作为用户消息写进聊天，
 * 录制库把发送瞬间的聊天记成 history（见 oracle/prompt/lib.js），所以回放不需要在这里模拟 Generate 那一层。
 */
class PromptGoldenTest {
    private val FILES = listOf("basic", "markers", "authors", "worldinfo", "gentype")

    @Test
    fun goldens() {
        val failures = ArrayList<String>()
        var total = 0
        for (name in FILES) {
            val file = PromptGoldenSupport.load("golden/prompt/$name.json")
            for (c in file["cases"] as JsonArray) {
                val co = c as JsonObject
                val caseName = "$name/${co["name"]!!.jsonPrimitive.content}"
                total++
                try {
                    val r = PromptGoldenSupport.replay(co)
                    val result = PromptAssembler.assemble(r.input, r.host)
                    val got = PromptGoldenSupport.toWire(result, r.input, r.host)
                    val expected = r.expectedMessages.map { Triple(it.role, it.name, it.content) }
                    val actual = got.map { Triple(it.role, it.name, it.content) }
                    if (expected != actual) throw AssertionError(describeDiff(expected, actual))
                } catch (e: Throwable) {
                    failures.add("$caseName: ${e.message}")
                }
            }
        }
        assertTrue("golden/prompt ${failures.size}/$total failed:\n" + failures.joinToString("\n\n"), failures.isEmpty())
    }

    /** JUnit 的 compact diff 会折叠相同部分，长提示词看不出差在哪；这里报第几条、哪个字符开始不同。 */
    private fun describeDiff(
        expected: List<Triple<*, *, String>>,
        actual: List<Triple<*, *, String>>,
    ): String {
        val head = "ST ${expected.size} 条 / Kotlin ${actual.size} 条"
        val n = minOf(expected.size, actual.size)
        val i = (0 until n).firstOrNull { expected[it] != actual[it] }
            ?: return "$head；前 $n 条相同，多出的 ${if (expected.size > n) "ST" else "Kotlin"} 尾部: " +
                (if (expected.size > n) expected.drop(n) else actual.drop(n)).joinToString { "(${it.first}, ${it.second}, ${it.third.take(60)})" }
        val e = expected[i]
        val a = actual[i]
        val k = (0 until minOf(e.third.length, a.third.length)).firstOrNull { e.third[it] != a.third[it] } ?: minOf(e.third.length, a.third.length)
        fun around(s: String) = s.substring(maxOf(0, k - 50), minOf(s.length, k + 70)).replace("\n", "⏎")
        return "$head；第 $i 条不同 role ST=${e.first}/KT=${a.first} name ST=${e.second}/KT=${a.second}；内容自第 $k 字符起不同\n" +
            "  ST: …${around(e.third)}…\n  KT: …${around(a.third)}…"
    }
}
