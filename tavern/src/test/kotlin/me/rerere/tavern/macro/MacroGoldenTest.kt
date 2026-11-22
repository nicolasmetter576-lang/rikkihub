package me.rerere.tavern.macro

import me.rerere.tavern.core.ChatLine
import me.rerere.tavern.core.FixedTavernClock
import me.rerere.tavern.core.TavernRandom
import me.rerere.tavern.variable.VariableScope
import me.rerere.tavern.variable.Variables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * 宏引擎差分 golden（上游 SillyTavern 1.19.0 `experimental_macro_engine`，`substituteParams` 直录）。
 *
 * 覆盖清单（每文件 ≥1 例）：
 * - basic.json：全部名字宏（含单人 group/groupNotMuted/notChar/charIfNotGroup）、大小写不敏感、
 *   角色卡 14 字段、model、maxPrompt/maxContext/maxResponse、isMobile/hasExtension/lastGenerationType/input、space/newline/noop/reverse、
 *   注释（内联/作用域/别名）、trim（独立/作用域/# flag）、未知宏（纯/含嵌套）、
 *   未配对开/闭标签、`\{` 转义、legacy 前缀（<USER>/<BOT>/<CHAR>/<GROUP>/{{time_UTC}}）、
 *   原文一空串、original 一次性、outlet、聊天检视链、swipe、firstIncluded/firstDisplayed、mesExamples；
 * - flow.json：{{if}} 真/假/else 双分支、! 反转、变量与宏名条件、嵌套、作用域缩进 trim 与 # 保空白、
 *   单参数作用域（`{{if true::x}}` 是一整条含冒号的参数！）、else 独立、未注册作用域对、
 *   胜负分支的副作用差异、参数内嵌宏、深层嵌套、求值顺序、空文档/未终止/空宏；
 * - vars.json：18 个变量宏 + 7 个别名、对象值 JSON 输出、键/索引路径、本地/全局隔离、
 *   简写 16 操作符（含 ||= ??= && ?? 惰性）、副作用顺序，varsAfter 终态断言；
 * - time.json：固定时钟（2025-03-04T05:06:07Z）+ oracle 时区下的 time/date/weekday/iso、
 *   {{time::UTC±N}}、非法偏移回退、datetimeformat（含本地化令牌）、timeDiff 前后缀、
 *   idleDuration（sendDateMillis 驱动/空聊天/无用户消息/别名）；
 * - random.json：roll 定序/多骰/简写/非法、random（allow 断言 ∈ 候选）、pick 的确定性
 *   （同位置同结果、不同位置不同种子、reroll 种子、逗号单参、内容哈希因子）。
 *
 * 回放：按 case.env 快照重建 [MacroEnv]（时钟/随机注入），断言 expected；`allow` 用例只断言成员。
 * 偏离上游的已注 `偏离:`（见 MacroEnv/CoreMacros/TimeMacros）——`{{random}}`/`{{pick}}` 的随机源、
 * `{{mesExamples}}` 的 instruct 分支、isMobile/hasExtension/firstDisplayedMessageId 的注入化、
 * `{{if}}` 条件为已注册 0 参宏名时先求值。
 */
class MacroGoldenTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val engine = MacroEngine()

    @Test fun basic() = runFile("basic")
    @Test fun flow() = runFile("flow")
    @Test fun vars() = runFile("vars")
    @Test fun time() = runFile("time")
    @Test fun random() = runFile("random")

    private fun runFile(name: String) {
        val file = File(javaClass.getResource("/golden/macro/$name.json")!!.toURI())
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val cases = root["cases"]!!.jsonArray
        val failures = ArrayList<String>()
        for (el in cases) {
            val c = el.jsonObject
            val cname = c["name"]!!.jsonPrimitive.content
            try {
                runCase(c)
            } catch (e: Throwable) {
                failures.add("$cname: ${e.message}")
            }
        }
        assertTrue("golden/$name failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun runCase(c: JsonObject) {
        val input = c["input"]!!.jsonPrimitive.content
        val env = buildEnv(c)
        val actual = engine.evaluate(input, env)

        val allow = c["allow"]?.jsonArray?.map { it.jsonPrimitive.content }
        if (allow != null) {
            assertTrue("case ${c["name"]}: '$actual' not in $allow", actual in allow)
            return
        }
        assertEquals("case ${c["name"]}", c["expected"]!!.jsonPrimitive.content, actual)

        c["varsAfter"]?.jsonObject?.let { after ->
            assertEquals("case ${c["name"]} varsAfter.local",
                after["local"]!!.jsonObject.toString(), env.variables.local.toJson().toString())
            assertEquals("case ${c["name"]} varsAfter.global",
                after["global"]!!.jsonObject.toString(), env.variables.global.toJson().toString())
        }
    }

    private fun buildEnv(c: JsonObject): MacroEnv {
        val e = c["env"]!!.jsonObject
        val names = e["names"]!!.jsonObject
        val ch = e["character"]!!.jsonObject
        val vars = e["variables"]!!.jsonObject

        // case.random → 固定序列 TavernRandom
        val randSeq = c["random"]?.jsonArray?.map { it.jsonPrimitive.double }
        var randIdx = 0
        val random = TavernRandom {
            if (randSeq == null) 0.0 else {
                if (randIdx >= randSeq.size) error("random sequence exhausted")
                randSeq[randIdx++]
            }
        }

        val zone = ZoneId.of(e["timezone"]?.jsonPrimitive?.content ?: "UTC")

        return MacroEnv(
            content = e["content"]!!.jsonPrimitive.content,
            names = MacroEnvNames(
                user = names["user"]!!.jsonPrimitive.content,
                char = names["char"]!!.jsonPrimitive.content,
                group = names["group"]!!.jsonPrimitive.content,
                groupNotMuted = names["groupNotMuted"]!!.jsonPrimitive.content,
                notChar = names["notChar"]!!.jsonPrimitive.content,
            ),
            character = MacroEnvCharacter(
                charPrompt = ch["charPrompt"]!!.jsonPrimitive.content,
                charInstruction = ch["charInstruction"]!!.jsonPrimitive.content,
                description = ch["description"]!!.jsonPrimitive.content,
                personality = ch["personality"]!!.jsonPrimitive.content,
                scenario = ch["scenario"]!!.jsonPrimitive.content,
                persona = ch["persona"]!!.jsonPrimitive.content,
                mesExamplesRaw = ch["mesExamplesRaw"]!!.jsonPrimitive.content,
                charDepthPrompt = ch["charDepthPrompt"]!!.jsonPrimitive.content,
                creatorNotes = ch["creatorNotes"]!!.jsonPrimitive.content,
                version = ch["version"]!!.jsonPrimitive.content,
                firstMessage = ch["firstMessage"]!!.jsonPrimitive.content,
                alternateGreetings = ch["alternateGreetings"]!!.jsonArray.map { it.jsonPrimitive.content },
            ),
            model = e["model"]!!.jsonPrimitive.content,
            isMobile = e["isMobile"]!!.jsonPrimitive.boolean,
            lastGenerationType = e["lastGenerationType"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content ?: "",
            firstIncludedMessageId = e["firstIncludedMessageId"]?.jsonPrimitive?.longOrNull?.toInt(),
            firstDisplayedMessageId = e["firstDisplayedMessageId"]?.jsonPrimitive?.longOrNull?.toInt(),
            input = e["input"]!!.jsonPrimitive.content,
            maxPromptTokens = e["maxPromptTokens"]!!.jsonPrimitive.long.toInt(),
            maxContextTokens = e["maxContextTokens"]!!.jsonPrimitive.long.toInt(),
            maxResponseTokens = e["maxResponseTokens"]!!.jsonPrimitive.long.toInt(),
            outletEntries = e["outlet"]!!.jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } },
            chat = e["chat"]!!.jsonArray.map { m ->
                val mo = m.jsonObject
                ChatLine(
                    name = mo["name"]!!.jsonPrimitive.content,
                    text = mo["text"]!!.jsonPrimitive.content,
                    isUser = mo["isUser"]!!.jsonPrimitive.boolean,
                    isSystem = mo["isSystem"]!!.jsonPrimitive.boolean,
                    swipeId = mo["swipeId"]!!.jsonPrimitive.long.toInt(),
                    swipeCount = mo["swipeCount"]!!.jsonPrimitive.long.toInt(),
                    sendDateMillis = mo["sendDateMillis"]?.jsonPrimitive?.longOrNull,
                )
            },
            variables = Variables(
                local = VariableScope.fromJson(vars["local"] as? JsonObject),
                global = VariableScope.fromJson(vars["global"] as? JsonObject),
            ),
            pickRerollSeed = e["pickRerollSeed"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content,
            chatId = e["chatId"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content,
            chatIdHash = e["chatIdHash"]?.jsonPrimitive?.doubleOrNull,
            original = e["original"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content?.let { o -> { o } },
            clock = FixedTavernClock(FIXED_MILLIS, zone),
            random = random,
        )
    }

    companion object {
        // oracle 录制时冻结的时钟（__st.freezeClock('2025-03-04T05:06:07Z')）
        private val FIXED_MILLIS = Instant.parse("2025-03-04T05:06:07Z").toEpochMilli()
    }
}
