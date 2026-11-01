package me.rerere.ai.claudelife

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private fun testWarmRequest(): CacheWarmRequest = CacheWarmRequest(
    providerSetting = ProviderSetting.Claude(),
    messages = listOf(UIMessage.user("hi"), UIMessage.assistant("hello")),
    params = TextGenerationParams(model = Model(modelId = "m")),
    contextMessages = listOf(UIMessage.user("hi")),
)

/**
 * [CacheWarmer] 时序语义的单测。全部时间断言基于 [FakeWarmTicker] 手动推进
 * （无真实 sleep），协程运行在 [Dispatchers.Unconfined] scope 里，advance /
 * CompletableDeferred.complete 时内联恢复 → 行为确定。
 */
class CacheWarmerTest {

    private class Harness {
        var cfg = CacheKeepAliveConfig(enabled = true)
        val ticker = FakeWarmTicker(startMs = 1_000_000L)
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val replays = mutableListOf<Pair<Long, CacheWarmRequest>>()
        val cancelled = mutableListOf<CancellationException>()
        var nextResult: () -> CacheWarmReplayResult = { CacheWarmReplayResult.Succeeded() }
        var blocker: CompletableDeferred<CacheWarmReplayResult>? = null
        var endedCount = 0
        var contextReason: String? = null
        val isCurrent: () -> String? = { contextReason }
        val logs = CopyOnWriteArrayList<String>()
        var onLog: (() -> Unit)? = null
        val warmer = CacheWarmer(
            ticker = ticker,
            scope = scope,
            config = { cfg },
            replay = { req ->
                replays += ticker.nowMs() to req
                try {
                    blocker?.await() ?: nextResult()
                } catch (ce: CancellationException) {
                    cancelled += ce
                    throw ce
                }
            },
            log = { msg ->
                logs += msg
                onLog?.invoke()
            },
            onTaskEnded = { endedCount++ },
        )

        fun start(): Long {
            val t0 = ticker.nowMs()
            warmer.start(testWarmRequest(), warmer.newGeneration(), isCurrent)
            return t0
        }
    }

    private fun assertInactive(warmer: CacheWarmer): CacheWarmStatus.Inactive {
        val s = warmer.status.value
        assertTrue("expected Inactive but was $s", s is CacheWarmStatus.Inactive)
        return s as CacheWarmStatus.Inactive
    }

    // ---------- 1. disabled ----------

    @Test
    fun `disabled config creates no task on start`() {
        val h = Harness()
        h.cfg = CacheKeepAliveConfig(enabled = false)
        h.start()
        assertInactive(h.warmer)
        assertFalse(h.warmer.isActive)
        h.ticker.advanceBy(10 * 60_000L)
        assertEquals(0, h.replays.size)
    }

    // ---------- 2. first replay at exactly interval ----------

    @Test
    fun `first replay fires at exactly T0 plus 270s`() {
        val h = Harness()
        val t0 = h.start()
        assertEquals(CacheWarmStatus.Scheduled(t0 + 270_000L), h.warmer.status.value)

        h.ticker.advanceBy(269_999L)
        assertEquals(0, h.replays.size)

        h.ticker.advanceBy(1L)
        assertEquals(1, h.replays.size)
        assertEquals(t0 + 270_000L, replaysFirstTime(h))
    }

    private fun replaysFirstTime(h: Harness): Long = h.replays.first().first

    // ---------- 3. next schedule anchored at replay START ----------

    @Test
    fun `next schedule is anchored at replay start not completion`() {
        val h = Harness()
        val t0 = h.start()
        h.blocker = CompletableDeferred()

        h.ticker.advanceBy(270_000L)
        assertEquals(1, h.replays.size)
        assertEquals(CacheWarmStatus.Refreshing, h.warmer.status.value)

        // 重放在途 5 秒后才完成
        h.ticker.advanceBy(5_000L)
        h.blocker!!.complete(CacheWarmReplayResult.Succeeded())
        h.blocker = null

        // 锚点 = 重放开始侧(T0+270s)，不是完成侧(T0+275s)
        assertEquals(CacheWarmStatus.Scheduled(t0 + 540_000L), h.warmer.status.value)
    }

    // ---------- 4. custom interval ----------

    @Test
    fun `custom interval is used for first replay`() {
        val h = Harness()
        h.cfg = CacheKeepAliveConfig(enabled = true, intervalSeconds = 100)
        val t0 = h.start()
        assertEquals(CacheWarmStatus.Scheduled(t0 + 100_000L), h.warmer.status.value)

        h.ticker.advanceBy(99_999L)
        assertEquals(0, h.replays.size)
        h.ticker.advanceBy(1L)
        assertEquals(1, h.replays.size)
        assertEquals(t0 + 100_000L, h.replays[0].first)
    }

    // ---------- 5. late more than 15s ends the task ----------

    @Test
    fun `waking more than 15s late ends the task without replay`() {
        val h = Harness()
        val t0 = h.start()

        h.ticker.advanceTo(t0 + 270_000L + 15_001L)
        assertEquals(0, h.replays.size)
        assertInactive(h.warmer)
        assertFalse(h.warmer.isActive)
        assertEquals(1, h.endedCount)
    }

    @Test
    fun `waking exactly 15s late still replays`() {
        val h = Harness()
        val t0 = h.start()

        h.ticker.advanceTo(t0 + 270_000L + 15_000L)
        assertEquals(1, h.replays.size)
        assertEquals(t0 + 270_000L + 15_000L, h.replays[0].first)
        assertTrue(h.warmer.isActive)
    }

    // ---------- 6. replay failure ends the task ----------

    @Test
    fun `replay failure ends the task with no retry`() {
        val h = Harness()
        h.nextResult = { CacheWarmReplayResult.Failed("x") }
        h.start()

        h.ticker.advanceBy(270_000L)
        assertEquals(1, h.replays.size)
        assertInactive(h.warmer)
        assertFalse(h.warmer.isActive)
        assertEquals(1, h.endedCount)

        h.ticker.advanceBy(30 * 60_000L)
        assertEquals(1, h.replays.size)
    }

    // ---------- 7. failure blocks the generation ----------

    @Test
    fun `after failure same generation start is refused but new generation works`() {
        val h = Harness()
        h.nextResult = { CacheWarmReplayResult.Failed("x") }
        val gen = h.warmer.newGeneration()
        h.warmer.start(testWarmRequest(), gen, h.isCurrent)

        h.ticker.advanceBy(270_000L)
        assertInactive(h.warmer)

        // 同一轮次：拒绝
        h.warmer.start(testWarmRequest(), gen, h.isCurrent)
        val refused = assertInactive(h.warmer)
        assertTrue(refused.reason.contains("stopped earlier"))
        assertFalse(h.warmer.isActive)

        // 新轮次：允许重建
        h.nextResult = { CacheWarmReplayResult.Succeeded() }
        h.warmer.start(testWarmRequest(), h.warmer.newGeneration(), h.isCurrent)
        assertTrue(h.warmer.isActive)
        assertTrue(h.warmer.status.value is CacheWarmStatus.Scheduled)
    }

    // ---------- 8. new start cancels in-flight replay ----------

    @Test
    fun `new start cancels in flight replay and reschedules`() {
        val h = Harness()
        val t0 = h.start()
        h.blocker = CompletableDeferred()

        h.ticker.advanceBy(270_000L)
        assertEquals(1, h.replays.size)
        assertEquals(CacheWarmStatus.Refreshing, h.warmer.status.value)

        // 5 秒后真实请求开始侧再次 start（新轮次）→ 在途重放被取消
        h.ticker.advanceBy(5_000L)
        h.blocker = null
        val t1 = h.ticker.nowMs()
        h.warmer.start(testWarmRequest(), h.warmer.newGeneration(), h.isCurrent)

        assertEquals(1, h.cancelled.size)
        assertEquals(CacheWarmStatus.Scheduled(t1 + 270_000L), h.warmer.status.value)
        assertTrue(t1 == t0 + 275_000L)
    }

    // ---------- 9. max duration deadline ----------

    @Test
    fun `max duration deadline stops task after last allowed replay`() {
        val h = Harness()
        h.cfg = CacheKeepAliveConfig(
            enabled = true,
            intervalSeconds = 270,
            maxDurationMinutes = 10, // 600s
        )
        val t0 = h.start()

        h.ticker.advanceBy(270_000L)
        assertEquals(1, h.replays.size)
        assertEquals(CacheWarmStatus.Scheduled(t0 + 540_000L), h.warmer.status.value)

        // 第二次重放完成后，下一计划 T0+810s > 上限 T0+600s → 结束
        h.ticker.advanceBy(270_000L)
        assertEquals(2, h.replays.size)
        assertInactive(h.warmer)
        assertFalse(h.warmer.isActive)
        assertEquals(1, h.endedCount)

        h.ticker.advanceBy(600_000L)
        assertEquals(2, h.replays.size)
    }

    // ---------- 10. max duration reset by new start, not extended by replays ----------

    @Test
    fun `max duration is reset by a new start and not extended by replays`() {
        val h = Harness()
        h.cfg = CacheKeepAliveConfig(
            enabled = true,
            intervalSeconds = 270,
            maxDurationMinutes = 10,
        )
        val t0 = h.start()

        // 第一次重放 T0+270s 不延长上限（仍 T0+600s）
        h.ticker.advanceBy(270_000L)
        assertEquals(1, h.replays.size)

        // T0+500s 新的真实请求 → 新上限 T0+1100s、新锚点 T0+500s
        h.ticker.advanceTo(t0 + 500_000L)
        h.warmer.start(testWarmRequest(), h.warmer.newGeneration(), h.isCurrent)
        assertEquals(CacheWarmStatus.Scheduled(t0 + 770_000L), h.warmer.status.value)

        // T0+540s 不应再有重放（旧计划已被取消）
        h.ticker.advanceTo(t0 + 540_000L)
        assertEquals(1, h.replays.size)

        // 新计划 T0+770s 触发
        h.ticker.advanceTo(t0 + 770_000L)
        assertEquals(2, h.replays.size)
        assertEquals(t0 + 770_000L, h.replays[1].first)

        // 再下一次 T0+1040s ≤ 新上限 T0+1100s → 仍然重放（上限被新 start 重置了）
        h.ticker.advanceTo(t0 + 1_040_000L)
        assertEquals(3, h.replays.size)

        // 下一次计划 T0+1310s > 新上限 → 结束
        assertInactive(h.warmer)
    }

    // ---------- 11. config disabled while scheduled ----------

    @Test
    fun `onConfigChanged with disabled config ends the task and re enabling does not resume`() {
        val h = Harness()
        h.start()
        assertTrue(h.warmer.isActive)

        h.cfg = h.cfg.copy(enabled = false)
        h.warmer.onConfigChanged()
        assertInactive(h.warmer)
        assertFalse(h.warmer.isActive)
        assertEquals(1, h.endedCount)

        // 重新启用 + onConfigChanged 不恢复已结束的任务
        h.cfg = h.cfg.copy(enabled = true)
        h.warmer.onConfigChanged()
        assertInactive(h.warmer)
        h.ticker.advanceBy(30 * 60_000L)
        assertEquals(0, h.replays.size)
        assertEquals(1, h.endedCount)
    }

    // ---------- 12. config disabled while replay in flight ----------

    @Test
    fun `onConfigChanged with disabled config cancels in flight replay`() {
        val h = Harness()
        h.start()
        h.blocker = CompletableDeferred()

        h.ticker.advanceBy(270_000L)
        assertEquals(1, h.replays.size)
        assertEquals(CacheWarmStatus.Refreshing, h.warmer.status.value)

        h.cfg = h.cfg.copy(enabled = false)
        h.warmer.onConfigChanged()

        assertEquals(1, h.cancelled.size)
        assertInactive(h.warmer)
        assertFalse(h.warmer.isActive)
        assertEquals(1, h.endedCount)
    }

    // ---------- 13. interval change keeps original anchor ----------

    @Test
    fun `interval change replans from the original anchor`() {
        val h = Harness()
        val t0 = h.start()
        assertEquals(CacheWarmStatus.Scheduled(t0 + 270_000L), h.warmer.status.value)

        h.ticker.advanceBy(100_000L)
        h.cfg = h.cfg.copy(intervalSeconds = 200)
        h.warmer.onConfigChanged()

        // 锚点仍是 T0 → 新计划 T0+200s
        assertEquals(CacheWarmStatus.Scheduled(t0 + 200_000L), h.warmer.status.value)

        h.ticker.advanceTo(t0 + 200_000L)
        assertEquals(1, h.replays.size)
        assertEquals(t0 + 200_000L, h.replays[0].first)
    }

    @Test
    fun `interval change making next plan too late in the past ends the task`() {
        val h = Harness()
        val t0 = h.start()

        h.ticker.advanceBy(100_000L)
        h.cfg = h.cfg.copy(intervalSeconds = 60)
        h.warmer.onConfigChanged()

        // 新计划 T0+60s 已过去 40s > 15s 容忍 → 结束
        assertInactive(h.warmer)
        assertFalse(h.warmer.isActive)
        assertEquals(1, h.endedCount)

        h.ticker.advanceBy(30 * 60_000L)
        assertEquals(0, h.replays.size)
    }

    // ---------- 14. context change ----------

    @Test
    fun `revalidate with stale context ends the task and blocks the generation`() {
        val h = Harness()
        val gen = h.warmer.newGeneration()
        h.warmer.start(testWarmRequest(), gen, h.isCurrent)

        h.contextReason = "changed"
        h.warmer.revalidate()

        assertInactive(h.warmer)
        assertFalse(h.warmer.isActive)
        assertEquals(1, h.endedCount)

        // 同一轮次 start 被拒绝
        h.contextReason = null
        h.warmer.start(testWarmRequest(), gen, h.isCurrent)
        assertInactive(h.warmer)
        assertFalse(h.warmer.isActive)
    }

    @Test
    fun `stale context at tick time prevents replay and ends the task`() {
        val h = Harness()
        h.start()

        h.contextReason = "changed"
        h.ticker.advanceBy(270_000L)

        assertEquals(0, h.replays.size)
        assertInactive(h.warmer)
        assertFalse(h.warmer.isActive)
        assertEquals(1, h.endedCount)
    }

    // ---------- 15. close ----------

    @Test
    fun `close ends the task and refuses all later starts`() {
        val h = Harness()
        val gen = h.warmer.newGeneration()
        h.warmer.start(testWarmRequest(), gen, h.isCurrent)
        assertTrue(h.warmer.isActive)

        h.warmer.close()
        assertTrue(h.warmer.isClosed)
        assertInactive(h.warmer)
        assertFalse(h.warmer.isActive)
        assertEquals(1, h.endedCount)

        // 任何轮次都被拒绝
        h.warmer.start(testWarmRequest(), gen, h.isCurrent)
        assertInactive(h.warmer)
        h.warmer.start(testWarmRequest(), h.warmer.newGeneration(), h.isCurrent)
        assertInactive(h.warmer)

        h.ticker.advanceBy(30 * 60_000L)
        assertEquals(0, h.replays.size)
    }

    // ---------- 16. stop without blocking generation ----------

    @Test
    fun `stop without blocking generation allows same generation restart`() {
        val h = Harness()
        val gen = h.warmer.newGeneration()
        val t0 = h.ticker.nowMs()
        h.warmer.start(testWarmRequest(), gen, h.isCurrent)

        h.warmer.stop("x", blockGeneration = false)
        assertInactive(h.warmer)
        assertFalse(h.warmer.isActive)
        assertEquals(1, h.endedCount)

        h.warmer.start(testWarmRequest(), gen, h.isCurrent)
        assertTrue(h.warmer.isActive)
        assertEquals(CacheWarmStatus.Scheduled(t0 + 270_000L), h.warmer.status.value)

        h.ticker.advanceBy(270_000L)
        assertEquals(1, h.replays.size)
    }

    // ---------- 17. replay throwing ----------

    @Test
    fun `replay throwing an exception is treated as failure`() {
        val h = Harness()
        h.nextResult = { throw RuntimeException("boom") }
        h.start()

        h.ticker.advanceBy(270_000L)
        assertEquals(1, h.replays.size)
        val inactive = assertInactive(h.warmer)
        assertTrue(inactive.reason.contains("replay failed"))
        assertFalse(h.warmer.isActive)

        h.ticker.advanceBy(30 * 60_000L)
        assertEquals(1, h.replays.size)
    }

    // ---------- 18. invalid config ----------

    @Test
    fun `invalid config refuses start`() {
        val h = Harness()
        // max 4 分钟 = 240s ≤ interval 270s
        h.cfg = CacheKeepAliveConfig(enabled = true, intervalSeconds = 270, maxDurationMinutes = 4)
        h.start()
        val inactive = assertInactive(h.warmer)
        assertTrue(inactive.reason.contains("invalid config"))
        assertFalse(h.warmer.isActive)

        h.ticker.advanceBy(30 * 60_000L)
        assertEquals(0, h.replays.size)
    }

    // ---------- 19. incompatible parameters ----------

    @Test
    fun `customBody max_tokens override refuses start`() {
        val h = Harness()
        val req = testWarmRequest().copy(
            params = TextGenerationParams(
                model = Model(modelId = "m"),
                customBody = listOf(CustomBody("max_tokens", JsonPrimitive(5))),
            )
        )
        h.warmer.start(req, h.warmer.newGeneration(), h.isCurrent)
        val inactive = assertInactive(h.warmer)
        assertTrue(inactive.reason.contains("parameter incompatible"))
        assertFalse(h.warmer.isActive)
    }

    @Test
    fun `customBody enabled thinking refuses start`() {
        val h = Harness()
        val req = testWarmRequest().copy(
            params = TextGenerationParams(
                model = Model(modelId = "m"),
                customBody = listOf(
                    CustomBody(
                        "thinking",
                        buildJsonObject {
                            put("type", "enabled")
                            put("budget_tokens", 1024)
                        }
                    )
                ),
            )
        )
        h.warmer.start(req, h.warmer.newGeneration(), h.isCurrent)
        val inactive = assertInactive(h.warmer)
        assertTrue(inactive.reason.contains("parameter incompatible"))
        assertFalse(h.warmer.isActive)
    }

    // ---------- 20. stale context refused at observer start side ----------

    @Test
    fun `stale context at start refuses the request and blocks the generation`() {
        val h = Harness()
        val gen = h.warmer.newGeneration()
        h.contextReason = "policyEpoch changed"
        h.warmer.start(testWarmRequest(), gen, h.isCurrent)

        val inactive = assertInactive(h.warmer)
        assertTrue(inactive.reason.contains("context changed"))
        assertFalse(h.warmer.isActive)

        // 同一轮次内后续 attempt 即使上下文恢复也被封锁（旧 observer 不能复活）
        h.contextReason = null
        h.warmer.start(testWarmRequest(), gen, h.isCurrent)
        val refused = assertInactive(h.warmer)
        assertTrue(refused.reason.contains("stopped earlier"))
        assertFalse(h.warmer.isActive)

        // 新轮次不受影响
        h.warmer.start(testWarmRequest(), h.warmer.newGeneration(), h.isCurrent)
        assertTrue(h.warmer.isActive)
    }

    @Test
    fun `stale context at start replaces the old run and still blocks`() {
        val h = Harness()
        val gen = h.warmer.newGeneration()
        h.warmer.start(testWarmRequest(), gen, h.isCurrent)
        assertTrue(h.warmer.isActive)

        // 同一轮次内第二个真实请求开始侧：快照已过期 → 旧 run 取消，不建新任务
        h.contextReason = "view changed"
        h.warmer.start(testWarmRequest(), gen, h.isCurrent)
        assertInactive(h.warmer)
        assertFalse(h.warmer.isActive)
        assertEquals(1, h.endedCount)

        h.contextReason = null
        h.warmer.start(testWarmRequest(), gen, h.isCurrent)
        assertInactive(h.warmer)
    }

    // ---------- 21. stop with no run blocks the current generation ----------

    @Test
    fun `stop with blockGeneration and no task still blocks current generation`() {
        val h = Harness()
        val gen = h.warmer.newGeneration()
        // 无 run（如 disabled/从未启动）→ 老版本 stop 直接 return，不封轮次
        h.warmer.stop("workspace policy changed", blockGeneration = true)
        val inactive = assertInactive(h.warmer)
        assertTrue(inactive.reason.contains("workspace policy changed"))

        h.warmer.start(testWarmRequest(), gen, h.isCurrent)
        val refused = assertInactive(h.warmer)
        assertTrue(refused.reason.contains("stopped earlier"))
        assertFalse(h.warmer.isActive)

        // 新轮次可重建
        h.warmer.start(testWarmRequest(), h.warmer.newGeneration(), h.isCurrent)
        assertTrue(h.warmer.isActive)
    }

    @Test
    fun `stop without blockGeneration and no task is a no-op`() {
        val h = Harness()
        h.warmer.newGeneration()
        h.warmer.stop("x", blockGeneration = false)
        assertFalse(h.warmer.isActive)
        h.warmer.start(testWarmRequest(), h.warmer.newGeneration(), h.isCurrent)
        assertTrue(h.warmer.isActive)
    }

    // ---------- 22. stop 原子性 / 单调封锁水位 / 迟到 observer ----------

    @Test
    fun `concurrent blocking stop and start leaves no active task and seals the generation`() {
        // barrier 并发：stop(blockGeneration=true) 与 start 同台竞争。
        // 旧实现 stop 先锁取 run=null 释放后被 start 插入建任务，第二次锁只置
        // blocked/status → 新 run 活着进 Inactive 继续重放。修复后无论谁赢，
        // 收尾都必须无活动任务，且本轮次再 start 一律被拒。
        for (round in 0 until 200) {
            val h = Harness()
            val gen = h.warmer.newGeneration()
            val barrier = CyclicBarrier(2)
            val failure = AtomicReference<Throwable?>()
            val t = Thread {
                try {
                    barrier.await(10, TimeUnit.SECONDS)
                    h.warmer.start(testWarmRequest(), gen, h.isCurrent)
                } catch (t0: Throwable) {
                    failure.set(t0)
                }
            }
            t.start()
            barrier.await(10, TimeUnit.SECONDS)
            h.warmer.stop("concurrent stop", blockGeneration = true)
            t.join(10_000)
            assertNull("round $round raced thread failed: ${failure.get()}", failure.get())

            assertFalse("round $round: a task survived the blocking stop", h.warmer.isActive)
            assertInactive(h.warmer)
            h.warmer.start(testWarmRequest(), gen, h.isCurrent)
            assertFalse("round $round: sealed generation restarted", h.warmer.isActive)

            // 新轮次不受影响
            h.warmer.start(testWarmRequest(), h.warmer.newGeneration(), h.isCurrent)
            assertTrue("round $round: fresh generation refused", h.warmer.isActive)
            h.warmer.close()
        }
    }

    @Test
    fun `blocking stop seals the latest generation even when run belongs to an older one`() {
        // 反向 TOCTOU：取到旧 run 后新 start 已替换，旧实现 end() 里 run!==r 白跑 →
        // 当前轮次根本没被封。修复后封锁以调用时刻的 generationCounter 为准。
        val h = Harness()
        val genOld = h.warmer.newGeneration()
        h.warmer.start(testWarmRequest(), genOld, h.isCurrent)
        assertTrue(h.warmer.isActive)

        val genNew = h.warmer.newGeneration() // 新轮次已发号但尚未 start
        h.warmer.stop("policy changed", blockGeneration = true)
        assertInactive(h.warmer)
        assertFalse(h.warmer.isActive)
        assertEquals(1, h.endedCount)

        // 最新轮次 genNew 也被封（封锁以 stop 调用时刻的 counter 为准）
        h.warmer.start(testWarmRequest(), genNew, h.isCurrent)
        assertFalse(h.warmer.isActive)

        h.warmer.start(testWarmRequest(), h.warmer.newGeneration(), h.isCurrent)
        assertTrue(h.warmer.isActive)
    }

    @Test
    fun `late observer of an older generation does not replace the current run`() {
        // 锁外 precheck 与 stop/start 排序穿插：gen1 的 start 跑完锁外 isCurrent 才被
        // 调度，期间 gen2 已建新任务。迟到的 gen1 start 只跳过自身，不得摘/取消新 run。
        val h = Harness()
        val gen1 = h.warmer.newGeneration()
        val gen2 = h.warmer.newGeneration()
        h.warmer.start(testWarmRequest(), gen2, h.isCurrent)
        val scheduled = h.warmer.status.value as CacheWarmStatus.Scheduled
        assertTrue(h.warmer.isActive)

        h.warmer.start(testWarmRequest(), gen1, h.isCurrent)

        assertTrue(h.warmer.isActive)
        assertEquals(scheduled, h.warmer.status.value) // 新 run 的排期不变
        assertEquals(0, h.endedCount)

        h.ticker.advanceBy(270_000L)
        assertEquals(1, h.replays.size) // 存活的是新 run，正常重放
    }

    @Test
    fun `late stale observer does not weaken a newer generation seal`() {
        // 单调 blockedThrough：gen2 已被 stop 封锁后，迟到的 gen1 stale start 写水位
        // 只能是 max()，不得把水位回退到 gen1 而让 gen2 漏封。
        val h = Harness()
        val gen1 = h.warmer.newGeneration()
        val gen2 = h.warmer.newGeneration()
        h.warmer.stop("sealed gen2", blockGeneration = true)

        // 迟到的旧 observer（gen1）带着过期快照到来
        val staleIsCurrent: () -> String? = { "context changed: stale gen1" }
        h.warmer.start(testWarmRequest(), gen1, staleIsCurrent)
        assertFalse(h.warmer.isActive)

        // gen2 仍必须被拒
        h.warmer.start(testWarmRequest(), gen2, h.isCurrent)
        assertFalse(h.warmer.isActive)

        // gen3 放行
        h.warmer.start(testWarmRequest(), h.warmer.newGeneration(), h.isCurrent)
        assertTrue(h.warmer.isActive)
    }

    @Test
    fun `stop racing the replay dispatch prevents the request`() {
        // isCurrent 通过 → 置 Refreshing → 发请求之间存在窗口；stop 若在该窗口取消
        // 本协程，ensureActive 必须拦下 replay 调用本身。
        val h = Harness()
        h.start()
        var stopped = false
        h.onLog = {
            if (!stopped) {
                stopped = true
                h.warmer.stop("stopped from callback", blockGeneration = true)
            }
        }

        h.ticker.advanceBy(270_000L)

        assertTrue(stopped)
        assertEquals(0, h.replays.size) // replay 函数体根本没被调用
        assertInactive(h.warmer)
        assertFalse(h.warmer.isActive)
        assertEquals(1, h.endedCount)
    }

    @Test
    fun `late stale precheck of an old generation cannot cancel the newer run`() {
        // 复现真实竞态：gen1 start 的锁外 precheck 阻塞期间 gen2 建好新 run；
        // gen1 恢复后 staleReason 非 null。旧实现 keepCurrentRun=false 会把 gen2
        // 的 run 摘掉并 cancel；修复后 superseded 与 staleReason 无关，只跳过自身。
        val h = Harness()
        val gen1 = h.warmer.newGeneration()
        val gen2 = h.warmer.newGeneration()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val staleIsCurrent: () -> String? = {
            entered.countDown()
            release.await()
            "context changed: stale gen1"
        }
        val t = Thread {
            try {
                h.warmer.start(testWarmRequest(), gen1, staleIsCurrent)
            } catch (t0: Throwable) {
                failure.set(t0)
            }
        }
        t.start()
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        h.warmer.start(testWarmRequest(), gen2, h.isCurrent)
        val scheduled = h.warmer.status.value as CacheWarmStatus.Scheduled
        release.countDown()
        t.join(10_000)
        assertNull("stale start thread failed: ${failure.get()}", failure.get())

        assertTrue(h.warmer.isActive)
        assertEquals(scheduled, h.warmer.status.value) // 新 run 的排期不变
        assertEquals(0, h.endedCount)

        h.ticker.advanceBy(270_000L)
        assertEquals(1, h.replays.size) // 存活的是新 run，正常重放
    }

    @Test
    fun `superseded stale start skips itself deterministically`() {
        // 同一竞态的单线程确定性形：旧轮次带着过期快照迟到，不得摘新 run、
        // 不得改写其 status（老实现里 staleReason 非 null 会摘）。
        val h = Harness()
        val gen1 = h.warmer.newGeneration()
        val gen2 = h.warmer.newGeneration()
        h.warmer.start(testWarmRequest(), gen2, h.isCurrent)
        val scheduled = h.warmer.status.value as CacheWarmStatus.Scheduled

        h.warmer.start(testWarmRequest(), gen1) { "context changed: late" }

        assertTrue(h.warmer.isActive)
        assertEquals(scheduled, h.warmer.status.value)
        assertEquals(0, h.endedCount)
    }

    @Test
    fun `already blocked generation stale late arrival cannot cancel the new run`() {
        // gen1 已被 stop 封轮次后，迟到的 gen1 stale start 复封同一轮次（单调水位
        // 幂等），仍不得摘/取消 gen2 的新 run。注意 stop 封的是调用时刻 counter，
        // 须在发号 gen2 之前调用。
        val h = Harness()
        val gen1 = h.warmer.newGeneration()
        h.warmer.stop("sealed gen1", blockGeneration = true)
        val gen2 = h.warmer.newGeneration()
        h.warmer.start(testWarmRequest(), gen2, h.isCurrent)
        val scheduled = h.warmer.status.value as CacheWarmStatus.Scheduled
        assertTrue(h.warmer.isActive)

        h.warmer.start(testWarmRequest(), gen1) { "context changed: late" }

        assertTrue(h.warmer.isActive)
        assertEquals(scheduled, h.warmer.status.value)
        assertEquals(0, h.endedCount)

        // gen1 的封锁没有被回退，gen2 的新轮次号不受影响
        h.warmer.start(testWarmRequest(), gen1, h.isCurrent)
        assertTrue(h.warmer.isActive) // 依然只有 gen2 的 run
    }

    @Test
    fun `isCurrent throwing refuses the start without leaking its message`() {
        // observer 异常只留异常类名进 status/日志，不得带回可能含 prompt 的 message。
        val h = Harness()
        val secret = "SECRET-PROMPT-PAYLOAD"
        h.warmer.start(testWarmRequest(), h.warmer.newGeneration()) {
            throw RuntimeException(secret)
        }

        val inactive = assertInactive(h.warmer)
        assertTrue(inactive.reason.contains("context check failed"))
        assertTrue(inactive.reason.contains("RuntimeException"))
        assertFalse(inactive.reason.contains(secret))
        assertFalse(h.logs.any { it.contains(secret) })
        assertFalse(h.warmer.isActive)
    }

    @Test
    fun `unrelated customBody is accepted`() {
        val h = Harness()
        val req = testWarmRequest().copy(
            params = TextGenerationParams(
                model = Model(modelId = "m"),
                customBody = listOf(CustomBody("temperature", JsonPrimitive(0.5))),
            )
        )
        h.warmer.start(req, h.warmer.newGeneration(), h.isCurrent)
        assertTrue(h.warmer.isActive)
        assertTrue(h.warmer.status.value is CacheWarmStatus.Scheduled)
    }
}
