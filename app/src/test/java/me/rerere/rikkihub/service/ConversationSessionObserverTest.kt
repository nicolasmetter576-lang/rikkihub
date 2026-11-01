package me.rerere.rikkihub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkihub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * [observeSessionGenerationJob] 动态跟随测试。
 *
 * 生产对应 [ChatService.getGenerationJobStateFlow]：
 * `observeSessionGenerationJob(_sessionsVersion) { sessions[conversationId] }`。
 * helper 的职责是“被动观察者不为不存在的会话创建占位 session、
 * 且会话被创建/移除/重建后跟随新实例的 generationJob”。
 * 这里用可变 registry（ConcurrentHashMap + 版本 StateFlow）复刻 ChatService 的
 * sessions 表语义：bump 版本 = getOrCreateSession 安装 / removeSession 摘除之后
 * 发出的通知。
 *
 * 时序约定：收集器挂在 Unconfined scope + UNDISPATCHED，launch 返回即已开始
 * 收集；每个下游发射进 Channel(UNLIMITED)，断言用 withTimeout receive 等下游
 * 实际到达——不假设 flatMapLatest inner flow 与注册表写入同一线程步进。
 */
class ConversationSessionObserverTest {

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun newSession(scope: CoroutineScope, id: Uuid = Uuid.random()) =
        ConversationSession(id, Conversation.ofId(id), scope, {})

    /** 等待下游真实发射；UNLIMITED 缓冲保证任何顺序下都不丢值。 */
    private suspend fun Channel<Job?>.awaitEmission(timeoutMs: Long = 5_000): Job? =
        withTimeout(timeoutMs) { receive() }

    @Test
    fun `subscribing before session exists follows the session once it is created`() =
        runBlocking {
            val scope = newScope()
            val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
            val registryVersion = MutableStateFlow(0L)
            val id = Uuid.random()
            var lookups = 0
            val emissions = Channel<Job?>(Channel.UNLIMITED)
            // UNDISPATCHED + Unconfined：此处返回时收集器已订阅并缓冲首个发射。
            val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                observeSessionGenerationJob(registryVersion) {
                    lookups++
                    sessions[id]
                }.collect { emissions.send(it) }
            }
            try {
                // 会话还没注册：被动观察者拿到 null，且不会凭空建 session。
                assertNull(emissions.awaitEmission())
                assertTrue(sessions.isEmpty())
                assertTrue(lookups >= 1)

                val session = newSession(scope, id)
                sessions[id] = session
                registryVersion.value = 1

                // 新 inner flow 的首个值是 session.generationJob 当前值 null。
                assertNull(emissions.awaitEmission())

                val job = scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
                session.setJob(job)
                assertSame(job, emissions.awaitEmission())

                // 生成结束后 slot 清空，观察者看到 null。
                job.cancel()
                job.join()
                assertNull(emissions.awaitEmission())
            } finally {
                // 断言失败也必须先停收集器：runBlocking 会等子协程，
                // 活 collect 会把测试卡死而不是失败退出。
                collector.cancel()
                collector.join()
                emissions.close()
                sessions.values.forEach { it.cleanup() }
                scope.cancel()
            }
        }

    @Test
    fun `observer follows session removal and recreation instead of a stale instance`() =
        runBlocking {
            val scope = newScope()
            val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
            val registryVersion = MutableStateFlow(0L)
            val id = Uuid.random()
            val emissions = Channel<Job?>(Channel.UNLIMITED)
            val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                observeSessionGenerationJob(registryVersion) { sessions[id] }
                    .collect { emissions.send(it) }
            }
            try {
                assertNull(emissions.awaitEmission()) // initial missing registry
                val oldSession = newSession(scope, id)
                sessions[id] = oldSession
                registryVersion.value = 1

                // 订阅时注册表已有会话：inner 即该 session 的 generationJob（初始 null）。
                assertNull(emissions.awaitEmission())

                val oldJob = scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
                oldSession.setJob(oldJob)
                assertSame(oldJob, emissions.awaitEmission())

                // removeSession 同款：先摘除再 bump 版本（isInUse 保护由 ChatService 负责，
                // helper 只跟随注册表内容）。
                sessions.remove(id)
                registryVersion.value = 2
                assertNull(emissions.awaitEmission())

                // 旧 session 之后的 job 变化不再被观察：inner flow 已切走。
                // 先只设置旧槽位不发版本事件，然后用一次 bump 作为"已排空"锚点：
                // 下游收到的下一个值必须是新一轮 lookup 的 flowOf(null)；若旧 inner
                // 泄漏了 orphan，锚点值就会先变成 orphan 而被下面的断言抓住。
                val orphan = scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
                oldSession.setJob(orphan)
                registryVersion.value = 3
                assertNull(emissions.awaitEmission())
                orphan.cancel()
                oldJob.cancel()

                // 重建：同 id 新 session 的 job 进入视野。
                val newSession = newSession(scope, id)
                sessions[id] = newSession
                registryVersion.value = 4
                // 新 inner 首个值仍是当前槽位（null）。
                assertNull(emissions.awaitEmission())

                val newJob = scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
                newSession.setJob(newJob)
                assertSame(newJob, emissions.awaitEmission())
            } finally {
                collector.cancel()
                collector.join()
                emissions.close()
                sessions.values.forEach { it.cleanup() }
                scope.cancel()
            }
        }

    @Test
    fun `job changes within one registry version emit without a version bump`() = runBlocking {
        val scope = newScope()
        val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
        val registryVersion = MutableStateFlow(0L)
        val id = Uuid.random()
        val emissions = Channel<Job?>(Channel.UNLIMITED)
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            observeSessionGenerationJob(registryVersion) { sessions[id] }
                .collect { emissions.send(it) }
        }
        try {
            assertNull(emissions.awaitEmission()) // consume initial missing registry BEFORE insertion
            val session = newSession(scope, id)
            sessions[id] = session
            registryVersion.value = 1

            // 注册表没变，inner flow 直接透传 generationJob 的每个值。
            assertNull(emissions.awaitEmission())
            assertEquals(1L, registryVersion.value)

            val gate = CompletableDeferred<Unit>()
            val job = scope.launch(start = CoroutineStart.LAZY) { gate.await() }
            session.setJob(job)
            assertSame(job, emissions.awaitEmission())

            // 自然完成（非取消）也回到 null。
            gate.complete(Unit)
            job.join()
            assertNull(emissions.awaitEmission())
        } finally {
            collector.cancel()
            collector.join()
            emissions.close()
            sessions.values.forEach { it.cleanup() }
            scope.cancel()
        }
    }

    @Test
    fun `observer only tracks the requested id and never materializes missing sessions`() =
        runBlocking {
            val scope = newScope()
            val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
            val registryVersion = MutableStateFlow(0L)
            val watchedId = Uuid.random()
            val otherId = Uuid.random()
            var lookups = 0
            val emissions = Channel<Job?>(Channel.UNLIMITED)
            val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                observeSessionGenerationJob(registryVersion) {
                    lookups++
                    sessions[watchedId]
                }.collect { emissions.send(it) }
            }
            try {
                assertNull(emissions.awaitEmission())
                assertNull(sessions[watchedId])

                // 别的会话注册并开跑：对 watchedId 只是注册表抖动，仍然 null。
                val other = newSession(scope, otherId)
                sessions[otherId] = other
                val otherJob = scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
                other.setJob(otherJob)
                registryVersion.value = 1
                // 版本 bump 重启 inner，watchedId 无会话 → flowOf(null) 再发一次 null。
                assertNull(emissions.awaitEmission())
                assertNull(sessions[watchedId])
                assertTrue(lookups >= 2)

                otherJob.cancel()
            } finally {
                collector.cancel()
                collector.join()
                emissions.close()
                sessions.values.forEach { it.cleanup() }
                scope.cancel()
            }
        }
}
