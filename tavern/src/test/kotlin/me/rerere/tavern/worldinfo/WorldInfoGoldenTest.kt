package me.rerere.tavern.worldinfo

/*
 * 世界书引擎对真 SillyTavern 1.19.0 的差分测试。golden 由 tavern/oracle/worldinfo/record_*.js 在真酒馆里录制
 * （检查入口 world-info.js:checkWorldInfo，与 getWorldInfoPrompt 的返回映射一致），这里用同样的输入跑 Kotlin 实现，
 * 逐项比对 prompt（七个字段）、activated（顺序、书、uid、hash、位置、求值后的内容）、计时状态、宏调用顺序、随机数消耗次数。
 * 多步用例把上一步的计时状态喂给下一步。
 *
 * golden 覆盖清单（各 record_*.js 顶部有对应主题表）：
 *   smoke    冒烟 + dryRun 自洽（checkWorldInfo 与 getWorldInfoPrompt 映射一致）
 *   matching 关键词匹配、扫描缓冲、选择逻辑、装饰器、触发器、角色过滤、全局扫描数据
 *   recursion 递归开关、exclude/prevent/delay-until-recursion、最大步数、最少激活数扩深
 *   budget   token 预算/上限、ignoreBudget、概率与预算交互、CJK、宏替换后计数
 *   groups   包含组：加权随机（含边界）、groupOverride、useGroupScoring 全局/条目级、
 *            平局回退随机、单成员/空名/禁用、已激活组整组压掉、多组成员资格
 *   timed    sticky/cooldown/delay 多步序列与失效规则、dryRun 不推进不应用、
 *            timedInit 播种的各清理分支、sticky×组、sticky 免掷概率
 *   output   七种 position 的产出落点、atDepth 的 depth/role 分组与顺序、outlet 具名/无名、
 *            order 排序（升序拼接）、跨书同序、内容被正则清空、minDepth 只挡 atDepth
 * 世界书/条目 JSON 往返无损在 WorldInfoCodecRoundTripTest。
 */

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.tavern.regex.QuickJsRegexRuntime
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class WorldInfoGoldenTest(
    @Suppress("unused") private val label: String,
    private val case: JsonObject,
    private val user: String,
    private val char: String,
) {
    companion object {
        /** 录制过的 golden 文件，按主题。 */
        val FILES = listOf("smoke", "matching", "recursion", "budget", "groups", "timed", "output")

        private val regex = QuickJsRegexRuntime()

        @AfterClass @JvmStatic
        fun closeRegex() = regex.close()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Array<Any>> = FILES.filter { GoldenSupport::class.java.getResource("/golden/worldinfo/$it.json") != null }
            .flatMap { file ->
                val g = GoldenSupport.load(file)
                g.getValue("cases").jsonArray.map { c ->
                    arrayOf<Any>(
                        "$file/${c.jsonObject.getValue("name").jsonPrimitive.content}",
                        c.jsonObject,
                        g.getValue("user").jsonPrimitive.content,
                        g.getValue("char").jsonPrimitive.content,
                    )
                }
            }
    }

    private fun assertJson(what: String, expected: JsonElement?, actual: JsonElement?) {
        if (expected != actual) {
            throw AssertionError("[$label] $what 不一致\n期望: $expected\n实际: $actual")
        }
    }

    @Test
    fun matchesRealSillyTavern() = runBlocking {
        val c = GoldenSupport.Case(case.getValue("input").jsonObject)
        val host = GoldenSupport.RecordingHost(regex, c.regexScripts, user, char)
        val engine = WorldInfoEngine(host)
        val expectedSteps = case.getValue("steps").jsonArray
        assertEquals(expectedSteps.size, c.steps.size)

        var timed = c.timedInit
        c.steps.forEachIndexed { i, step ->
            host.trace.setLength(0)
            host.seq = step.random
            host.used = 0
            val res = engine.check(
                chat = step.chat,
                books = c.books,
                settings = c.settings,
                maxContext = c.maxContext,
                timedEffects = timed,
                generationType = c.trigger,
                dryRun = step.dryRun,
                globalScanData = c.globalScan,
                character = c.character,
                injectedScanTexts = c.injected,
                dryRunProbability = DryRunProbability.ROLL, // 差分测试与上游一致：预演也掷骰
            )
            timed = res.timedEffects

            val exp = expectedSteps[i].jsonObject
            val where = "第 ${i + 1} 步"
            assertJson("$where prompt", exp.getValue("prompt"), GoldenSupport.promptJson(res.prompt))
            assertJson(
                "$where activated",
                exp.getValue("activated"),
                JsonArray(
                    res.activated.map {
                        JsonObject(
                            linkedMapOf(
                                "world" to JsonPrimitive(it.book),
                                "uid" to JsonPrimitive(it.uid),
                                "hash" to JsonPrimitive(it.hash),
                                "position" to JsonPrimitive(it.position),
                                "content" to JsonPrimitive(it.content),
                            ),
                        )
                    },
                ),
            )
            assertJson("$where timedWorldInfo", exp.getValue("timed"), res.timedEffects.toJson())
            assertEquals("$where 宏调用顺序(trace)", exp.getValue("trace").jsonPrimitive.content, host.trace.toString())
            assertEquals("$where Math.random 取用次数", exp.getValue("randomUsed").jsonPrimitive.content.toInt(), host.used)
        }
    }
}
