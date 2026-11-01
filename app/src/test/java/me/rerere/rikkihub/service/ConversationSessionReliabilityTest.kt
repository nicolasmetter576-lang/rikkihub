package me.rerere.rikkihub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * ConversationSession 可靠性原语测试：
 *
 * - 终态落库：普通失败与取消都把半截回复持久化，收尾时 reasoning 封口
 *   ([UIMessagePart.Reasoning.finishedAt])，生成槽位只等落库完成后才释放。
 * - 元数据与全量快照：并发 toggle 按 persistenceMutex 顺序落库且不吞流式
 *   消息；移动会话清 folder/换 assistant 但保留整棵消息树；旧全量快照不能撤回
 *   pin/assistant/title/customSystemPrompt/workspaceCwd；全量保存与 metadata
 *   写双向互斥（同一把 [ConversationSession.withPersistence] 锁）。
 * - 懒加载：并发初始化只加载一次；迟到的 DB 加载不能盖住在途编辑，也不能
 *   盖住已接管生成槽位的会话；取消/失败的 load 可重试；已初始化 session 不重复
 *   加载。
 *
 * 全量持久化与元数据更新共用 persistenceMutex；普通流式内存更新不拿此锁。
 * replaceConversation 保留已发布的元数据，initialize 不覆盖已编辑的内存状态。
 * ChatService 生产对应点：initializeConversation→session.initialize、
 * saveConversation/persistProtocolRollbackState→withPersistence+replaceConversation、
 * toggleConversationPin/moveConversation→updateMetadata、生成 onCompletion→finishGeneration。
 */
class ConversationSessionReliabilityTest {

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun partialReply() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Reasoning("thinking so far", finishedAt = null),
            UIMessagePart.Text("partial reply"),
            UIMessagePart.Tool(
                toolCallId = "edit-1",
                toolName = "edit_file",
                input = "{}",
                output = listOf(UIMessagePart.Text("file updated")),
            ),
        ),
    )

    // ---------------------------------------------------------------------
    // 终态落库：半截内容持久化 + reasoning 封口 + 槽位释放时序
    // ---------------------------------------------------------------------

    @Test
    fun `failed stream persists partial content for a recreated session`() = runBlocking {
        val scope = newScope()
        val id = Uuid.random()
        var persisted = Conversation.ofId(id)
        val session = ConversationSession(id, persisted, scope, {})
        try {
            session.initialize { persisted }
            val failure = IllegalStateException("connection lost")
            // ChatService 生成循环同款：流中每个 chunk 先进会话态，onCompletion 统一收尾。
            val result = runCatching {
                flow {
                    emit(partialReply())
                    throw failure
                }.onCompletion {
                    session.finishGeneration { persisted = it }
                }.collect { message ->
                    session.replaceConversation(
                        session.state.value.updateCurrentMessages(listOf(message))
                    )
                }
            }
            assertSame(failure, result.exceptionOrNull())

            // 重开（新 session + 持久层快照）必须能还原半截回复，且 reasoning 已封口。
            val reopened = ConversationSession(id, Conversation.ofId(id), scope, {})
            try {
                reopened.initialize { persisted }
                val parts = reopened.state.value.currentMessages.single().parts
                assertEquals("thinking so far", (parts[0] as UIMessagePart.Reasoning).reasoning)
                assertNotNull((parts[0] as UIMessagePart.Reasoning).finishedAt)
                assertEquals(UIMessagePart.Text("partial reply"), parts[1])
                val tool = parts[2] as UIMessagePart.Tool
                assertEquals("edit_file", tool.toolName)
                assertEquals(listOf(UIMessagePart.Text("file updated")), tool.output)
            } finally {
                reopened.cleanup()
            }
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `cancelled generation waits for partial content to be persisted before releasing the slot`() =
        runBlocking {
            val scope = newScope()
            val id = Uuid.random()
            var persisted = Conversation.ofId(id)
            val session = ConversationSession(id, persisted, scope, {})
            try {
                session.initialize { persisted }
                val saving = CompletableDeferred<Unit>()
                val allowSave = CompletableDeferred<Unit>()
                val generation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    flow {
                        emit(partialReply())
                        awaitCancellation()
                    }.onCompletion {
                        session.finishGeneration {
                            saving.complete(Unit)
                            allowSave.await()
                            persisted = it
                        }
                    }.collect { message ->
                        session.replaceConversation(
                            session.state.value.updateCurrentMessages(listOf(message))
                        )
                    }
                }
                session.setJob(generation)
                generation.cancel()

                // 取消已生效，但 NonCancellable 落库还没完：协程未结束、槽位不释放。
                saving.await()
                assertFalse(generation.isCompleted)
                // isActive is already false after cancellation; the non-null job SLOT retains the session.
                assertFalse(session.isGenerating)
                assertSame(generation, session.getJob())
                assertTrue(session.isInUse)

                allowSave.complete(Unit)
                generation.join()

                assertNull(session.getJob())
                assertFalse(session.isGenerating)
                assertEquals(session.state.value, persisted)
                val parts = persisted.currentMessages.single().parts
                assertNotNull((parts[0] as UIMessagePart.Reasoning).finishedAt)
                assertEquals("partial reply", (parts[1] as UIMessagePart.Text).text)
            } finally {
                session.cleanup()
                scope.cancel()
            }
        }

    @Test
    fun `finishGeneration seals reasoning across branches and bumps updateAt`() = runBlocking {
        val scope = newScope()
        val id = Uuid.random()
        val sealedAt = kotlin.time.Instant.fromEpochMilliseconds(1)
        val open = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("active branch reasoning", finishedAt = null)),
        )
        val alreadySealed = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("closed branch reasoning", finishedAt = sealedAt)
            ),
        )
        val initial = Conversation.ofId(id)
            .updateCurrentMessages(listOf(UIMessage.user("hi"), open))
            .copy(updateAt = Instant.EPOCH)
        // 第二个分支塞同一节点：收尾必须覆盖全分支，不是只盖 selectIndex。
        val withBranch = initial.copy(
            messageNodes = initial.messageNodes.mapIndexed { i, node ->
                if (i == initial.messageNodes.lastIndex) {
                    node.copy(messages = node.messages + alreadySealed)
                } else node
            }
        )
        val session = ConversationSession(id, withBranch, scope, {})
        try {
            var saved: Conversation? = null
            val finished = session.finishGeneration { saved = it }
            assertSame(finished, saved)

            val node = finished.messageNodes.last()
            val openReasoning = node.messages[0].parts[0] as UIMessagePart.Reasoning
            assertNotNull(openReasoning.finishedAt)
            // 已封口的 reasoning 不被改写
            val sealedReasoning = node.messages[1].parts[0] as UIMessagePart.Reasoning
            assertEquals(sealedAt, sealedReasoning.finishedAt)
            assertTrue(finished.updateAt.isAfter(Instant.EPOCH))
            assertSame(finished, session.state.value)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `metadata persist still completes when the caller is cancelled mid save`() = runBlocking {
        val scope = newScope()
        val id = Uuid.random()
        var persistedPinned: Boolean? = null
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            session.initialize { Conversation.ofId(id) }
            val persistStarted = CompletableDeferred<Unit>()
            val allowPersist = CompletableDeferred<Unit>()
            val writer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.updateMetadata(
                    update = { it.copy(isPinned = true) },
                    persist = {
                        persistStarted.complete(Unit)
                        // updateMetadata 的 persist 段在 NonCancellable 中：调用协程被取消
                        // 也不能让列更新写到一半（对应 updatePinStatus 等单列 DAO 写）。
                        allowPersist.await()
                        persistedPinned = it.isPinned
                    },
                )
            }
            persistStarted.await()
            assertTrue(session.state.value.isPinned)
            writer.cancel()
            assertFalse(writer.isCompleted)
            allowPersist.complete(Unit)
            writer.join()
            assertTrue(writer.isCancelled)
            assertEquals(true, persistedPinned)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    // ---------------------------------------------------------------------
    // 元数据列写与全量快照的互斥与排序
    // ---------------------------------------------------------------------

    @Test
    fun `concurrent pin toggles persist in order without replacing streamed messages`() =
        runBlocking {
            val scope = newScope()
            val id = Uuid.random()
            var persisted = Conversation.ofId(id)
            val session = ConversationSession(id, persisted, scope, {})
            try {
                session.initialize { persisted }
                val allowFirstSave = CompletableDeferred<Unit>()
                val writtenPins = mutableListOf<Boolean>()
                // toggleConversationPin 同款路径：updateMetadata(update, persist)。
                // fork 把 updateState 也放进锁内，所以第二个 toggle 读到的一定是
                // 第一个 toggle 之后的状态 —— 两次写必然 true,false 不丢失更新。
                val first = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    session.updateMetadata(
                        update = { it.copy(isPinned = !it.isPinned) },
                        persist = {
                            allowFirstSave.await()
                            persisted = persisted.copy(isPinned = it.isPinned)
                            writtenPins.add(it.isPinned)
                        },
                    )
                }
                val second = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    session.updateMetadata(
                        update = { it.copy(isPinned = !it.isPinned) },
                        persist = {
                            persisted = persisted.copy(isPinned = it.isPinned)
                            writtenPins.add(it.isPinned)
                        },
                    )
                }
                // 第一次 toggle 已改内存态、持锁停在 persist；第二次还没进锁。
                assertTrue(session.state.value.isPinned)
                assertFalse(second.isCompleted)

                // metadata 写未落库期间，流式写仍可推进消息树（不占持久化锁）。
                val streaming = session.state.value.updateCurrentMessages(listOf(partialReply()))
                session.replaceConversation(streaming)

                allowFirstSave.complete(Unit)
                first.join()
                second.join()

                assertEquals(listOf(true, false), writtenPins)
                assertFalse(persisted.isPinned)
                assertFalse(session.state.value.isPinned)
                assertEquals(streaming.messageNodes, session.state.value.messageNodes)
            } finally {
                session.cleanup()
                scope.cancel()
            }
        }

    @Test
    fun `moving a conversation clears folder and rebinds assistant while keeping full history`() =
        runBlocking {
            val scope = newScope()
            val id = Uuid.random()
            val oldAssistant = Uuid.random()
            val targetAssistant = Uuid.random()
            var persisted = Conversation.ofId(id, assistantId = oldAssistant)
                .updateCurrentMessages(
                    listOf(UIMessage.user("q1"), UIMessage.assistant("a1"), UIMessage.user("q2"))
                )
                .copy(folderId = Uuid.random(), title = "folder chat")
            val session = ConversationSession(id, Conversation.ofId(id), scope, {})
            try {
                session.initialize { persisted }
                val historyNodes = session.state.value.messageNodes

                // moveConversation 同款：换 assistant + 清 folder，一次 metadata 写完成。
                session.updateMetadata(
                    update = { it.copy(assistantId = targetAssistant, folderId = null) },
                    persist = { updated ->
                        persisted = persisted.copy(
                            assistantId = updated.assistantId,
                            folderId = updated.folderId,
                        )
                    },
                )

                assertEquals(targetAssistant, session.state.value.assistantId)
                assertNull(session.state.value.folderId)
                // 元数据写不动消息树
                assertEquals(historyNodes, session.state.value.messageNodes)
                assertEquals(3, session.state.value.currentMessages.size)
                assertEquals("a1", session.state.value.currentMessages[1].toText())
                assertEquals(historyNodes, persisted.messageNodes)
            } finally {
                session.cleanup()
                scope.cancel()
            }
        }

    @Test
    fun `stale full snapshot cannot undo pin assistant title system prompt and cwd`() =
        runBlocking {
            val scope = newScope()
            val id = Uuid.random()
            val oldAssistant = Uuid.random()
            val targetAssistant = Uuid.random()
            val targetFolder = Uuid.random()
            val modeInjectionIds = setOf(Uuid.random())
            val lorebookIds = setOf(Uuid.random())
            val session = ConversationSession(id, Conversation.ofId(id, assistantId = oldAssistant), scope, {})
            try {
                session.initialize { Conversation.ofId(id, assistantId = oldAssistant) }

                // 各列更新的生产者（Web patch / App pin / 移动会话）先后写元数据。
                session.updateMetadata(
                    update = { it.copy(isPinned = true) },
                    persist = {},
                )
                session.updateMetadata(
                    update = {
                        it.copy(
                            assistantId = targetAssistant,
                            folderId = targetFolder,
                            title = "renamed",
                            customSystemPrompt = "sys",
                            workspaceCwd = "/workspace/app",
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                        )
                    },
                    persist = {},
                )

                // 迟到全量快照：旧 assistant、未 pin、空 title，但带更新的消息树。
                // 生产对应 saveConversation 里 replaceConversation 的合并语义。
                val streamed = partialReply()
                var replacedWith: Conversation? = null
                var oldSeen: Conversation? = null
                val stale = Conversation.ofId(id, assistantId = oldAssistant)
                    .updateCurrentMessages(listOf(UIMessage.user("hi"), streamed))
                    .copy(title = "", isPinned = false, workspaceCwd = null, folderId = null)
                val updated = session.replaceConversation(stale) { new, old ->
                    replacedWith = new
                    oldSeen = old
                }

                // 受保护列全部以内存态为准，消息树采纳快照。
                assertEquals(targetAssistant, updated.assistantId)
                assertEquals(targetFolder, updated.folderId)
                assertTrue(updated.isPinned)
                assertEquals("renamed", updated.title)
                assertEquals("sys", updated.customSystemPrompt)
                assertEquals("/workspace/app", updated.workspaceCwd)
                assertEquals(modeInjectionIds, updated.modeInjectionIds)
                assertEquals(lorebookIds, updated.lorebookIds)
                assertEquals(stale.messageNodes, updated.messageNodes)
                assertSame(updated, replacedWith)
                assertSame(session.state.value, updated)
                assertEquals(targetAssistant, oldSeen!!.assistantId)
            } finally {
                session.cleanup()
                scope.cancel()
            }
        }

    @Test
    fun `full snapshot is adopted verbatim before first initialization`() = runBlocking {
        val scope = newScope()
        val id = Uuid.random()
        val snapshotAssistant = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            // 会话从未 initialize：没有更可信的内存元数据，快照原样接管（否则分支）。
            val snapshot = Conversation.ofId(id, assistantId = snapshotAssistant)
                .copy(isPinned = true, title = "from store")
            val updated = session.replaceConversation(snapshot)
            assertEquals(snapshot, updated)
            assertEquals(snapshotAssistant, updated.assistantId)
            assertTrue(updated.isPinned)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `metadata edit waits for a suspended full save to finish`() = runBlocking {
        val scope = newScope()
        val id = Uuid.random()
        var persisted = Conversation.ofId(id)
        val session = ConversationSession(id, persisted, scope, {})
        try {
            session.initialize { persisted }
            val allowFullSave = CompletableDeferred<Unit>()
            val metaCalls = mutableListOf<Boolean>()
            val events = mutableListOf<String>()

            // saveConversation 同款：withPersistence { replaceConversation + persist }。
            val fullSave = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.withPersistence {
                    val snapshot = session.state.value.updateCurrentMessages(listOf(partialReply()))
                    val updated = session.replaceConversation(snapshot)
                    allowFullSave.await()
                    persisted = updated
                    events += "full-save"
                }
            }
            // toggleConversationPin 同款：metadata 写与全量保存同一把锁。
            val metaWrite = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.updateMetadata(
                    update = { it.copy(isPinned = !it.isPinned) },
                    persist = { updated ->
                        metaCalls += updated.isPinned
                        persisted = persisted.copy(isPinned = updated.isPinned)
                        events += "meta-save"
                    },
                )
            }

            assertFalse(metaWrite.isCompleted)
            allowFullSave.complete(Unit)
            fullSave.join()
            metaWrite.join()

            // metadata 写排在全量保存之后：状态已含消息树，落库顺序不乱。
            assertEquals(listOf("full-save", "meta-save"), events)
            assertEquals(listOf(true), metaCalls)
            assertTrue(persisted.isPinned)
            assertEquals(1, persisted.currentMessages.size)
            assertTrue(session.state.value.isPinned)
            assertEquals(persisted.messageNodes, session.state.value.messageNodes)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `full save waits for a suspended metadata persist and then sees pinned state`() =
        runBlocking {
            val scope = newScope()
            val id = Uuid.random()
            var persisted = Conversation.ofId(id)
            val session = ConversationSession(id, persisted, scope, {})
            try {
                session.initialize { persisted }
                val allowMeta = CompletableDeferred<Unit>()
                val events = mutableListOf<String>()
                var savedPinned: Boolean? = null

                val metaWrite = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    session.updateMetadata(
                        update = { it.copy(isPinned = true) },
                        persist = {
                            allowMeta.await()
                            events += "meta-save"
                        },
                    )
                }
                // finishGeneration 同款（也是 withPersistence 内的全量写）：在 metadata
                // persist 挂起期间不许插进来写一个未 pin 的全量快照。
                val finish = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    session.finishGeneration { finished ->
                        savedPinned = finished.isPinned
                        events += "full-save"
                        persisted = finished
                    }
                }

                assertFalse(finish.isCompleted)
                allowMeta.complete(Unit)
                metaWrite.join()
                finish.join()

                assertEquals(listOf("meta-save", "full-save"), events)
                assertEquals(true, savedPinned)
                assertTrue(persisted.isPinned)
                assertTrue(session.state.value.isPinned)
            } finally {
                session.cleanup()
                scope.cancel()
            }
        }

    // ---------------------------------------------------------------------
    // 懒加载与并发初始化
    // ---------------------------------------------------------------------

    @Test
    fun `concurrent initialization loads once without blocking another conversation`() =
        runBlocking {
            val scope = newScope()
            val id = Uuid.random()
            val initial = Conversation.ofId(id)
            val session = ConversationSession(id, initial, scope, {})
            val otherId = Uuid.random()
            val other = ConversationSession(otherId, Conversation.ofId(otherId), scope, {})
            try {
                val loaded = CompletableDeferred<Conversation>()
                var loads = 0
                val first = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    session.initialize { loads++; loaded.await() }
                }
                val second = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    session.initialize { loads++; initial }
                }
                // 另一个会话的初始化不共享锁，不被挂起的加载堵住
                other.initialize { Conversation.ofId(otherId) }
                assertFalse(first.isCompleted)
                assertFalse(second.isCompleted)

                val persisted = initial.updateCurrentMessages(listOf(UIMessage.user("history")))
                loaded.complete(persisted)
                first.join()
                second.join()

                assertEquals(1, loads)
                assertEquals(persisted, session.state.value)
            } finally {
                session.cleanup()
                other.cleanup()
                scope.cancel()
            }
        }

    @Test
    fun `late database load cannot overwrite a newer conversation update`() = runBlocking {
        val scope = newScope()
        val id = Uuid.random()
        val initial = Conversation.ofId(id)
        val session = ConversationSession(id, initial, scope, {})
        try {
            val loaded = CompletableDeferred<Conversation>()
            val loading = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.initialize { loaded.await() }
            }
            // 加载挂起期间内存态被编辑：迟到的 DB 快照必须被丢弃。
            val updated = initial.updateCurrentMessages(listOf(partialReply()))
            session.replaceConversation(updated)
            loaded.complete(initial)
            loading.join()

            assertEquals(updated, session.state.value)
            // 且会话已视为初始化，后续 initialize 一律 no-op。
            session.initialize { error("Initialized session must not reload") }
            assertEquals(updated, session.state.value)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `late load cannot overwrite a session whose generation slot is already taken`() =
        runBlocking {
            val scope = newScope()
            val id = Uuid.random()
            val initial = Conversation.ofId(id)
            val session = ConversationSession(id, initial, scope, {})
            try {
                val loaded = CompletableDeferred<Conversation>()
                val loading = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    session.initialize { loaded.await() }
                }
                // 加载挂起期间生成任务接管槽位（sendMessage/regenerate 先到）：
                // 即使 state 引用未变，load 结果也不许落回，避免清掉在途生成视图。
                val generation = scope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
                session.setJob(generation)
                loaded.complete(initial.updateCurrentMessages(listOf(UIMessage.user("db"))))
                loading.join()

                assertEquals(initial, session.state.value)
                generation.cancel()
                generation.join()
            } finally {
                session.cleanup()
                scope.cancel()
            }
        }

    @Test
    fun `cancelled initial load can be retried`() = runBlocking {
        val scope = newScope()
        val id = Uuid.random()
        val initial = Conversation.ofId(id)
        val session = ConversationSession(id, initial, scope, {})
        try {
            val loading = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.initialize { awaitCancellation() }
            }
            loading.cancel()
            loading.join()
            val persisted = initial.updateCurrentMessages(listOf(UIMessage.user("history")))
            session.initialize { persisted }
            assertEquals(persisted, session.state.value)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `failed initial load can be retried`() = runBlocking {
        val scope = newScope()
        val id = Uuid.random()
        val initial = Conversation.ofId(id)
        val session = ConversationSession(id, initial, scope, {})
        try {
            // load 抛异常：锁必须释放且 initialized 不能置位，下一次调用重试。
            val first = runCatching {
                session.initialize { error("disk read failed") }
            }
            assertTrue(first.isFailure)
            val persisted = initial.updateCurrentMessages(listOf(UIMessage.user("history")))
            session.initialize { persisted }
            assertEquals(persisted, session.state.value)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `initialized session with no job is not reloaded`() = runBlocking {
        val scope = newScope()
        val id = Uuid.random()
        var persisted = Conversation.ofId(id)
        val session = ConversationSession(id, persisted, scope, {})
        try {
            session.initialize { persisted }
            val streaming = session.state.value.updateCurrentMessages(listOf(partialReply()))
            session.replaceConversation(streaming)

            // 页面返回 / Web 重连 / 元数据路径的前置 initialize 都只是 no-op：
            // 即便槽位为空也不重新读库覆盖内存态。
            assertNull(session.getJob())
            var loads = 0
            repeat(3) {
                session.initialize { loads++; error("Must not reload an initialized session") }
            }
            assertEquals(0, loads)
            assertEquals(streaming.messageNodes, session.state.value.messageNodes)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `metadata edits survive reopening and final generation save`() = runBlocking {
        val scope = newScope()
        val id = Uuid.random()
        val oldAssistant = Uuid.random()
        val targetAssistant = Uuid.random()
        var persisted = Conversation.ofId(id, assistantId = oldAssistant)
            .copy(folderId = Uuid.random())
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            session.initialize { persisted }
            val streaming = session.state.value.updateCurrentMessages(listOf(partialReply()))
            session.replaceConversation(streaming)

            session.updateMetadata(
                update = { it.copy(isPinned = !it.isPinned) },
                persist = { persisted = persisted.copy(isPinned = it.isPinned) },
            )
            session.updateMetadata(
                update = { it.copy(assistantId = targetAssistant, folderId = null) },
                persist = {
                    persisted = persisted.copy(assistantId = it.assistantId, folderId = null)
                },
            )

            // 重新打开活动会话：initialize 不再读库，元数据与流式消息都在。
            session.initialize { error("Returning to an active conversation must not reload") }
            assertTrue(session.state.value.isPinned)
            assertEquals(targetAssistant, session.state.value.assistantId)
            assertNull(session.state.value.folderId)
            assertEquals(streaming.messageNodes, session.state.value.messageNodes)
            assertTrue(persisted.messageNodes.isEmpty())

            // 生成收尾的全量快照带上最新元数据与消息树一起落库。
            session.finishGeneration { persisted = it }
            assertTrue(persisted.isPinned)
            assertEquals(targetAssistant, persisted.assistantId)
            assertNull(persisted.folderId)
            assertEquals(
                streaming.currentMessages.single().id,
                persisted.currentMessages.single().id,
            )
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }
}
