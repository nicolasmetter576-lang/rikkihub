package me.rerere.rikkihub.data.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.job
import kotlin.coroutines.coroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WorkspaceRuntimeGateTest {

    private fun newGate() = WorkspaceRuntimeGate(joinTimeoutMillis = 2_000L)

    private suspend inline fun <T> gateTool(
        gate: WorkspaceRuntimeGate,
        ws: String,
        stableId: String,
        noinline body: suspend (WorkspaceEpochs) -> T,
    ): T = gate.withOperation(
        workspaceId = ws,
        kind = WorkspaceOperationKind.TOOL,
        stableIds = setOf(stableId),
    ) { epochs -> body(epochs) }

    // --------------------------------------------------------------- 基本隔离

    @Test
    fun `barrier - publish cancels victims while other workspace is unaffected`() = runBlocking {
        val gate = newGate()
        val wsA = "ws-a"
        val wsB = "ws-b"
        val aStarted = CompletableDeferred<Unit>()
        val aReleased = CompletableDeferred<Unit>()
        val bDone = CompletableDeferred<String>()

        val aJob = async {
            runCatching {
                gateTool(gate, wsA, "workspace_shell") {
                    aStarted.complete(Unit)
                    // NonCancellable：真正占用到释放，使 publish 的 join 实际等待
                    withContext(NonCancellable) { aReleased.await() }
                    "a-finished"
                }
            }
        }
        val bJob = async(Dispatchers.Default) {
            bDone.complete(
                gateTool(gate, wsB, "workspace_shell") { "b-finished" }
            )
        }

        aStarted.await()
        assertEquals("b-finished", withTimeout(3_000) { bDone.await() })

        val publishedDeferred = async {
            gate.publishAndCancel(wsA, WorkspaceChangeSpec.toolPolicy(setOf("workspace_shell"))) { epochs ->
                assertEquals(1L, epochs.policyEpoch)
                "published"
            }
        }
        delay(30)
        assertFalse(publishedDeferred.isCompleted) // join 真正在等 victim
        aReleased.complete(Unit)
        assertEquals("published", publishedDeferred.await())

        val aResult = aJob.await()
        assertTrue(aResult.exceptionOrNull() is WorkspacePolicyCancellationException)
        // 另一工作区不受 wsA 变更影响：epoch 各自独立
        assertEquals(1L, gate.currentEpoch(wsA))
        assertEquals(0L, gate.currentEpoch(wsB))
        assertEquals(0, gate.snapshot(wsB).activeOperations)
        Unit
    }

    // --------------------------------------------------------------- 热关闭只取消子 Job

    @Test
    fun `policy cancel surfaces WorkspacePolicyCancellationException without cancelling caller`() = runBlocking {
        val gate = newGate()
        val ws = "ws-1"
        val started = CompletableDeferred<Unit>()
        val callerCancelled = AtomicBoolean(false)

        val op = async {
            try {
                gateTool(gate, ws, "workspace_shell") {
                    started.complete(Unit)
                    delay(60_000)
                }
                fail("expected policy cancellation")
            } catch (e: WorkspacePolicyCancellationException) {
                assertEquals(ws, e.workspaceId)
            }
        }
        started.await()
        launch {
            gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("workspace_shell"))) {}
        }
        withTimeout(3_000) { op.await() }
        assertFalse(callerCancelled.get())
        assertTrue(coroutineContext[Job]!!.isActive)
    }

    // --------------------------------------------------------------- disable/admit 竞态

    @Test
    fun `disable vs admit race is deterministic - admitted becomes victim, later admission waits`() = runBlocking {
        repeat(20) {
            val gate = newGate()
            val ws = "ws-race"
            val victimStarted = CompletableDeferred<Unit>()
            val victimReleased = CompletableDeferred<Unit>()

            val victim = async {
                runCatching {
                    gateTool(gate, ws, "tool-x") {
                        victimStarted.complete(Unit)
                        withContext(NonCancellable) { victimReleased.await() }
                    }
                }
            }
            victimStarted.await()

            val publish = async(Dispatchers.Default) {
                gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("tool-x"))) {}
            }
            // 阻塞中的新准入等待解封而非插进 victim 集
            val late = async(Dispatchers.Default) {
                gate.withOperation(
                    workspaceId = ws,
                    kind = WorkspaceOperationKind.TOOL,
                    stableIds = setOf("tool-x"),
                    validate = { true },
                ) { "late-ran" }
            }
            delay(30)
            victimReleased.complete(Unit)
            publish.await()
            assertEquals("late-ran", withTimeout(3_000) { late.await() })
            assertTrue(victim.await().exceptionOrNull() is WorkspacePolicyCancellationException)
        }
    }

    @Test
    fun `waitIfSealed false denies while sealed`() = runBlocking {
        val gate = newGate()
        val ws = "ws-seal"
        val victimStarted = CompletableDeferred<Unit>()
        val victimReleased = CompletableDeferred<Unit>()
        val victim = async {
            runCatching {
                gateTool(gate, ws, "tool-y") {
                    victimStarted.complete(Unit)
                    withContext(NonCancellable) { victimReleased.await() }
                }
            }
        }
        victimStarted.await()
        val publish = async {
            gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("tool-y"))) {}
        }
        delay(50)
        // 封禁期间快速失败
        try {
            gate.withOperation(ws, WorkspaceOperationKind.TOOL, waitIfSealed = false) { "never" }
            fail("expected SEALED denial")
        } catch (e: WorkspaceUnavailableException) {
            assertEquals(WorkspaceUnavailableReason.SEALED, e.reason)
        }
        victimReleased.complete(Unit)
        publish.await()
        assertTrue(victim.await().exceptionOrNull() is WorkspacePolicyCancellationException)
    }

    // --------------------------------------------------------------- 创建登记

    @Test
    fun `createHandle registers and survives unrelated tool policy change`() = runBlocking {
        val gate = newGate()
        val ws = "ws-h"
        val closed = AtomicBoolean(false)
        val handle = gate.createHandle(
            workspaceId = ws,
            handleClass = WorkspaceHandleClass.USER_TERMINAL,
            close = { closed.set(true) },
            spawn = { "terminal-session" },
        )
        assertEquals("terminal-session", handle.value)
        assertEquals(1, gate.snapshot(ws).registeredHandles)
        assertTrue(gate.isHandleFresh(handle.token))

        // 单工具开关不波及 USER_TERMINAL（空 stableIds）
        gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("workspace_shell"))) {}
        assertTrue(gate.isHandleFresh(handle.token))
        assertFalse(closed.get())

        gate.releaseHandle(handle.token)
        assertTrue(closed.get())
        assertFalse(gate.isHandleFresh(handle.token))
        assertEquals(0, gate.snapshot(ws).registeredHandles)
    }

    @Test
    fun `spawn cancelled when creation op is victimized by structural publish`() = runBlocking {
        val gate = newGate()
        val ws = "ws-stale"
        val spawnEntered = CompletableDeferred<Unit>()
        val spawnCancelled = CompletableDeferred<Unit>()

        val create = async {
            runCatching {
                gate.createHandle(
                    workspaceId = ws,
                    handleClass = WorkspaceHandleClass.MODEL_BASH,
                    spawn = {
                        spawnEntered.complete(Unit)
                        try {
                            CompletableDeferred<Unit>().await()
                        } finally {
                            spawnCancelled.complete(Unit)
                        }
                        @Suppress("UNREACHABLE_CODE")
                        "bash"
                    },
                )
            }
        }
        spawnEntered.await()
        gate.publishAndCancel(ws, WorkspaceChangeSpec.structural()) {}
        // 创建 op 是 victim：spawn 挂起点被取消，调用方收 policy CE（已开始的创建被打断）
        assertTrue(withTimeout(3_000) { spawnCancelled.await(); true })
        val result = create.await()
        assertTrue(result.exceptionOrNull() is WorkspacePolicyCancellationException)
    }

    @Test
    fun `handle created across view bump without victimization is closed and reported stale`() = runBlocking {
        // 类过滤结构变更：viewEpoch bump 但波及集不含 MODEL_BASH 创建 op
        // → spawn 正常返回后登记复核发现 view 过期 → NonCancellable 关闭并抛 Stale
        val gate = newGate()
        val ws = "ws-stale2"
        val spawnEntered = CompletableDeferred<Unit>()
        val releaseSpawn = CompletableDeferred<Unit>()
        val closed = AtomicBoolean(false)
        val awaited = AtomicBoolean(false)

        val create = async {
            runCatching {
                gate.createHandle(
                    workspaceId = ws,
                    handleClass = WorkspaceHandleClass.MODEL_BASH,
                    close = { closed.set(true) },
                    awaitClosed = { awaited.set(true) },
                    spawn = {
                        spawnEntered.complete(Unit)
                        releaseSpawn.await()
                        "bash"
                    },
                )
            }
        }
        spawnEntered.await()
        // 先注册一个 USER_TERMINAL handle 作为真实 victim，让 publish 有活可干
        val terminal = gate.createHandle(
            workspaceId = ws,
            handleClass = WorkspaceHandleClass.USER_TERMINAL,
            spawn = { "term" },
        )
        gate.publishAndCancel(
            ws,
            WorkspaceChangeSpec(
                affectedStableIds = null,
                handleClasses = setOf(WorkspaceHandleClass.USER_TERMINAL),
                bumpPolicyEpoch = true,
                bumpViewEpoch = true,
            ),
        ) {}
        assertFalse(gate.isHandleFresh(terminal.token))
        releaseSpawn.complete(Unit)
        val result = create.await()
        assertTrue(result.exceptionOrNull() is WorkspaceStaleHandleException)
        assertTrue(closed.get())
        assertTrue(awaited.get())
        assertEquals(0, gate.snapshot(ws).registeredHandles)
    }

    // --------------------------------------------------------------- 锁外 join

    @Test
    fun `join happens outside admission mutex - new admission sees SEALED not blocked during join`() = runBlocking {
        // victim 在 NonCancellable 中占用，publish 停在 join 阶段；此时新准入能立刻拿到锁
        // 并看到 SEALED（证明 join 不持 admissionMutex；持锁则 probe 挂起至 publish 结束）
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 2_000L)
        val ws = "ws-join"
        val victimStarted = CompletableDeferred<Unit>()
        val victimReleased = CompletableDeferred<Unit>()
        val probeReason = CompletableDeferred<WorkspaceUnavailableReason?>()

        val victim = async {
            runCatching {
                gateTool(gate, ws, "tool-z") {
                    victimStarted.complete(Unit)
                    withContext(NonCancellable) { victimReleased.await() }
                }
            }
        }
        victimStarted.await()
        val publish = async {
            gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("tool-z"))) {}
        }
        delay(50) // 等 publish 进入 join
        val probe = async(Dispatchers.Default) {
            try {
                gate.withOperation(ws, WorkspaceOperationKind.TOOL, waitIfSealed = false) { "never" }
                probeReason.complete(null)
            } catch (e: WorkspaceUnavailableException) {
                probeReason.complete(e.reason)
            }
        }
        // 锁外 join：probe 在 victim 未释放时已返回 SEALED
        assertEquals(
            WorkspaceUnavailableReason.SEALED,
            withTimeout(1_000) { probeReason.await() },
        )
        assertFalse(publish.isCompleted)
        victimReleased.complete(Unit)
        probe.await()
        publish.await()
        assertTrue(victim.await().exceptionOrNull() is WorkspacePolicyCancellationException)
    }

    // --------------------------------------------------------------- publish 失败解封

    @Test
    fun `publish failure unseals admission and keeps bumped epoch`() = runBlocking {
        val gate = newGate()
        val ws = "ws-fail"
        val victimStarted = CompletableDeferred<Unit>()
        val victimReleased = CompletableDeferred<Unit>()
        val victim = async {
            runCatching {
                gateTool(gate, ws, "tool-f") {
                    victimStarted.complete(Unit)
                    withContext(NonCancellable) { victimReleased.await() }
                }
            }
        }
        victimStarted.await()

        val publishDeferred = async {
            runCatching {
                gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("tool-f"))) {
                    error("publish blew up")
                }
            }
        }
        delay(30)
        victimReleased.complete(Unit)
        val publishResult = publishDeferred.await()
        assertTrue(publishResult.exceptionOrNull()?.message?.contains("publish blew up") == true)
        assertTrue(victim.await().exceptionOrNull() is WorkspacePolicyCancellationException)

        // 解封：新准入能跑（validate 读到的是发布后的最新状态），epoch 不回滚
        assertEquals(1L, gate.currentEpoch(ws))
        val after = gate.withOperation(ws, WorkspaceOperationKind.TOOL, validate = { true }) { "admitted" }
        assertEquals("admitted", after)
        // 失败也发 committed=false 事件
        assertEquals(false, gate.eventsFlow(ws).value?.committed)
    }

    // --------------------------------------------------------------- 稳定身份选 victim

    @Test
    fun `stable identity selects victims - only affected stable ids are cancelled`() = runBlocking {
        val gate = newGate()
        val ws = "ws-victims"
        val shellStarted = CompletableDeferred<Unit>()
        val editorDone = CompletableDeferred<String>()
        val shellReleased = CompletableDeferred<Unit>()

        val shell = async {
            runCatching {
                gateTool(gate, ws, "workspace_shell") {
                    shellStarted.complete(Unit)
                    withContext(NonCancellable) { shellReleased.await() }
                }
            }
        }
        val editor = async(Dispatchers.Default) {
            editorDone.complete(gateTool(gate, ws, "workspace_edit_file") { "edited" })
        }
        val generic = async(Dispatchers.Default) {
            // 空 stableIds：单工具变更不波及
            gate.withOperation(ws, WorkspaceOperationKind.TOOL) { "generic" }
        }

        shellStarted.await()
        assertEquals("edited", withTimeout(3_000) { editorDone.await() })
        assertEquals("generic", withTimeout(3_000) { generic.await() })

        val publishDone = CompletableDeferred<Unit>()
        val publish = async {
            gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("workspace_shell"))) {}
            publishDone.complete(Unit)
        }
        delay(30)
        assertFalse(publishDone.isCompleted) // 等 victim join
        shellReleased.complete(Unit)
        publish.await()
        assertTrue(shell.await().exceptionOrNull() is WorkspacePolicyCancellationException)
        assertEquals(1L, gate.currentToolEpoch(ws, "workspace_shell"))
        assertEquals(0L, gate.currentToolEpoch(ws, "workspace_edit_file"))
    }

    @Test
    fun `reenable revokes old tool instance via per-stable-id epoch`() = runBlocking {
        val gate = newGate()
        val ws = "ws-revoke"
        // 捕获初始 tool epoch
        val captured = gate.currentToolEpoch(ws, "workspace_shell")
        assertEquals(0L, captured)

        // disable → enable：两次 bump
        gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("workspace_shell"))) {}
        gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("workspace_shell"))) {}
        assertEquals(2L, gate.currentToolEpoch(ws, "workspace_shell"))

        // 旧实例拿 captured=0 复查 → STABLE_ID_REVOKED
        try {
            gate.withOperation(
                workspaceId = ws,
                kind = WorkspaceOperationKind.TOOL,
                stableIds = setOf("workspace_shell"),
                expectedToolEpochs = mapOf("workspace_shell" to captured),
            ) { "should-not-run" }
            fail("expected STABLE_ID_REVOKED")
        } catch (e: WorkspaceUnavailableException) {
            assertEquals(WorkspaceUnavailableReason.STABLE_ID_REVOKED, e.reason)
        }
        // 新捕获可执行
        val fresh = gate.withOperation(
            workspaceId = ws,
            kind = WorkspaceOperationKind.TOOL,
            stableIds = setOf("workspace_shell"),
            expectedToolEpochs = mapOf("workspace_shell" to gate.currentToolEpoch(ws, "workspace_shell")),
        ) { "fresh-ran" }
        assertEquals("fresh-ran", fresh)
    }

    // --------------------------------------------------------------- child 免审批不免 gate

    @Test
    fun `child operations are gated too - nested admission allowed, still victimized`() = runBlocking {
        val gate = newGate()
        val ws = "ws-child"
        val childStarted = CompletableDeferred<Unit>()
        val childReleased = CompletableDeferred<Unit>()
        val childCancelled = CompletableDeferred<Unit>()

        // child op 嵌套在父 tool op 体内（subagent 场景：嵌套准入允许，OpElement 不拒绝准入）
        val parent = async {
            runCatching {
                gate.withOperation(ws, WorkspaceOperationKind.TOOL, stableIds = setOf("subagent")) {
                    gate.withOperation(ws, WorkspaceOperationKind.CHILD, stableIds = setOf("workspace_shell")) {
                        childStarted.complete(Unit)
                        try {
                            childReleased.await()
                        } catch (e: CancellationException) {
                            childCancelled.complete(Unit)
                            throw e
                        }
                        "child-done"
                    }
                }
            }
        }
        childStarted.await()
        gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("workspace_shell"))) {}
        // child 的 job 是独立 victim 被取消（父 op stableIds 不含 workspace_shell 不直接受害）；
        // 且 child job 是父 op job 的 supervisor 子节点：child 取消不打断父的其他工作，
        // 但调用方拿到的是 child 的 policy CE（哪怕 body 吞掉取消返回值也不交付）
        val parentResult = parent.await()
        assertTrue(parentResult.exceptionOrNull() is WorkspacePolicyCancellationException)
        assertTrue(withTimeout(3_000) { childCancelled.await(); true })
        childReleased.complete(Unit)
        Unit
    }

    // --------------------------------------------------------------- 重入

    @Test
    fun `publish inside registered op is rejected not deadlocked`() = runBlocking {
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 500L)
        val ws = "ws-reentrant"
        val outcome = gate.withOperation(ws, WorkspaceOperationKind.TOOL) {
            runCatching {
                gate.publishAndCancel(ws, WorkspaceChangeSpec.structural()) {}
            }
        }
        assertTrue(outcome.exceptionOrNull() is WorkspaceGateReentrancyException)
    }

    // --------------------------------------------------------------- metadata-only prepared

    @Test
    fun `publishPrepared null spec publishes in place - no epoch bump, no event, no seal`() = runBlocking {
        val gate = newGate()
        val ws = "ws-meta"
        var published = false
        val result = gate.publishPrepared(
            workspaceId = ws,
            prepare = { null },
            publish = { epochs ->
                published = true
                assertEquals(WorkspaceEpochs(0, 0), epochs)
                "meta-ok"
            },
        )
        assertEquals("meta-ok", result)
        assertTrue(published)
        assertEquals(0L, gate.currentEpoch(ws))
        assertEquals(0L, gate.currentViewEpoch(ws))
        assertNull(gate.eventsFlow(ws).value)
        assertFalse(gate.snapshot(ws).sealed)
    }

    @Test
    fun `publishPrepared real spec runs full publish path`() = runBlocking {
        val gate = newGate()
        val ws = "ws-prepared"
        var preparedUnderLatest = false
        val seen = gate.publishPrepared(
            workspaceId = ws,
            prepare = {
                // 锁内读最新：能读到 db 现状（这里是首次）
                preparedUnderLatest = true
                WorkspaceChangeSpec.toolPolicy(setOf("tool-p"))
            },
            publish = { it.policyEpoch },
        )
        assertTrue(preparedUnderLatest)
        assertEquals(1L, seen)
        assertEquals(1L, gate.currentToolEpoch(ws, "tool-p"))
        assertEquals(true, gate.eventsFlow(ws).value?.committed)
    }

    // --------------------------------------------------------------- withSnapshot / events

    @Test
    fun `withSnapshot read observes consistent epoch`() = runBlocking {
        val gate = newGate()
        val ws = "ws-snap"
        gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("t"))) {}
        val (readEpoch, dbValue) = gate.withSnapshot(ws) { epochs ->
            epochs to "db-latest" // 模拟单次最新 DB 读
        }
        assertEquals(1L, readEpoch.policyEpoch)
        assertEquals("db-latest", dbValue)
    }

    @Test
    fun `changes sharedflow emits committed and failed events`() = runBlocking {
        val gate = newGate()
        val ws = "ws-events"
        // take(2) 先订阅再发布，不依赖 tryEmit 投递时机
        val collector = async(Dispatchers.Default) {
            gate.changes.take(2).toList()
        }
        delay(50)
        gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("a"))) {}
        runCatching {
            gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("b"))) { error("boom") }
        }
        val events = withTimeout(3_000) { collector.await() }
        assertEquals(2, events.size)
        assertTrue(events[0].committed)
        assertFalse(events[1].committed)
        assertEquals(ws, events[0].workspaceId)
    }

    @Test
    fun `onBeforeChange fires synchronously before publish callback`() = runBlocking {
        val gate = newGate()
        val ws = "ws-before"
        val order = mutableListOf<String>()
        val unregister = gate.addOnBeforeChangeListener { e ->
            assertEquals(ws, e.workspaceId)
            order += "before:${e.epochs.policyEpoch}"
        }
        gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("a"))) { epochs ->
            order += "publish:${epochs.policyEpoch}"
        }
        unregister()
        assertEquals(listOf("before:1", "publish:1"), order)
    }

    // --------------------------------------------------------------- closeWorkspace

    @Test
    fun `closeWorkspace permanently denies admission and cancels all`() = runBlocking {
        val gate = newGate()
        val ws = "ws-close"
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val victim = async {
            runCatching {
                gateTool(gate, ws, "anything") {
                    started.complete(Unit)
                    withContext(NonCancellable) { released.await() }
                }
            }
        }
        started.await()
        val close = async { gate.closeWorkspace(ws) }
        delay(30)
        released.complete(Unit)
        close.await()
        assertTrue(victim.await().exceptionOrNull() is WorkspacePolicyCancellationException)

        try {
            gate.withOperation(ws, WorkspaceOperationKind.TOOL) { "never" }
            fail("expected WORKSPACE_CLOSED")
        } catch (e: WorkspaceUnavailableException) {
            assertEquals(WorkspaceUnavailableReason.WORKSPACE_CLOSED, e.reason)
        }
        // 幂等
        gate.closeWorkspace(ws)
        assertTrue(gate.snapshot(ws).closed)
    }

    // --------------------------------------------------------------- quarantine

    @Test
    fun `cleanup failure quarantines workspace - stays sealed and does not publish`() = runBlocking {
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 300L)
        val ws = "ws-quarantine"
        val handle = gate.createHandle(
            workspaceId = ws,
            handleClass = WorkspaceHandleClass.MODEL_BASH,
            close = { error("close refused") },
            spawn = { "bash" },
        )
        assertTrue(gate.isHandleFresh(handle.token))

        val outcome = runCatching {
            gate.publishAndCancel(ws, WorkspaceChangeSpec.structural()) { "should-not-publish" }
        }
        assertTrue(outcome.exceptionOrNull() is WorkspaceGateCleanupException)
        // fail-loud：仍 sealed（quarantine）、handle 未出册、事件 committed=false
        assertTrue(gate.snapshot(ws).sealed)
        assertEquals(1, gate.snapshot(ws).registeredHandles)
        assertEquals(false, gate.eventsFlow(ws).value?.committed)
        // 逃生通道：closeWorkspace 设 closed（closed 优先于 sealed），但清理失败同样抛出
        // —— 不静默宣称撤回成功，Repo 删除据此阻止
        val closeOutcome = runCatching { gate.closeWorkspace(ws) }
        assertTrue(closeOutcome.exceptionOrNull() is WorkspaceGateCleanupException)
        assertTrue(gate.snapshot(ws).closed)
    }

    // --------------------------------------------------------------- validate/attest

    @Test
    fun `validate false denies with VALIDATION_FAILED`() = runBlocking {
        val gate = newGate()
        try {
            gate.withOperation("ws-v", WorkspaceOperationKind.TOOL, validate = { false }) { "x" }
            fail("expected denial")
        } catch (e: WorkspaceUnavailableException) {
            assertEquals(WorkspaceUnavailableReason.VALIDATION_FAILED, e.reason)
        }
    }

    // --------------------------------------------------------------- handle 收尾竞态

    @Test
    fun `concurrent release and publish victim cleanup close handle exactly once`() = runBlocking {
        // releaseHandle 与 publish victim 清理共用 per-record 串行门：同一资源只 close 一次
        val gate = newGate()
        val ws = "ws-dbl"
        val closes = AtomicInteger(0)
        val awaits = AtomicInteger(0)
        val closeGate = CompletableDeferred<Unit>()
        val releaseDone = CompletableDeferred<Unit>()

        val handle = gate.createHandle(
            workspaceId = ws,
            handleClass = WorkspaceHandleClass.MODEL_BASH,
            close = {
                closes.incrementAndGet()
                withTimeout(3_000) { closeGate.await() } // 占住 cleanupMutex，撑开竞态窗口
            },
            awaitClosed = { awaits.incrementAndGet() },
            spawn = { "bash" },
        )

        // A: release 先拿到 cleanupMutex 并阻塞在 close 内
        val release = async(Dispatchers.Default) {
            gate.releaseHandle(handle.token)
            releaseDone.complete(Unit)
        }
        delay(50)
        assertEquals(1, closes.get())

        // B: 结构级 publish —— victim 清理必须等 A 完成，且不得二次 close
        val publish = async(Dispatchers.Default) {
            gate.publishAndCancel(ws, WorkspaceChangeSpec.structural()) {}
        }
        delay(50)
        closeGate.complete(Unit)
        withTimeout(3_000) { release.await() }
        withTimeout(3_000) { publish.await() }

        assertEquals(1, closes.get())
        assertEquals(1, awaits.get())
        assertEquals(0, gate.snapshot(ws).registeredHandles)
        Unit
    }

    @Test
    fun `close succeeded but await failed - retry only re-awaits, never closes twice`() = runBlocking {
        val gate = newGate()
        val ws = "ws-retry"
        val closes = AtomicInteger(0)
        val awaits = AtomicInteger(0)
        val failAwait = AtomicBoolean(true)

        val handle = gate.createHandle(
            workspaceId = ws,
            handleClass = WorkspaceHandleClass.MODEL_BASH,
            close = { closes.incrementAndGet() },
            awaitClosed = {
                awaits.incrementAndGet()
                if (failAwait.get()) error("await failed once")
            },
            spawn = { "bash" },
        )

        val first = runCatching { gate.releaseHandle(handle.token) }
        assertTrue(first.exceptionOrNull() is WorkspaceGateCleanupException)
        assertEquals(1, closes.get())
        assertEquals(1, awaits.get())
        // 失败不静默：handle 留在册且工作区 quarantine
        assertEquals(1, gate.snapshot(ws).registeredHandles)
        assertTrue(gate.snapshot(ws).quarantined)

        // 重试：只重跑 awaitClosed，不二次 close
        failAwait.set(false)
        gate.releaseHandle(handle.token)
        assertEquals(1, closes.get())
        assertEquals(2, awaits.get())
        assertEquals(0, gate.snapshot(ws).registeredHandles)
    }

    @Test
    fun `cancel inside registration window still closes spawned handle - no leak`() = runBlocking {
        // spawn 已成功、登记过程中抛出 CE：资源归属还在 createHandle，必须 NonCancellable
        // 收尾，不能把孤儿 handle 漏掉
        val gate = newGate()
        val ws = "ws-own"
        val closes = AtomicInteger(0)
        val awaits = AtomicInteger(0)

        val spawned = AtomicBoolean(false)
        val validateCalls = AtomicInteger(0)
        val job = launch {
            runCatching {
                gate.createHandle(
                    workspaceId = ws,
                    handleClass = WorkspaceHandleClass.MODEL_BASH,
                    close = { closes.incrementAndGet() },
                    awaitClosed = { awaits.incrementAndGet() },
                    validate = {
                        // 第 2 次 validate = registerHandle 登记临界区（第 1 次在 admit，
                        // 早于 spawn）。此时 spawn 已完成：取消必须走孤儿资源收尾
                        if (validateCalls.incrementAndGet() == 2) {
                            coroutineContext.job.cancel()
                        }
                        true
                    },
                    spawn = { spawned.set(true); "bash" },
                )
            }
        }
        withTimeout(3_000) { job.join() }
        assertTrue(spawned.get())
        assertEquals(1, closes.get())
        assertEquals(1, awaits.get())
        assertEquals(0, gate.snapshot(ws).registeredHandles)
    }

    // --------------------------------------------------------------- quarantine fail-fast

    @Test
    fun `quarantine fail-fasts snapshot and admission - retry publish recovers`() = runBlocking {
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 300L)
        val ws = "ws-qfail"
        val failClose = AtomicBoolean(true)
        val closes = AtomicInteger(0)
        val handle = gate.createHandle(
            workspaceId = ws,
            handleClass = WorkspaceHandleClass.MODEL_BASH,
            close = {
                closes.incrementAndGet()
                if (failClose.get()) error("close refused")
            },
            spawn = { "bash" },
        )
        assertTrue(gate.isHandleFresh(handle.token))

        // 失败发布 → quarantine：准入不再挂起等待而是 CLEANUP_FAILED fail-fast
        assertTrue(
            runCatching {
                gate.publishAndCancel(ws, WorkspaceChangeSpec.structural()) {}
            }.exceptionOrNull() is WorkspaceGateCleanupException
        )
        assertTrue(gate.snapshot(ws).quarantined)

        try {
            withTimeout(1_000) { gate.awaitAdmission(ws) }
            fail("expected CLEANUP_FAILED")
        } catch (e: WorkspaceUnavailableException) {
            assertEquals(WorkspaceUnavailableReason.CLEANUP_FAILED, e.reason)
        }
        try {
            withTimeout(1_000) { gate.withSnapshot(ws) { "x" } }
            fail("expected CLEANUP_FAILED")
        } catch (e: WorkspaceUnavailableException) {
            assertEquals(WorkspaceUnavailableReason.CLEANUP_FAILED, e.reason)
        }
        try {
            withTimeout(1_000) { gate.withOperation(ws, WorkspaceOperationKind.TOOL) { "never" } }
            fail("expected CLEANUP_FAILED")
        } catch (e: WorkspaceUnavailableException) {
            assertEquals(WorkspaceUnavailableReason.CLEANUP_FAILED, e.reason)
        }

        // 恢复通道：retry publish 在 admission 临界区内重算 victim（失败的 handle 仍在册），
        // 这次 close 成功 → 出册、解 quarantine、publish 提交
        failClose.set(false)
        gate.publishAndCancel(ws, WorkspaceChangeSpec.structural()) {}
        assertFalse(gate.snapshot(ws).quarantined)
        assertFalse(gate.snapshot(ws).sealed)
        assertEquals(0, gate.snapshot(ws).registeredHandles)
        val after = gate.withOperation(ws, WorkspaceOperationKind.TOOL) { "admitted" }
        assertEquals("admitted", after)
    }

    // --------------------------------------------------------------- closeWorkspace 重入

    @Test
    fun `closeWorkspace from inside registered op is rejected - no self join`() = runBlocking {
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 500L)
        val ws = "ws-rein"
        val outcome = gate.withOperation(ws, WorkspaceOperationKind.TOOL) {
            runCatching { gate.closeWorkspace(ws) }
        }
        assertTrue(outcome.exceptionOrNull() is WorkspaceGateReentrancyException)
    }

    @Test
    fun `nested op closing ancestor workspace is rejected - no ancestor self join`() = runBlocking {
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 500L)
        val ws = "ws-ancestor"
        val innerRan = CompletableDeferred<Unit>()
        val outcome = gate.withOperation(ws, WorkspaceOperationKind.TOOL) {
            // 嵌套 op 换 ws 登记（合法子操作），但祖先链含 ws → closeWorkspace(ws) 必须拒
            gate.withOperation("ws-other", WorkspaceOperationKind.CHILD) {
                innerRan.complete(Unit)
                runCatching { gate.closeWorkspace(ws) }
            }
        }
        innerRan.await()
        assertTrue(outcome.exceptionOrNull() is WorkspaceGateReentrancyException)
        // close 被拒后 ws 仍可用
        assertFalse(gate.snapshot(ws).closed)
    }

    @Test
    fun `closeWorkspace retries pending handle cleanup on repeat call`() = runBlocking {
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 300L)
        val ws = "ws-cretry"
        val failClose = AtomicBoolean(true)
        val closes = AtomicInteger(0)
        gate.createHandle(
            workspaceId = ws,
            handleClass = WorkspaceHandleClass.MODEL_BASH,
            close = {
                closes.incrementAndGet()
                if (failClose.get()) error("close refused")
            },
            spawn = { "bash" },
        )

        val first = runCatching { gate.closeWorkspace(ws) }
        assertTrue(first.exceptionOrNull() is WorkspaceGateCleanupException)
        // 清理失败 = committed=false（阻 Repository.delete），不静默宣称已收回
        assertEquals(false, gate.eventsFlow(ws).value?.committed)
        assertTrue(gate.snapshot(ws).closed)
        assertEquals(1, gate.snapshot(ws).registeredHandles)

        // 重试不跳过残留：这次 close 成功 → 出册、committed=true
        failClose.set(false)
        gate.closeWorkspace(ws)
        assertEquals(0, gate.snapshot(ws).registeredHandles)
        assertEquals(true, gate.eventsFlow(ws).value?.committed)
        // closed 墓碑仍在：准入永远 WORKSPACE_CLOSED
        try {
            gate.withOperation(ws, WorkspaceOperationKind.TOOL) { "never" }
            fail("expected WORKSPACE_CLOSED")
        } catch (e: WorkspaceUnavailableException) {
            assertEquals(WorkspaceUnavailableReason.WORKSPACE_CLOSED, e.reason)
        }
    }

    // --------------------------------------------------------------- 竞态加固

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `op join timeout keeps record and quarantines - not silently deregistered`() = runBlocking {
        // op body 结束后遗留下无视取消的子协程：record.job.join 超时不得冒充完成——
        // record 留在册并 quarantine（挂 cleanupPending），抛 JoinTimeout；retry
        // publish 对账时 child 已完成 → 清账复入册、解 quarantine。
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 300L)
        val ws = "ws-opjoin"
        val childParked = CompletableDeferred<Unit>()
        val childRelease = CompletableDeferred<Unit>()

        try {
            val thrown = runCatching {
                gate.withOperation(ws, WorkspaceOperationKind.TOOL, stableIds = setOf("tool-x")) {
                    // Attach directly to the gate record Job, not runBlocking's receiver Job
                    // nor withContext's ScopeCoroutine (which waits for its own children).
                    val operationContext = kotlinx.coroutines.currentCoroutineContext()
                    val recordJob = requireNotNull(operationContext.job.parent)
                    CoroutineScope(operationContext.minusKey(Job) + recordJob).launch {
                        withContext(NonCancellable) {
                            childParked.complete(Unit)
                            childRelease.await()
                        }
                    }
                    childParked.await() // barrier：child 已不可取消地占住
                    "done"
                }
            }
            assertTrue(thrown.exceptionOrNull() is WorkspaceGateJoinTimeoutException)
            // 不静默出册：record 仍在册（遗留子协程未走完），工作区 quarantine
            assertEquals(1, gate.snapshot(ws).activeOperations)
            assertTrue(gate.snapshot(ws).quarantined)
            assertTrue(gate.snapshot(ws).sealed)
            // 阻塞新准入（fail-fast CLEANUP_FAILED）
            try {
                withTimeout(1_000) { gate.awaitAdmission(ws) }
                fail("expected CLEANUP_FAILED")
            } catch (e: WorkspaceUnavailableException) {
                assertEquals(WorkspaceUnavailableReason.CLEANUP_FAILED, e.reason)
            }

            // record finished 留给 retry cleanup：遗留子协程放行后，retry publish 把该
            // record 当 victim join（等其 job 真正完成）并代为清账 → 解 quarantine
            childRelease.complete(Unit)
            gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("tool-x"))) {}
            assertFalse(gate.snapshot(ws).quarantined)
            assertFalse(gate.snapshot(ws).sealed)
            assertEquals(0, gate.snapshot(ws).activeOperations)
            val after = gate.withOperation(ws, WorkspaceOperationKind.TOOL) { "admitted" }
            assertEquals("admitted", after)
        } finally {
            // runBlocking 是 record.job 的父 Job：断言失败也必须放行遗留子协程，
            // 否则 NonCancellable 挂起的 child 会永久拖住本测试协程
            childRelease.complete(Unit)
        }
    }

    @Test
    fun `orphan close failure adopts into registry and blocks publish commit - retry cleans it`() = runBlocking {
        // spawn 成功返回时 ws 已封禁（registerHandle 判 sealed → null）→ 孤儿收尾
        // close 失败不得只 addSuppressed 丢成册外孤儿：收养进 handles 册 + quarantine；
        // publish join 对账重扫重试收养记录仍失败 → 必须失败而不发 committed=true；
        // retry publish 清理收养记录后解 quarantine 并正常提交。
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 2_000L)
        val ws = "ws-orphan"
        val closes = AtomicInteger(0)
        val awaits = AtomicInteger(0)
        val failClose = AtomicBoolean(true)
        val publishSealed = CompletableDeferred<Unit>()
        val createAdmitted = CompletableDeferred<Unit>()

        val victimStarted = CompletableDeferred<Unit>()
        val victimReleased = CompletableDeferred<Unit>()
        val victim = async {
            runCatching {
                gateTool(gate, ws, "workspace_shell") {
                    victimStarted.complete(Unit)
                    withContext(NonCancellable) { victimReleased.await() }
                }
            }
        }
        victimStarted.await()

        // create 先登记（stableIds 空：不是 toolPolicy(workspace_shell) 的 victim，
        // 不会被打断）；admit 的 validate 回调是「已入册」barrier，spawn 挂起等 publish
        // 封禁信号——确定性命中「封禁期诞生的孤儿」窗口
        val create = async {
            runCatching {
                gate.createHandle(
                    workspaceId = ws,
                    handleClass = WorkspaceHandleClass.MODEL_BASH,
                    close = {
                        closes.incrementAndGet()
                        if (failClose.get()) error("close refused")
                    },
                    awaitClosed = { awaits.incrementAndGet() },
                    validate = { createAdmitted.complete(Unit); true },
                    spawn = {
                        publishSealed.await()
                        "orphan-bash"
                    },
                )
            }
        }
        try {
            withTimeout(3_000) { createAdmitted.await() } // create op 已入册、停在 spawn

            val publish = async {
                runCatching {
                    gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("workspace_shell"))) {
                        "should-not-commit"
                    }
                }
            }
            // 等 publish 确实封禁（victim join 保持 sealed）再放行 spawn 返回
            withTimeout(3_000) { while (!gate.snapshot(ws).sealed) delay(5) }
            publishSealed.complete(Unit)
            val createResult = withTimeout(3_000) { create.await() }
            assertTrue(createResult.exceptionOrNull() is WorkspaceStaleHandleException)
            // close 失败：资源收养进册 + quarantine（不留册外 live orphan）
            assertEquals(1, closes.get())
            assertEquals(1, gate.snapshot(ws).registeredHandles)
            assertTrue(gate.snapshot(ws).quarantined)

            // publish join 结束后对账重扫：发现收养的欠债记录并重试 close（仍失败）→
            // 必须失败而不能发 committed=true
            victimReleased.complete(Unit)
            val publishResult = withTimeout(3_000) { publish.await() }
            assertTrue(publishResult.exceptionOrNull() is WorkspaceGateCleanupException)
            assertEquals(false, gate.eventsFlow(ws).value?.committed)
            assertTrue(gate.snapshot(ws).sealed)
            assertTrue(gate.snapshot(ws).quarantined)
            assertEquals(1, gate.snapshot(ws).registeredHandles)
            assertTrue(victim.await().exceptionOrNull() is WorkspacePolicyCancellationException)
            assertEquals(2, closes.get()) // 对账重试了收养记录的 close（首次失败允许重试）
            assertEquals(0, awaits.get()) // close 未成功，await 不会跑

            // retry publish：欠债记录在对账中被重试清理，成功后出册、解 quarantine、提交
            failClose.set(false)
            gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("workspace_shell"))) {}
            assertEquals(0, gate.snapshot(ws).registeredHandles)
            assertFalse(gate.snapshot(ws).quarantined)
            assertFalse(gate.snapshot(ws).sealed)
            assertEquals(true, gate.eventsFlow(ws).value?.committed)
            assertEquals(3, closes.get())
            assertEquals(1, awaits.get())
            val after = gate.withOperation(ws, WorkspaceOperationKind.TOOL) { "admitted" }
            assertEquals("admitted", after)
            Unit
        } finally {
            // barrier 安全阀：断言路径任何失败都必须放行 victim/spawn，
            // 否则 NonCancellable 挂起的协程会永久拖住 runBlocking
            publishSealed.complete(Unit)
            victimReleased.complete(Unit)
        }
    }

    @Test
    fun `minted but undelivered handle is retired by owning op - no orphan on cancel`() = runBlocking {
        // 登记成功 → 返回值交付之间存在取消窄窗：资源 ownership 在外层 op，op 异常
        // 退出时必须 NonCancellable retire（走 HandleRecord 门），不丢 live orphan。
        // stableIds 为空：publish victim 集不含该 handle，唯有 op 收尾路径能收回它。
        val gate = newGate()
        val ws = "ws-minted"
        val closes = AtomicInteger(0)
        val awaits = AtomicInteger(0)
        val created = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()

        try {
            val op = async {
                runCatching {
                    gate.withOperation(ws, WorkspaceOperationKind.TOOL, stableIds = setOf("tool-x")) {
                        gate.createHandle(
                            workspaceId = ws,
                            handleClass = WorkspaceHandleClass.GENERIC,
                            close = { closes.incrementAndGet() },
                            awaitClosed = { awaits.incrementAndGet() },
                            spawn = { "inner-handle" },
                        )
                        created.complete(Unit)
                        proceed.await() // 挂起等 victim 化，制造取消窗口
                        "never"
                    }
                }
            }
            withTimeout(3_000) { created.await() } // handle 已登记入册、交付 op body
            // toolPolicy(tool-x) 波及 op（victim）但不波及空 stableIds 的 GENERIC handle
            withTimeout(3_000) {
                gate.publishAndCancel(ws, WorkspaceChangeSpec.toolPolicy(setOf("tool-x"))) {}
            }
            // publish 已 join 完（victim op 已彻底收尾）才返回：此时 minted 收尾必然完成
            assertTrue(
                withTimeout(3_000) { op.await() }.exceptionOrNull() is WorkspacePolicyCancellationException
            )
            assertEquals(1, closes.get())
            assertEquals(1, awaits.get())
            assertEquals(0, gate.snapshot(ws).registeredHandles)
            Unit
        } finally {
            proceed.complete(Unit)
        }
    }

    // --------------------------------------------------------------- 全局吊销基数

    @Test
    fun `structural bump revokes never-before-seen stable ids via global base`() = runBlocking {
        val gate = newGate()
        val ws = "ws-base"
        // 新接线工具从未被 bump 过：offset=0，但捕获的是结构变更前的基数
        val captured = gate.currentToolEpoch(ws, "future_tool")
        gate.publishAndCancel(ws, WorkspaceChangeSpec.structural()) {}
        assertTrue(gate.currentToolEpoch(ws, "future_tool") > captured)
        try {
            gate.withOperation(
                workspaceId = ws,
                kind = WorkspaceOperationKind.TOOL,
                stableIds = setOf("future_tool"),
                expectedToolEpochs = mapOf("future_tool" to captured),
            ) { "should-not-run" }
            fail("expected STABLE_ID_REVOKED")
        } catch (e: WorkspaceUnavailableException) {
            assertEquals(WorkspaceUnavailableReason.STABLE_ID_REVOKED, e.reason)
        }
        // 新捕获的有效 epoch 可执行
        val ran = gate.withOperation(
            workspaceId = ws,
            kind = WorkspaceOperationKind.TOOL,
            stableIds = setOf("future_tool"),
            expectedToolEpochs = mapOf("future_tool" to gate.currentToolEpoch(ws, "future_tool")),
        ) { "fresh-ran" }
        assertEquals("fresh-ran", ran)
        // 快照给调用方的 toolEpochs 同样是含基数的有效值
        val known = gate.snapshot(ws).toolEpochs
        known.forEach { (id, v) -> assertEquals(v, gate.currentToolEpoch(ws, id)) }
    }
}
