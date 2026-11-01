package me.rerere.rikkihub.web.routes

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * `/api/events` 的 `workspace_update` diff 语义：EventsRoutes 里 SSE/auth/compression
 * 外壳之外的纯失效机制，直接对生产 helper 做真失效断言（不是只测常量）。
 *
 * 流测试约定：MutableStateFlow 有 conflation，快照间必须等 collector 消化完再发
 * 下一帧（wait-for-received 屏障），且所有等待都套 withTimeout + finally cancel。
 */
class WorkspaceInvalidationTest {

    private fun entity(
        id: String,
        name: String = "ws-$id",
        updatedAt: Long = 1000L,
    ) = WorkspaceEntity(
        id = id,
        name = name,
        root = "/root/$id",
        createdAt = 1L,
        updatedAt = updatedAt,
    )

    private fun byId(vararg entities: WorkspaceEntity) =
        entities.toList().associateBy { it.id }

    /* ---------- workspaceChangedIds（纯 diff） ---------- */

    @Test
    fun `first frame is not a change`() {
        assertNull(workspaceChangedIds(null, listOf(entity("a"), entity("b"))))
        assertNull(workspaceChangedIds(null, emptyList()))
    }

    @Test
    fun `identical snapshot produces no change`() {
        val before = byId(entity("a"), entity("b"))
        // Room 会重发相等快照；data class 全字段相等 -> 无事件
        assertNull(
            workspaceChangedIds(before, listOf(entity("a"), entity("b").copy())),
        )
    }

    @Test
    fun `modified row yields only that id`() {
        val before = byId(entity("a"), entity("b"), entity("c"))
        val next = listOf(entity("a"), entity("b", name = "renamed"), entity("c"))
        assertEquals(listOf("b"), workspaceChangedIds(before, next))
    }

    @Test
    fun `created and deleted rows yield their ids`() {
        val before = byId(entity("a"), entity("b"))
        assertEquals(
            listOf("b", "c"),
            workspaceChangedIds(before, listOf(entity("a"), entity("c"))).orEmpty().sorted(),
        )
    }

    @Test
    fun `any column change counts including enablement`() {
        val before = byId(entity("a"))
        val toggled = entity("a").copy(toolEnablement = "{\"bash\":false}")
        assertEquals(listOf("a"), workspaceChangedIds(before, listOf(toggled)))
    }

    /* ---------- workspaceUpdateEvents（流 + 每订阅独立缓存） ---------- */

    /**
     * 等待 [source] 的订阅数达到 [n] 并让 collector 消化完首帧：
     * subscriptionCount 在 emit 首帧前递增（单线程 runBlocking 内首帧处理也在
     * 该协程同一 dispatch turn 里完成），随后 yield 兜底。
     */
    private suspend fun awaitBaseline(source: MutableStateFlow<*>, n: Int) {
        source.subscriptionCount.first { it >= n }
        yield()
    }

    @Test
    fun `flow emits only real diffs in order`() = runBlocking {
        val source = MutableStateFlow(listOf(entity("a"), entity("b")))
        val received = ConcurrentLinkedQueue<List<String>>()
        val job = launch {
            workspaceUpdateEvents(source).take(2).collect { received.add(it.workspaceIds) }
        }
        try {
            withTimeout(3_000) {
                awaitBaseline(source, 1) // 首帧 = 基线，不算变更

                source.value = listOf(entity("a"), entity("b").copy(name = "x")) // 改一行
                while (received.size < 1) yield()

                // 无变化快照不产生事件（StateFlow 对 equals 相同值本就抑制，
                // 纯 diff 的"无变化"由上面的单测覆盖）；这里直接发删除帧。
                source.value = listOf(entity("a")) // 删除 b
                while (received.size < 2) yield()
            }
            withTimeout(3_000) { job.join() }

            assertEquals(listOf(listOf("b"), listOf("b")), received.toList())
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `concurrent subscriptions keep independent previous caches`() = runBlocking {
        // 模拟 JWT 开/关两处 /api/events 端点各自安装路由时的两个并发 collector：
        // 每个订阅的首帧是它自己的基线，晚加入者不会吃到早加入者订阅前的历史 diff。
        val source = MutableStateFlow(listOf(entity("a")))

        val early = ConcurrentLinkedQueue<List<String>>()
        val earlyJob: Job = launch {
            workspaceUpdateEvents(source).take(2).collect { early.add(it.workspaceIds) }
        }
        var lateJob: Job? = null
        val late = ConcurrentLinkedQueue<List<String>>()
        try {
            withTimeout(3_000) {
                awaitBaseline(source, 1) // early 基线 = v1

                source.value = listOf(entity("a"), entity("b")) // 新增 b
                while (early.size < 1) yield() // early 收到 [b] 后再继续

                val lateReceived = late
                lateJob = launch {
                    workspaceUpdateEvents(source).take(1).collect {
                        lateReceived.add(it.workspaceIds)
                    }
                }
                awaitBaseline(source, 2) // late 基线 = v2(a,b)，不回放 [b]

                source.value = listOf(entity("a", name = "renamed"), entity("b")) // 改 a
                while (early.size < 2 || late.size < 1) yield()
            }
            withTimeout(3_000) {
                earlyJob.join()
                lateJob?.join()
            }

            // early: [b]（新增）+ [a]（修改）；late 只见它订阅后的 [a]
            assertEquals(listOf(listOf("b"), listOf("a")), early.toList())
            assertEquals(listOf(listOf("a")), late.toList())
        } finally {
            earlyJob.cancelAndJoin()
            lateJob?.cancelAndJoin()
        }
    }
}
