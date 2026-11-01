package me.rerere.rikkihub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.GenerationAttemptProtocolException
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test
    fun `stop does not resume queued approval when predecessor cancels immediately`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            var saved = false
            val first = scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
            session.setJob(first)
            val second = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(first) { saved = true }
            }
            session.setJob(second, cancelPrevious = false)
            session.cancelJobs().forEach { it.join() }
            assertFalse(saved)
            assertTrue(first.isCancelled)
            assertTrue(second.isCancelled)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `queued approvals preserve a decision while its save is suspended`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val completions = mutableListOf<Throwable?>()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {},
            { _, cause -> completions.add(cause) })
        try {
            val save = CompletableDeferred<Unit>()
            val decisions = mutableListOf<String>()
            val first = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(null) {
                    save.await()
                    decisions.add("A")
                }
            }
            session.setJob(first, cancelPrevious = false)
            val second = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(first) { decisions.add("B") }
            }
            session.setJob(second, cancelPrevious = false)
            assertTrue(first.isActive)
            assertTrue(decisions.isEmpty())
            save.complete(Unit)
            second.join()
            assertEquals(listOf("A", "B"), decisions)
            assertEquals(listOf<Throwable?>(null), completions)
            assertNull(session.getJob())
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `stopping queued approvals waits for all predecessors to stop`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            val cleanup = CompletableDeferred<Unit>()
            val first = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(null) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { cleanup.await() }
                    }
                }
            }
            session.setJob(first, cancelPrevious = false)
            val second = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(first) { error("Must not execute") }
            }
            session.setJob(second, cancelPrevious = false)
            val third = scope.launch(start = CoroutineStart.LAZY) {
                afterPreviousGeneration(second) { error("Must not execute") }
            }
            session.setJob(third, cancelPrevious = false)
            val stopped = session.cancelJobs()
            assertTrue(first.isCancelled)
            assertTrue(second.isCancelled)
            assertFalse(third.isCompleted)
            cleanup.complete(Unit)
            stopped.forEach { it.join() }
            assertTrue(third.isCompleted)
            assertTrue(first.isCompleted)
            assertTrue(second.isCompleted)
            assertNull(session.getJob())
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `replaced job finishing late cannot clear successor or advance queue`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val completions = mutableListOf<Throwable?>()
        val id = Uuid.random()
        val session = ConversationSession(
            id,
            Conversation.ofId(id),
            scope,
            {},
            { _, cause -> completions.add(cause) })
        try {
            val releaseOld = CompletableDeferred<Unit>()
            val releaseNew = CompletableDeferred<Unit>()
            val old = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { releaseOld.await() }
                }
            }
            session.setJob(old)
            val successor = scope.launch(start = CoroutineStart.LAZY) { releaseNew.await() }
            session.setJob(successor)

            releaseOld.complete(Unit)
            old.join()
            assertSame(successor, session.getJob())
            assertTrue(completions.isEmpty())

            releaseNew.complete(Unit)
            successor.join()
            assertNull(session.getJob())
            assertEquals(listOf<Throwable?>(null), completions)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `instant completion does not leave a stale generation job`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            session.setJob(scope.launch(start = CoroutineStart.LAZY) {})
            assertNull(session.getJob())
            assertFalse(session.isGenerating)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `pending messages retain session even when queue is paused and page has no references`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            assertFalse(session.isInUse)
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("next")))
            session.messageQueue.pause()
            assertTrue(session.isInUse)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    // ---- 协议失败 attempt 回滚与生成排序不变量 ----
    // 生产链路: 失败任务在其协程末尾的 runCatching.onFailure 里跑
    // withContext(NonCancellable) { rollback + persist }; 后继生成任务
    // (审批 setJob(cancelPrevious=false)+afterPreviousGeneration, 或
    // sendMessage/regenerate 的 cancelPrevious=true+previousJob.join())
    // 必须等前任连同清理完整结束后才读会话态。以下两条用例在
    // ConversationSession + afterPreviousGeneration 原语层验证该契约。

    private data class ProtocolFailureFixture(
        val base: UIMessage,
        val halfBuilt: UIMessage,
        val failure: GenerationAttemptProtocolException,
    )

    private fun protocolFailureFixture(conversationId: Uuid): ProtocolFailureFixture {
        val base = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        val failure = GenerationAttemptProtocolException(
            protocolError = ToolStreamProtocolException("duplicate_tool_name", "duplicate name"),
            conversationId = conversationId,
            assistantMessageId = base.id,
            attemptIndex = 0,
            baseMessage = base,
        )
        // 失败 attempt 发布过的半截尾消息: Auto 未执行工具 part (input 可携带
        // file:// 引用 —— 回滚不得走附件 GC, 这里只验证内存态恢复)。
        val halfBuilt = base.copy(
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "half",
                    toolName = "echo_tool",
                    input = "{\"path\":\"file:///chat/upload/x.png\"}",
                    approvalState = ToolApprovalState.Auto,
                )
            )
        )
        return ProtocolFailureFixture(base, halfBuilt, failure)
    }

    /** 模拟 ChatService 生成任务体: 发布半截态 -> 抛协议异常 -> onFailure 内 NonCancellable 延迟回滚。 */
    private fun CoroutineScope.protocolFailingGenerationJob(
        session: ConversationSession,
        fixture: ProtocolFailureFixture,
        rollbackGate: CompletableDeferred<Unit>,
        events: MutableList<String>,
        slotDuringRollback: MutableList<Job?>,
        published: List<UIMessage>,
    ): Job = launch(start = CoroutineStart.LAZY) {
        try {
            session.state.value = session.state.value.updateCurrentMessages(published)
            events += "publish"
            throw fixture.failure
        } catch (failure: Throwable) {
            if (failure is GenerationAttemptProtocolException) {
                runCatching {
                    withContext(NonCancellable) {
                        rollbackGate.await()
                        slotDuringRollback += session.getJob()
                        if (session.rollbackProtocolFailedAttempt(failure)) {
                            events += "rollback"
                            events += "persist"
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `successor reads state only after predecessor non-cancellable protocol rollback`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val conversationId = Uuid.random()
            val session = ConversationSession(
                conversationId,
                Conversation.ofId(conversationId),
                scope,
                {},
            )
            try {
                val fixture = protocolFailureFixture(conversationId)
                val rollbackGate = CompletableDeferred<Unit>()
                val events = mutableListOf<String>()
                val slotDuringRollback = mutableListOf<Job?>()

                // 前任 A: 发布含 Auto 未执行工具的半截尾消息后协议失败;
                // onFailure 清理被闸门挂起, 模拟清理尚未完成时后继已安装。
                // 显式绑到 Unconfined scope: runBlocking 的 scope 是串行事件循环,
                // LAZY+start() 只会排队不会内联执行, publish 事件根本到不了。
                val predecessor = scope.protocolFailingGenerationJob(
                    session = session,
                    fixture = fixture,
                    rollbackGate = rollbackGate,
                    events = events,
                    slotDuringRollback = slotDuringRollback,
                    published = listOf(UIMessage.user("hi"), fixture.halfBuilt),
                )
                session.setJob(predecessor)

                // 后继 B: 审批路径同款 setJob(cancelPrevious=false) +
                // afterPreviousGeneration(A) 后读状态。
                var observed: Conversation? = null
                val successor = scope.launch(start = CoroutineStart.LAZY) {
                    afterPreviousGeneration(predecessor) {
                        events += "read"
                        observed = session.state.value
                    }
                }
                session.setJob(successor, cancelPrevious = false)

                // A 已发布并进入 NonCancellable 清理但被闸门挡住, B 还不能读状态
                assertEquals(listOf("publish"), events)
                assertNull(observed)

                rollbackGate.complete(Unit)
                successor.join()

                // 清理在回滚时后继已占槽位: 无 ownerJob 门控仍然生效
                assertEquals(listOf(successor), slotDuringRollback)
                assertEquals(listOf("publish", "rollback", "persist", "read"), events)
                val last = observed!!.currentMessages.last()
                assertEquals(fixture.base, last)
                assertTrue(
                    last.parts.none { it is UIMessagePart.Tool && it.canResumeExecution }
                )
                assertNull(session.getJob())
            } finally {
                session.cleanup()
                scope.cancel()
            }
        }

    @Test
    fun `cancelled predecessor non-cancellable rollback precedes successor join read`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val conversationId = Uuid.random()
            val session = ConversationSession(
                conversationId,
                Conversation.ofId(conversationId),
                scope,
                {},
            )
            try {
                val fixture = protocolFailureFixture(conversationId)
                val rollbackGate = CompletableDeferred<Unit>()
                val events = mutableListOf<String>()
                val slotDuringRollback = mutableListOf<Job?>()

                // 同上: 必须绑到 Unconfined scope 才能内联执行到 rollbackGate。
                val predecessor = scope.protocolFailingGenerationJob(
                    session = session,
                    fixture = fixture,
                    rollbackGate = rollbackGate,
                    events = events,
                    slotDuringRollback = slotDuringRollback,
                    published = listOf(UIMessage.user("hi"), fixture.halfBuilt),
                )
                session.setJob(predecessor)

                // sendMessage/regenerate 同款: setJob(cancelPrevious=true) 取消前任,
                // job 体第一步 previousJob.join() —— 取消不得跳过前任已识别的
                // NonCancellable 清理, join 必须等其结束。
                var observed: Conversation? = null
                val successor = scope.launch(start = CoroutineStart.LAZY) {
                    previousJoin(predecessor)
                    events += "read"
                    observed = session.state.value
                }
                session.setJob(successor)

                assertTrue(predecessor.isCancelled)
                assertEquals(listOf("publish"), events)
                assertNull(observed)

                rollbackGate.complete(Unit)
                successor.join()

                assertEquals(listOf(successor), slotDuringRollback)
                assertEquals(listOf("publish", "rollback", "persist", "read"), events)
                val last = observed!!.currentMessages.last()
                assertEquals(fixture.base, last)
                assertTrue(
                    last.parts.none { it is UIMessagePart.Tool && it.canResumeExecution }
                )
            } finally {
                session.cleanup()
                scope.cancel()
            }
        }

    /** sendQueuedMessage/regenerateAtMessage 里的 `previousJob?.join()` 第一步。 */
    private suspend fun previousJoin(previous: Job?) {
        previous?.join()
    }

    // ---- 排序链：pending 修复任务（cancelPrevious=false + 前任 join） ----

    @Test
    fun `pending repair job joins predecessor and sees its published state`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            val publishGate = CompletableDeferred<Unit>()
            val streamedMessage = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("chunk")))
            val events = mutableListOf<String>()

            // 前任生成：模拟 loop 本地持有 messages 并在结束前发布尾消息
            val generation = scope.launch(start = CoroutineStart.LAZY) {
                publishGate.await()
                session.state.update { it.updateCurrentMessages(listOf(UIMessage.user("hi"), streamedMessage)) }
                events += "gen-publish"
            }
            session.setJob(generation)

            // 修复任务复刻 scheduleWorkspacePendingRepair：不占前任、先入槽再 join
            var examined: Int = -1
            var sawPublished = false
            val repair = scope.launch(start = CoroutineStart.LAZY) {
                previousJoin(generation)
                // 纯内存 part 级补丁（真实 reconcile 的 state.update CAS 步骤）
                session.state.update { current ->
                    sawPublished = current.currentMessages.lastOrNull() === streamedMessage
                    current.patchWorkspacePendingToolCalls { part, _ ->
                        WorkspacePendingToolResolution(WorkspacePendingToolStatus.READY)
                    }.also { examined = it.examinedParts }.conversation
                }
                events += "repair"
            }
            session.setJob(repair, cancelPrevious = false)

            // 前任未结束时修复任务已经占槽但不许先跑
            assertTrue(events.isEmpty())
            publishGate.complete(Unit)
            repair.join()
            assertEquals(listOf("gen-publish", "repair"), events)
            assertTrue(sawPublished)
            assertNull(session.getJob())
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `repair patch on inactive branch coexists with streamed current branch writes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val pending = UIMessagePart.Tool(
            toolCallId = "c1", toolName = "ws_tool", input = "{}",
            approvalState = ToolApprovalState.Pending,
        )
        val main = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("main")))
        val branch = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending))
        val initial = Conversation.ofId(id).updateCurrentMessages(listOf(UIMessage.user("hi"), main))
        // 把 branch 塞进最后一个节点作为非当前分支
        val withBranch = initial.copy(
            messageNodes = initial.messageNodes.mapIndexed { i, node ->
                if (i == initial.messageNodes.lastIndex) {
                    node.copy(messages = node.messages + branch)
                } else node
            }
        )
        val session = ConversationSession(id, withBranch, scope, {})
        try {
            val generation = scope.launch(start = CoroutineStart.LAZY) {
                // loop 流式写当前分支（CAS 追加）
                session.state.update { cur ->
                    cur.updateCurrentMessages(
                        cur.currentMessages.dropLast(1) +
                            main.copy(parts = listOf(UIMessagePart.Text("main v2")))
                    )
                }
            }
            session.setJob(generation)
            val repair = scope.launch(start = CoroutineStart.LAZY) {
                previousJoin(generation)
                session.state.update { cur ->
                    cur.patchWorkspacePendingToolCalls { part, _ ->
                        if (part.toolName == "ws_tool") {
                            WorkspacePendingToolResolution(
                                WorkspacePendingToolStatus.UNAVAILABLE,
                                "TOOL_DISABLED",
                            )
                        } else {
                            WorkspacePendingToolResolution(WorkspacePendingToolStatus.READY)
                        }
                    }.conversation
                }
            }
            session.setJob(repair, cancelPrevious = false)
            repair.join()

            val node = session.state.value.messageNodes.last()
            // 当前分支的流式写没被修复覆盖
            assertEquals("main v2", (node.currentMessage.parts[0] as UIMessagePart.Text).text)
            // 非当前分支的 pending 被写实，不再是 pending
            val patched = node.messages[1].parts[0] as UIMessagePart.Tool
            assertTrue(patched.isExecuted)
            assertFalse(patched.isPending)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `policy cancellation completes jobs with policy cause and releases slot`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val completions = mutableListOf<Throwable?>()
        val id = Uuid.random()
        val session = ConversationSession(
            id, Conversation.ofId(id), scope, {},
            { _, cause -> completions.add(cause) },
        )
        try {
            session.messageQueue.resume()
            val generation = scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
            session.setJob(generation)
            // 重绑工作区同款：政策取消在途 run —— 原因需能在完成回调里识别为
            // 政策取消（不 pause 队列），槽位随后可接修复/新任务。
            val jobs = session.cancelJobs(
                me.rerere.rikkihub.data.workspace.WorkspacePolicyCancellationException("ws-old")
            )
            jobs.forEach { it.join() }

            assertTrue(generation.isCancelled)
            assertEquals(1, completions.size)
            assertFalse(shouldPauseQueueOnGenerationFinished(completions[0]))
            assertFalse(session.messageQueue.state.value.paused)
            assertNull(session.getJob())

            // 后继清理任务可立即接槽并正常完成
            var repaired = false
            val repair = scope.launch(start = CoroutineStart.LAZY) { repaired = true }
            session.setJob(repair, cancelPrevious = false)
            repair.join()
            assertTrue(repaired)
            assertFalse(session.messageQueue.state.value.paused)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }
}
