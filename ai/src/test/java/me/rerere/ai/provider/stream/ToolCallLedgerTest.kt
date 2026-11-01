package me.rerere.ai.provider.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ToolCallLedger] 路由规则的单元级契约测试。
 *
 * SSE 层无法触达的部分（尤其是已 End 调用被新 index 复用：Chat decoder
 * 只在流收尾才发 End，End 之后不再有路由机会）直接在账本层断言。
 */
class ToolCallLedgerTest {
    private fun ledger() = ToolCallLedger()

    @Test
    fun `same real id across two open indexes fails closed`() {
        val ledger = ledger()
        val first = ledger.route(index = 0, wireCallId = "call_1", anonymousInPacket = 0, syntheticId = "s0")
        assertTrue(first.created)
        first.call.started = true

        val e = assertThrows(ToolStreamProtocolException::class.java) {
            ledger.route(index = 1, wireCallId = "call_1", anonymousInPacket = 0, syntheticId = "s1")
        }
        assertEquals("tool_call_id_reused_across_index", e.code)
        // 拒绝不产生任何路由副作用：第二个 index 没有获得映射
        val byId = ledger.route(index = null, wireCallId = "call_1", anonymousInPacket = 0, syntheticId = "s2")
        assertSame(first.call, byId.call)
        assertFalse(byId.created)
        assertEquals(1, ledger.all().size)
    }

    @Test
    fun `ended call id reused by a new index fails closed`() {
        // 同一真实 id 被另一个 index 占用时，即使该调用已 End 也拒绝：
        // 收编会把新调用的名称/参数拼进已结束 part，alias 命中已 End 调用
        // 也绝不复活（规则 4）。
        val ledger = ledger()
        val first = ledger.route(index = 0, wireCallId = "call_1", anonymousInPacket = 0, syntheticId = "s0")
        first.call.started = true
        first.call.ended = true

        val e = assertThrows(ToolStreamProtocolException::class.java) {
            ledger.route(index = 1, wireCallId = "call_1", anonymousInPacket = 0, syntheticId = "s1")
        }
        assertEquals("tool_call_id_reused_across_index", e.code)

        // 无 index 直接命中已 End 调用同样是协议错误（不复活）
        val ended = assertThrows(ToolStreamProtocolException::class.java) {
            ledger.route(index = null, wireCallId = "call_1", anonymousInPacket = 0, syntheticId = "s2")
        }
        assertEquals("tool_call_after_end", ended.code)
        assertEquals(1, ledger.all().size)
    }

    @Test
    fun `explicit index annexes a previously index-less call`() {
        // 无 index 创建的调用（alias 路由）拿到首个显式 index 是身份补全：
        // 收编后 index/id 双通，不算跨 index 冲突。
        val ledger = ledger()
        val created = ledger.route(index = null, wireCallId = "call_1", anonymousInPacket = 0, syntheticId = "s0")
        assertTrue(created.created)
        assertNull(created.call.index)

        val annexed = ledger.route(index = 2, wireCallId = "call_1", anonymousInPacket = 0, syntheticId = "s1")
        assertSame(created.call, annexed.call)
        assertFalse(annexed.created)

        // 收编后 index 路由也命中同一调用
        val byIndex = ledger.route(index = 2, wireCallId = null, anonymousInPacket = 0, syntheticId = "s2")
        assertSame(created.call, byIndex.call)
        assertEquals(1, ledger.all().size)
    }

    @Test
    fun `same index late real id binds in place once`() {
        val ledger = ledger()
        val first = ledger.route(index = 0, wireCallId = null, anonymousInPacket = 0, syntheticId = "s0")
        assertTrue(first.created)
        first.call.started = true

        val bound = ledger.route(index = 0, wireCallId = "real-1", anonymousInPacket = 0, syntheticId = "s1")
        assertSame(first.call, bound.call)
        assertEquals("real-1", bound.assignedId)

        // 同 id 重发 no-op；换一个不同非空 id 冲突
        val again = ledger.route(index = 0, wireCallId = "real-1", anonymousInPacket = 0, syntheticId = "s2")
        assertNull(again.assignedId)
        assertThrows(ToolStreamProtocolException::class.java) {
            ledger.route(index = 0, wireCallId = "other", anonymousInPacket = 0, syntheticId = "s3")
        }
    }

    @Test
    fun `anonymous element with multiple open calls fails closed`() {
        val ledger = ledger()
        ledger.route(index = 0, wireCallId = "a", anonymousInPacket = 0, syntheticId = "s0")
        ledger.route(index = 1, wireCallId = "b", anonymousInPacket = 0, syntheticId = "s1")
        assertThrows(ToolStreamProtocolException::class.java) {
            ledger.route(index = null, wireCallId = null, anonymousInPacket = 1, syntheticId = "s2")
        }
    }

    @Test
    fun `route after finish fails closed`() {
        val ledger = ledger()
        ledger.route(index = 0, wireCallId = "a", anonymousInPacket = 0, syntheticId = "s0")
        ledger.markClosed()
        assertThrows(ToolStreamProtocolException::class.java) {
            ledger.route(index = 0, wireCallId = "a", anonymousInPacket = 0, syntheticId = "s1")
        }
    }
}
