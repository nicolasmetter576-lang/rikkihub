package me.rerere.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 真实 close-before-publish 依赖的两个纯 JVM 构件的契约测试：
 *
 * - [WorkspaceExitBarrier]：唯一 waiter 调一次 signalExit，任意多 suspend awaiter
 *   共享同一 deferred —— 没有第二个 waiter 去 waitpid（JNI waitFor/onSessionFinished
 *   是唯一的退出观察点）。超时返回 false 而不是谎称已关闭。
 * - [WorkspacePendingCloses]：同步 close 把会话从活跃表移除时，join 对象必须留在
 *   登记处直到退出被证明 —— 之后到达的 closeWorkspaceAndAwait 仍等得到它。
 *
 * 不触碰 JNI/Android（WorkspacePtySession/TerminalSession 本体在真机才可用）。
 */
class WorkspaceCloseAwaitContractTest {

    @Test
    fun `barrier awaitClosed returns immediately when already signalled`() = runBlocking {
        val barrier = WorkspaceExitBarrier()
        assertTrue(barrier.signalExit())
        assertTrue(barrier.awaitClosed(50))
    }

    @Test
    fun `barrier awaitClosed times out as false, never fakes exit`() = runBlocking {
        val barrier = WorkspaceExitBarrier()
        assertFalse(barrier.awaitClosed(80))
        assertFalse(barrier.exited)
    }

    @Test
    fun `single waiter signal wakes all awaiters, second signal is rejected`() = runBlocking {
        val barrier = WorkspaceExitBarrier()
        val awaiterCount = 3
        val completed = AtomicInteger(0)
        val firstSignal = AtomicBoolean(false)
        val secondSignal = AtomicBoolean(true)
        // 唯一 waiter：模拟 native waitFor 线程/onSessionFinished 回调，只 signal 一次
        // （断言不放 thread 里——AssertionError 会随线程死掉被静默吞掉）
        val waiter = thread(name = "dummy-unique-waiter") {
            Thread.sleep(30)
            firstSignal.set(barrier.signalExit())
            secondSignal.set(barrier.signalExit())
        }
        val awaiters = List(awaiterCount) {
            async {
                assertTrue(withTimeout(2_000) { barrier.awaitClosed(5_000) })
                completed.incrementAndGet()
            }
        }
        awaiters.forEach { it.await() }
        waiter.join(2_000)
        assertTrue("signalExit must be single-shot", firstSignal.get())
        assertFalse("second signal must be rejected", secondSignal.get())
        assertEquals(awaiterCount, completed.get())
    }

    @Test
    fun `pendingCloses keeps join object visible after live-map removal`() = runBlocking {
        // 模拟 close(sessionId) 的顺序：record → 请求关闭 → drain 等待退出 → complete。
        // 在 drain 完成前，closeWorkspaceAndAwait 快照 pending() 必须仍能看到它。
        data class FakeSession(val id: String, val exited: WorkspaceExitBarrier = WorkspaceExitBarrier())

        val pendingCloses = WorkspacePendingCloses<FakeSession>()
        val session = FakeSession("s1")

        // record 与活跃表 remove 在同一把锁语义内（测试里顺序模拟），先 record
        pendingCloses.record(session)
        assertTrue(pendingCloses.pending().contains(session))

        // 并发 awaiter：代表 publish 前的 closeWorkspaceAndAwait
        val sawJoinable = CompletableDeferred<Boolean>()
        val awaited = async {
            while (pendingCloses.pending().none { it.id == "s1" }) delay(5)
            sawJoinable.complete(true)
            assertTrue(session.exited.awaitClosed(2_000))
        }
        assertTrue(sawJoinable.await())

        // 唯一 waiter 证明退出后，drain 才 complete 登记
        assertTrue(session.exited.signalExit())
        awaited.await()
        pendingCloses.complete(session)
        assertTrue(pendingCloses.isEmpty())
    }

    @Test
    fun `pendingCloses record and complete are idempotent per handle`() {
        val pendingCloses = WorkspacePendingCloses<String>()
        pendingCloses.record("a")
        pendingCloses.record("a")
        pendingCloses.record("b")
        assertEquals(listOf("a", "b"), pendingCloses.pending())
        pendingCloses.complete("a")
        pendingCloses.complete("a")
        assertEquals(listOf("b"), pendingCloses.pending())
        pendingCloses.complete("missing")
        assertEquals(listOf("b"), pendingCloses.pending())
    }

    @Test
    fun `awaitClosed failure stays pending for publish retry reconciliation`() = runBlocking {
        // retire 失败（await 超时）语义：记录留在 pendingCloses，下一次
        // closeWorkspaceAndAwait 快照还能捞到它重试，而不是丢成不可见孤儿。
        val pendingCloses = WorkspacePendingCloses<String>()
        val stillRunning = WorkspaceExitBarrier()
        pendingCloses.record("zombie")

        val retired = runCatching {
            if (!stillRunning.awaitClosed(50)) {
                throw IllegalStateException("did not exit in time")
            }
        }
        assertTrue(retired.isFailure)
        // 失败不 complete：记录必须还在，重试路径才看得到它
        assertEquals(listOf("zombie"), pendingCloses.pending())

        // 进程后来真退出了：drain/重试能把记录清掉
        stillRunning.signalExit()
        assertTrue(stillRunning.awaitClosed(50))
        pendingCloses.complete("zombie")
        assertTrue(pendingCloses.isEmpty())
    }
}
