package me.rerere.tavern.macro

import me.rerere.tavern.core.ChatLine
import me.rerere.tavern.core.FixedTavernClock
import me.rerere.tavern.variable.Variables
import me.rerere.tavern.core.TavernRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 冒烟单测：手验的核心语义（非 golden）。golden 差分见 MacroGoldenTest。
 */
class MacroSmokeTest {
    private fun env(content: String, vararg extra: Pair<String, Any>): MacroEnv = MacroEnv(
        content = content,
        names = MacroEnvNames.solo(user = "User", char = "Seraphina"),
        clock = FixedTavernClock(Instant.parse("2025-03-04T05:06:07Z").toEpochMilli(), ZoneId.of("UTC")),
        random = TavernRandom { 0.0 },
    )

    @Test
    fun namesAndUtility() {
        assertEquals("Hello Seraphina, I am User", MacroEngine().evaluate("Hello {{char}}, I am {{user}}", env("")))
        assertEquals("a b", MacroEngine().evaluate("a{{space}}b", env("")))
        assertEquals("a\nb", MacroEngine().evaluate("a{{newline}}b", env("")))
        assertEquals("ab", MacroEngine().evaluate("a{{noop}}b", env("")))
        assertEquals("cba", MacroEngine().evaluate("{{reverse::abc}}", env("")))
    }

    @Test
    fun unknownMacroKeepsSyntaxWithResolvedArgs() {
        assertEquals("{{mystery::Seraphina}}", MacroEngine().evaluate("{{mystery::{{char}}}}", env("")))
        assertEquals("{{/if}}", MacroEngine().evaluate("{{/if}}", env("")))
    }

    @Test
    fun comments() {
        assertEquals("ab", MacroEngine().evaluate("a{{// a comment}}b", env("")))
        assertEquals("ab", MacroEngine().evaluate("a{{//}}this is\ncommented{{///}}b", env("")))
    }

    @Test
    fun ifElseBlocks() {
        assertEquals("yes", MacroEngine().evaluate("{{if true}}yes{{else}}no{{/if}}", env("")))
        assertEquals("no", MacroEngine().evaluate("{{if false}}yes{{else}}no{{/if}}", env("")))
        assertEquals("yes", MacroEngine().evaluate("{{if !false}}yes{{else}}no{{/if}}", env("")))
        assertEquals("", MacroEngine().evaluate("{{if false}}yes{{/if}}", env("")))
        // 嵌套 if
        assertEquals("inner", MacroEngine().evaluate("{{if true}}{{if true}}inner{{else}}mid{{/if}}{{else}}outer{{/if}}", env("")))
    }

    @Test
    fun variables() {
        val e = env("")
        val eng = MacroEngine()
        assertEquals("foo", eng.evaluate("{{setvar::a::foo}}{{getvar::a}}", e))
        assertEquals("1", eng.evaluate("{{incvar::n}}", e))
        assertEquals("2", eng.evaluate("{{incvar::n}}", e))
        assertEquals("1", eng.evaluate("{{decvar::n}}", e))
        assertEquals("true", eng.evaluate("{{hasvar::a}}", e))
        assertEquals("", eng.evaluate("{{deletevar::a}}", e))
        assertEquals("false", eng.evaluate("{{hasvar::a}}", e))
        // 对象值与键路径
        assertEquals("", eng.evaluate("{{setvarkey::o::k::v}}", e))
        assertEquals("v", eng.evaluate("{{getvarkey::o::k}}", e))
        // 简写
        assertEquals("", eng.evaluate("{{.x=hello}}", e))
        assertEquals("hello", eng.evaluate("{{.x}}", e))
        assertEquals("2", eng.evaluate("{{.n++}}", e))
        assertEquals("", eng.evaluate("{{.n+=2}}", e))
        assertEquals("4", eng.evaluate("{{.n}}", e))
        assertEquals("true", eng.evaluate("{{.n==4}}", e))
        // 全局
        assertEquals("g", eng.evaluate("{{setglobalvar::gv::g}}{{getglobalvar::gv}}", e))
    }

    @Test
    fun sideEffectOrderInOneString() {
        val e = env("")
        val out = MacroEngine().evaluate("{{setvar::a::1}}[{{getvar::a}}]{{setvar::a::2}}[{{getvar::a}}]", e)
        assertEquals("[1][2]", out)
    }

    @Test
    fun timeMacros() {
        val eng = MacroEngine()
        assertEquals("5:06 AM", eng.evaluate("{{time}}", env("")))
        assertEquals("March 4, 2025", eng.evaluate("{{date}}", env("")))
        assertEquals("Tuesday", eng.evaluate("{{weekday}}", env("")))
        assertEquals("05:06", eng.evaluate("{{isotime}}", env("")))
        assertEquals("2025-03-04", eng.evaluate("{{isodate}}", env("")))
        assertEquals("2025-03-04 05:06:07", eng.evaluate("{{datetimeformat::YYYY-MM-DD HH:mm:ss}}", env("")))
    }

    @Test
    fun rollDeterministic() {
        var calls = 0
        val seq = TavernRandom { listOf(0.0, 0.5, 0.999).getOrElse(calls++) { 0.0 } }
        val e = env("").copy(random = seq)
        val eng = MacroEngine()
        assertEquals("1", eng.evaluate("{{roll::1d6}}", e))
        assertEquals("4", eng.evaluate("{{roll::1d6}}", e))
        assertEquals("6", eng.evaluate("{{roll::1d6}}", e))
        assertEquals("5", eng.evaluate("{{roll::2d6+3}}", e))
    }

    @Test
    fun pickDeterministicSamePosition() {
        val e = MacroEnv(content = "{{pick::a::b::c}}", chatId = "chat-1", random = TavernRandom { 0.0 })
        val out1 = MacroEngine().evaluate("{{pick::a::b::c}}", e)
        assertEquals(out1, MacroEngine().evaluate("{{pick::a::b::c}}", e))
        // 不同位置 → 不同种子（不保证不同结果，但种子不同）
        val e2 = MacroEnv(content = "xx{{pick::a::b::c}}", chatId = "chat-1", random = TavernRandom { 0.0 })
        assertTrue(MacroEngine().evaluate("xx{{pick::a::b::c}}", e2) in listOf("xxa", "xxb", "xxc"))
    }

    @Test
    fun trimMacroAndScopedTrim() {
        assertEquals("ab", MacroEngine().evaluate("a\n{{trim}}\nb", env("")))
        assertEquals("x", MacroEngine().evaluate("{{trim}}  x  {{/trim}}", env("")))
    }

    @Test
    fun chatInspection() {
        val e = env("").copy(
            chat = listOf(
                ChatLine("Seraphina", isUser = false, isSystem = false, text = "greeting", sendDateMillis = 0L),
                ChatLine("User", isUser = true, isSystem = false, text = "hi", sendDateMillis = 0L),
                ChatLine("Seraphina", isUser = false, isSystem = false, text = "hello", sendDateMillis = 0L),
            ),
        )
        val eng = MacroEngine()
        assertEquals("hello", eng.evaluate("{{lastMessage}}", e))
        assertEquals("2", eng.evaluate("{{lastMessageId}}", e))
        assertEquals("hi", eng.evaluate("{{lastUserMessage}}", e))
        assertEquals("hello", eng.evaluate("{{lastCharMessage}}", e))
        assertEquals("0-2", eng.evaluate("{{allChatRange}}", e))
    }

    @Test
    fun originalIsOneShot() {
        var calls = 0
        val e = env("").copy(original = { if (calls++ == 0) "orig" else "" })
        assertEquals("orig-", MacroEngine().evaluate("{{original}}-{{original}}", e))
    }
}
