package me.rerere.ai.provider.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ToolNameResolver] 的窄公开 API 单测：accept 只返回本帧新确认的名称
 * 增量（单调 lcp 前缀），finish 返回收尾剩余增量（不是完整名）。
 */
class ToolNameResolverTest {
    private fun collect(
        registry: Set<String>,
        wireNames: List<String>,
    ): String {
        val resolver = ToolNameResolver(registry)
        val emitted = StringBuilder()
        wireNames.forEach { emitted.append(resolver.accept(it)) }
        emitted.append(resolver.finish())
        return emitted.toString()
    }

    @Test
    fun `true fragments accumulate through intermediate states`() {
        // 只注册 foofoo：foo→foo 是合法分片而非重发，逐帧确认
        assertEquals("foofoo", collect(setOf("foofoo"), listOf("foo", "foo")))
    }

    @Test
    fun `full resend collapses to single name`() {
        assertEquals(
            "search_web",
            collect(setOf("search_web"), listOf("search_web", "search_web")),
        )
    }

    @Test
    fun `cumulative snapshot emits only new suffix`() {
        val resolver = ToolNameResolver(setOf("search_web"))
        assertEquals("search", resolver.accept("search"))
        assertEquals("_web", resolver.accept("search_web"))
        assertEquals("", resolver.finish())
    }

    @Test
    fun `empty wire name is a no op and keeps state`() {
        val resolver = ToolNameResolver(setOf("workspace_write_file"))
        assertEquals("", resolver.accept(""))
        assertEquals("workspace_write_file", resolver.accept("workspace_write_file"))
        assertEquals("", resolver.accept(""))
        assertEquals("", resolver.finish())
    }

    @Test
    fun `later fragment disambiguates overlapping registry names`() {
        // {foo, foofoobar} + foo→foo→bar：第二个 foo 歧义时未发射，bar 到来
        // 候选收敛 {foofoobar}，被暂扣的确认增量 foo+bar=foobar 一次补齐，
        // finish 不再剩尾部。
        val resolver = ToolNameResolver(setOf("foo", "foofoobar"))
        assertEquals("foo", resolver.accept("foo"))
        assertEquals("", resolver.accept("foo"))
        assertEquals("foobar", resolver.accept("bar"))
        assertEquals("", resolver.finish())
    }

    @Test
    fun `overlap converges to unique finished candidate not error`() {
        // {foo, foofoobar} + foo→foo：foofoo 仅是前缀不是完整候选 → foo
        val resolver = ToolNameResolver(setOf("foo", "foofoobar"))
        assertEquals("foo", resolver.accept("foo"))
        assertEquals("", resolver.accept("foo"))
        assertEquals("", resolver.finish())
    }

    @Test
    fun `two finished interpretations are ambiguous`() {
        // {foo, foofoo} + foo→foo：重发 foo 与分片 foofoo 都完整合法 → 拒绝
        val resolver = ToolNameResolver(setOf("foo", "foofoo"))
        resolver.accept("foo")
        resolver.accept("foo")
        val error = assertThrows(ToolStreamProtocolException::class.java) {
            resolver.finish()
        }
        assertTrue(error.code.contains("ambiguous"))
    }

    @Test
    fun `stale shorter snapshot after full name passes through literally`() {
        // 完整名收齐后又来一个更短的旧快照：候选全灭进入逐字透传（旧名/
        // 幻觉名不可执行，由执行端报 not found），透传名是逐字收束的
        // 拼接 literal 而不是协议错误，也绝不产出已确认前缀之外的解释。
        val resolver = ToolNameResolver(setOf("workspace_write_file"))
        assertEquals("workspace_write_file", resolver.accept("workspace_write_file"))
        assertEquals("", resolver.accept("workspace_"))
        assertEquals("workspace_", resolver.finish())
    }

    @Test
    fun `unregistered fragment passes through literally`() {
        // {search_web} 单发 zzz：首帧即零候选 → 透传模式，字面名原样收束
        val resolver = ToolNameResolver(setOf("search_web"))
        assertEquals("", resolver.accept("zzz"))
        assertEquals("zzz", resolver.finish())
    }

    @Test
    fun `unregistered name resent passes through literally`() {
        // 未注册名完整重发：透传模式下 accept 仍维护逐字名但不发射，
        // finish 返回字面全名一次（重发在逐字轨道上收敛为空增量）。
        val resolver = ToolNameResolver(setOf("search_web"))
        assertEquals("", resolver.accept("x_tool"))
        assertEquals("", resolver.accept("x_tool"))
        assertEquals("x_tool", resolver.finish())
    }

    @Test
    fun `empty registry passes any wire name through literally`() {
        // 空注册表下有工具调用：未注册名无法执行，literal 原样透传交给
        // 执行端报 not found，而不是协议硬失败。
        val resolver = ToolNameResolver(emptySet())
        assertEquals("", resolver.accept("search_web"))
        assertEquals("search_web", resolver.finish())
    }

    @Test
    fun `unregistered whole name passes through not halved`() {
        // wire 真发 N+N 且注册表只有 N：绝不自动截半成 N，literal 整体透传
        val resolver = ToolNameResolver(setOf("echo"))
        assertEquals("", resolver.accept("echoecho"))
        assertEquals("echoecho", resolver.finish())
    }

    @Test
    fun `unresolved stream keeps tracking literal until finish`() {
        // {foofoobar} + foo→foo：终态候选 {foo, foofoo} 均不在注册表 →
        // 零候选兜底，literal 收束为 foofoo 透传（未注册，不可执行），
        // 不凭空补出注册名尾巴。
        val resolver = ToolNameResolver(setOf("foofoobar"))
        assertEquals("foo", resolver.accept("foo"))
        assertEquals("", resolver.accept("foo"))
        assertEquals("foo", resolver.finish())
    }

    @Test
    fun `registered doubled-looking name emits whole`() {
        val resolver = ToolNameResolver(setOf("echo", "echoecho"))
        assertEquals("echoecho", resolver.accept("echoecho"))
        assertEquals("", resolver.finish())
    }

    @Test
    fun `oversized wire fragment is rejected`() {
        val resolver = ToolNameResolver(setOf("search_web"))
        assertThrows(ToolStreamProtocolException::class.java) {
            resolver.accept("x".repeat(513))
        }
    }

    @Test
    fun `literal pass through is bounded`() {
        // 透传不等于无界：逐字名累计超 1024 仍抛 tool_name_too_long
        val resolver = ToolNameResolver(setOf("search_web"))
        resolver.accept("x".repeat(512))
        resolver.accept("y".repeat(512))
        val error = assertThrows(ToolStreamProtocolException::class.java) {
            resolver.accept("z")
        }
        assertTrue(error.code.contains("too_long"))
    }

    @Test
    fun `accept after finish is rejected`() {
        val resolver = ToolNameResolver(setOf("search_web"))
        resolver.accept("search_web")
        resolver.finish()
        assertThrows(ToolStreamProtocolException::class.java) {
            resolver.accept("x")
        }
    }

    @Test
    fun `finish is idempotent`() {
        // resolver 不能补全未收到的字符：终态候选 {foofoo}∩R=∅（foofoo
        // 只是注册名前缀不是完整候选），走 literal 兜底补发剩余尾部。
        val resolver = ToolNameResolver(setOf("foofoo"))
        assertEquals("foo", resolver.accept("foo"))
        assertEquals("", resolver.accept("foo"))
        assertEquals("foo", resolver.finish())
        assertEquals("", resolver.finish())
    }
}
