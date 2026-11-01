package me.rerere.rikkihub.data.repository

import me.rerere.workspace.LocalWorkspaceBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.SubagentPresetConflictException
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolEnablement
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.dao.WorkspaceDAO
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.workspace.WorkspaceChangeSpec
import me.rerere.rikkihub.data.workspace.WorkspaceEpochs
import me.rerere.rikkihub.data.workspace.WorkspaceOperationKind
import me.rerere.rikkihub.data.workspace.WorkspacePolicyCancellationException
import me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate
import me.rerere.rikkihub.data.workspace.WorkspaceUnavailableException
import me.rerere.rikkihub.data.workspace.WorkspaceUnavailableReason
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.nio.file.Files

/**
 * WorkspaceRepository → WorkspaceRuntimeGate 发布通道的单测（纯 JVM）。
 *
 * Repo 构造：FakeWorkspaceDAO（内存表，与 WorkspaceDaoUpdateTest 一致的事务模型）
 * + 真 WorkspaceRuntimeGate + 临时目录 WorkspaceManager。SettingsStore 用
 * Unsafe.allocateInstance 拿到空壳（delete 的助手引用清理才会碰到它，
 * 本套件不测删除，也不声称 Room 真机行为）。
 *
 * 覆盖：并发不同 toolEnablement 键不丢、单工具关闭只波及 affected 操作与
 * 所在 ws、元数据更新不取消工具、no-op 不动 epoch、非法 patch 无 side effect、
 * prepare 读最新与事务写共用同一 baseline、disable→enable 吊销旧实例、
 * 备份已有 disabled/approvals 状态不被其他 patch 清空、subagent 内嵌套 child
 * 工具经 gate 准入后被单独热关闭而父操作继续存活。
 */
class WorkspaceRepositoryPublicationTest {

    // --------------------------------------------------------- 假 DAO（拷贝自 WorkspaceDaoUpdateTest）

    private class FakeWorkspaceDAO : WorkspaceDAO {
        private val rows = LinkedHashMap<String, WorkspaceEntity>()
        private val flow = MutableStateFlow<List<WorkspaceEntity>>(emptyList())

        /** 单语句原子性 */
        private val statement = Mutex()

        /** 事务串行化：包住接口默认事务方法，模拟 Room @Transaction */
        private val transaction = Mutex()

        private fun publish() {
            flow.value = rows.values.sortedByDescending { it.updatedAt }
        }

        override fun listFlow(): Flow<List<WorkspaceEntity>> = flow

        override suspend fun getById(id: String): WorkspaceEntity? = statement.withLock {
            rows[id]
        }

        override suspend fun insert(workspace: WorkspaceEntity) = statement.withLock {
            check(workspace.id !in rows) { "INSERT aborted: duplicate id ${workspace.id}" }
            rows[workspace.id] = workspace
            publish()
            Unit
        }

        override suspend fun getAll(): List<WorkspaceEntity> = statement.withLock {
            rows.values.sortedByDescending { it.updatedAt }
        }

        override suspend fun updateName(id: String, name: String, updatedAt: Long): Int =
            statement.withLock {
                val row = rows[id] ?: return@withLock 0
                rows[id] = row.copy(name = name, updatedAt = updatedAt)
                publish()
                1
            }

        override suspend fun updateSubagentModelId(id: String, modelId: String?, updatedAt: Long): Int =
            statement.withLock {
                val row = rows[id] ?: return@withLock 0
                rows[id] = row.copy(subagentModelId = modelId, updatedAt = updatedAt)
                publish()
                1
            }

        override suspend fun updateSubagentAllowContext(
            id: String,
            allowContext: Boolean,
            updatedAt: Long,
        ): Int = statement.withLock {
            val row = rows[id] ?: return@withLock 0
            rows[id] = row.copy(subagentAllowContext = allowContext, updatedAt = updatedAt)
            publish()
            1
        }

        override suspend fun updateExposeUpload(id: String, exposed: Boolean, updatedAt: Long): Int =
            statement.withLock {
                val row = rows[id] ?: return@withLock 0
                rows[id] = row.copy(exposeUpload = exposed, updatedAt = updatedAt)
                publish()
                1
            }

        override suspend fun updateExposeSkills(id: String, exposed: Boolean, updatedAt: Long): Int =
            statement.withLock {
                val row = rows[id] ?: return@withLock 0
                rows[id] = row.copy(exposeSkills = exposed, updatedAt = updatedAt)
                publish()
                1
            }

        override suspend fun updateExposeToolOutputs(id: String, exposed: Boolean, updatedAt: Long): Int =
            statement.withLock {
                val row = rows[id] ?: return@withLock 0
                rows[id] = row.copy(exposeToolOutputs = exposed, updatedAt = updatedAt)
                publish()
                1
            }

        override suspend fun updateToolApprovalsJson(id: String, json: String, updatedAt: Long): Int =
            statement.withLock {
                val row = rows[id] ?: return@withLock 0
                rows[id] = row.copy(toolApprovals = json, updatedAt = updatedAt)
                publish()
                1
            }

        override suspend fun updateToolEnablementJson(id: String, json: String, updatedAt: Long): Int =
            statement.withLock {
                val row = rows[id] ?: return@withLock 0
                rows[id] = row.copy(toolEnablement = json, updatedAt = updatedAt)
                publish()
                1
            }

        override suspend fun updateSubagentPresetsJson(id: String, json: String, updatedAt: Long): Int =
            statement.withLock {
                val row = rows[id] ?: return@withLock 0
                rows[id] = row.copy(subagentPresets = json, updatedAt = updatedAt)
                publish()
                1
            }

        override suspend fun updateLastAccessAt(id: String, lastAccessAt: Long?, updatedAt: Long): Int =
            statement.withLock {
                val row = rows[id] ?: return@withLock 0
                rows[id] = row.copy(lastAccessAt = lastAccessAt, updatedAt = updatedAt)
                publish()
                1
            }

        override suspend fun touchUpdatedAt(id: String, updatedAt: Long): Int =
            statement.withLock {
                val row = rows[id] ?: return@withLock 0
                rows[id] = row.copy(updatedAt = updatedAt)
                publish()
                1
            }

        override suspend fun updateShellStatus(id: String, shellStatus: String, updatedAt: Long): Int =
            statement.withLock {
                val row = rows[id] ?: return@withLock 0
                rows[id] = row.copy(shellStatus = shellStatus, updatedAt = updatedAt)
                publish()
                1
            }

        override suspend fun setShellCompatibilityMode(id: String, enabled: Boolean, updatedAt: Long): Int =
            statement.withLock {
                val row = rows[id] ?: return@withLock 0
                rows[id] = row.copy(shellCompatibilityMode = enabled, updatedAt = updatedAt)
                publish()
                1
            }
        override suspend fun setFirstMessageFileSnapshot(id: String, enabled: Boolean, updatedAt: Long): Int =
            statement.withLock {
                val row = rows[id] ?: return@withLock 0
                rows[id] = row.copy(firstMessageFileSnapshot = enabled, updatedAt = updatedAt)
                publish()
                1
            }
        override suspend fun setFirstMessageFileSnapshotDepth(id: String, depth: Int, updatedAt: Long): Int =
            statement.withLock {
                val row = rows[id] ?: return@withLock 0
                rows[id] = row.copy(firstMessageFileSnapshotDepth = depth, updatedAt = updatedAt)
                publish()
                1
            }

        override suspend fun updateInTransaction(
            id: String,
            transform: suspend (WorkspaceEntity) -> WorkspaceEntity,
        ): WorkspaceEntity? = transaction.withLock {
            yield()
            super.updateInTransaction(id, transform)
        }

        override suspend fun mergeToolApprovals(
            id: String,
            patch: Map<String, Boolean>,
            updatedAt: Long,
        ): Boolean = transaction.withLock {
            yield()
            super.mergeToolApprovals(id, patch, updatedAt)
        }

        override suspend fun mergeToolEnablement(
            id: String,
            patch: Map<String, Boolean>,
            updatedAt: Long,
        ): Boolean = transaction.withLock {
            yield()
            super.mergeToolEnablement(id, patch, updatedAt)
        }

        override suspend fun deleteById(id: String): Int = statement.withLock {
            val removed = rows.remove(id) != null
            publish()
            if (removed) 1 else 0
        }
    }

    // --------------------------------------------------------- harness

    private class Harness(
        val dao: FakeWorkspaceDAO,
        val gate: WorkspaceRuntimeGate,
        val repo: WorkspaceRepository,
        val baseDir: File,
    ) : Closeable {
        override fun close() {
            baseDir.deleteRecursively()
        }
    }

    /**
     * SettingsStore 需要 Context/DataStore，纯 JVM 单测无法构造；本套件不会走到
     * 使用它的 repo.delete()，用 Unsafe 分配一个字段全 null 的空壳实例占位。
     */
    @Suppress("UNCHECKED_CAST")
    private fun detachedSettingsStore(): SettingsStore {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, SettingsStore::class.java) as SettingsStore
    }

    private fun newHarness(): Harness {
        val baseDir = Files.createTempDirectory("ws-repo-test").toFile()
        val dao = FakeWorkspaceDAO()
        val gate = WorkspaceRuntimeGate(joinTimeoutMillis = 2_000L)
        val repo = WorkspaceRepository(
            dao = dao,
            manager = LocalWorkspaceBackend(WorkspaceManager(baseDir), RootfsInstaller(WorkspaceManager(baseDir)), java.io.File("/nonexistent-native"), null),
            settingsStore = detachedSettingsStore(),
            runtimeGate = gate,
        )
        return Harness(dao, gate, repo, baseDir)
    }

    private fun newWorkspace(
        id: String,
        name: String = id,
        shellStatus: String = WorkspaceShellStatus.READY.name,
        toolEnablement: String = "{}",
        toolApprovals: String = "{}",
    ) = WorkspaceEntity(
        id = id,
        name = name,
        root = id,
        shellStatus = shellStatus,
        createdAt = 1L,
        updatedAt = 1L,
        toolEnablement = toolEnablement,
        toolApprovals = toolApprovals,
    )

    private suspend fun toolOp(
        gate: WorkspaceRuntimeGate,
        ws: String,
        stableId: String,
        expectedToolEpochs: Map<String, Long>? = null,
        body: suspend (WorkspaceEpochs) -> Unit = {},
    ) = gate.withOperation(
        workspaceId = ws,
        kind = WorkspaceOperationKind.TOOL,
        stableIds = setOf(stableId),
        expectedToolEpochs = expectedToolEpochs,
    ) { epochs -> body(epochs) }

    // --------------------------------------------------------- 构造与读路径

    @Test
    fun `runtimeGate ctor default creates independent gate`() = runBlocking {
        val baseDir = Files.createTempDirectory("ws-repo-ctor").toFile()
        try {
            val repoA = WorkspaceRepository(
                dao = FakeWorkspaceDAO(),
                manager = LocalWorkspaceBackend(WorkspaceManager(baseDir), RootfsInstaller(WorkspaceManager(baseDir)), java.io.File("/nonexistent-native"), null),
                settingsStore = detachedSettingsStore(),
            )
            val repoB = WorkspaceRepository(
                dao = FakeWorkspaceDAO(),
                manager = LocalWorkspaceBackend(WorkspaceManager(baseDir), RootfsInstaller(WorkspaceManager(baseDir)), java.io.File("/nonexistent-native"), null),
                settingsStore = detachedSettingsStore(),
            )
            assertNotNull(repoA.runtimeGate)
            assertFalse(repoA.runtimeGate === repoB.runtimeGate)
            repoA.runtimeGate.publishAndCancel(
                "ws-x",
                WorkspaceChangeSpec.toolPolicy(setOf("t")),
            ) {}
            assertEquals(1L, repoA.runtimeGate.currentEpoch("ws-x"))
            assertEquals(0L, repoB.runtimeGate.currentEpoch("ws-x"))
            Unit
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun `getByIdFlow emits latest row after publication`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val seen = mutableListOf<WorkspaceEntity?>()
            val collector = launch { h.repo.getByIdFlow("w1").collect { seen += it } }
            withTimeout(3_000) { while (seen.isEmpty()) delay(5) }

            h.repo.setToolEnablement("w1", BuiltinToolIds.WORKSPACE_SHELL, enabled = false)
            withTimeout(3_000) { while (seen.size < 2) delay(5) }
            collector.cancel()

            assertEquals(2, seen.size)
            assertNotNull(seen[0])
            assertEquals(
                mapOf(BuiltinToolIds.WORKSPACE_SHELL to false),
                WorkspaceToolEnablement.parse(seen[1]!!.toolEnablement),
            )
            Unit
        }
    }

    // --------------------------------------------------------- 并发不同键不丢

    @Test
    fun `concurrent setToolEnablement different keys preserves all through real gate`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            coroutineScope {
                awaitAll(
                    async(Dispatchers.Default) { h.repo.setToolEnablement("w1", BuiltinToolIds.WORKSPACE_SHELL, false) },
                    async(Dispatchers.Default) { h.repo.setToolEnablement("w1", BuiltinToolIds.WORKSPACE_READ_FILE, false) },
                    async(Dispatchers.Default) { h.repo.setToolEnablement("w1", BuiltinToolIds.WORKSPACE_EDIT_FILE, false) },
                    async(Dispatchers.Default) { h.repo.setToolEnablement("w1", BuiltinToolIds.SUBAGENT, false) },
                    async(Dispatchers.Default) { h.repo.setToolEnablement("w1", BuiltinToolIds.BASH, false) },
                )
            }
            val overrides = WorkspaceToolEnablement.parse(h.dao.getById("w1")!!.toolEnablement)
            assertEquals(
                setOf(
                    BuiltinToolIds.WORKSPACE_SHELL,
                    BuiltinToolIds.WORKSPACE_READ_FILE,
                    BuiltinToolIds.WORKSPACE_EDIT_FILE,
                    BuiltinToolIds.SUBAGENT,
                    BuiltinToolIds.BASH,
                ),
                overrides.keys,
            )
            assertTrue(overrides.values.none { it })
            // 每次发布都是一次 policy bump + committed 事件
            assertEquals(5L, h.gate.currentEpoch("w1"))
            assertTrue(h.gate.snapshot("w1").toolEpochs.values.all { it == 1L })
            assertEquals(true, h.gate.eventsFlow("w1").value?.committed)
            Unit
        }
    }

    // --------------------------------------------------------- 热关闭波及面

    @Test
    fun `disabling tool cancels only affected op in that workspace`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            h.dao.insert(newWorkspace("w2", name = "second"))
            val victimStarted = CompletableDeferred<Unit>()
            val victimRelease = CompletableDeferred<Unit>()
            val otherDone = CompletableDeferred<Unit>()
            val ws2Done = CompletableDeferred<Unit>()

            val victim = async(Dispatchers.Default) {
                runCatching {
                    toolOp(h.gate, "w1", BuiltinToolIds.WORKSPACE_SHELL) {
                        victimStarted.complete(Unit)
                        victimRelease.await()
                    }
                }
            }
            val other = async(Dispatchers.Default) {
                toolOp(h.gate, "w1", BuiltinToolIds.WORKSPACE_EDIT_FILE) { otherDone.complete(Unit) }
            }
            val ws2op = async(Dispatchers.Default) {
                toolOp(h.gate, "w2", BuiltinToolIds.WORKSPACE_SHELL) { ws2Done.complete(Unit) }
            }

            victimStarted.await()
            withTimeout(3_000) { otherDone.await() }
            withTimeout(3_000) { ws2Done.await() }

            // 发布会 cancel victim 并等其 join：发布放后台，主线程随后放行 victim 收尾
            val publish = async {
                h.repo.setToolEnablement("w1", BuiltinToolIds.WORKSPACE_SHELL, enabled = false)
            }
            delay(30)
            victimRelease.complete(Unit)
            assertTrue(withTimeout(3_000) { publish.await() })
            val victimResult = withTimeout(3_000) { victim.await() }
            assertTrue(victimResult.exceptionOrNull() is WorkspacePolicyCancellationException)
            other.await()
            ws2op.await()

            val row = h.dao.getById("w1")!!
            assertEquals(
                mapOf(BuiltinToolIds.WORKSPACE_SHELL to false),
                WorkspaceToolEnablement.parse(row.toolEnablement),
            )
            // w2 行与 gate 完全未动
            assertEquals("{}", h.dao.getById("w2")!!.toolEnablement)
            assertEquals(0L, h.gate.currentEpoch("w2"))
            assertNull(h.gate.eventsFlow("w2").value)
            // 只吊销 shell；edit 不受影响
            assertEquals(1L, h.gate.currentToolEpoch("w1", BuiltinToolIds.WORKSPACE_SHELL))
            assertEquals(0L, h.gate.currentToolEpoch("w1", BuiltinToolIds.WORKSPACE_EDIT_FILE))
            assertEquals(0L, h.gate.currentViewEpoch("w1"))
            assertEquals(0, h.gate.snapshot("w1").activeOperations)
            Unit
        }
    }

    // --------------------------------------------------------- 元数据更新不取消工具

    @Test
    fun `approval change bumps policy epoch but never cancels ops`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val opStarted = CompletableDeferred<Unit>()
            val opRelease = CompletableDeferred<Unit>()
            val op = async(Dispatchers.Default) {
                runCatching {
                    toolOp(h.gate, "w1", BuiltinToolIds.WORKSPACE_SHELL) {
                        opStarted.complete(Unit)
                        opRelease.await()
                    }
                }
            }
            opStarted.await()

            // tool_approvals 差异 → toolPolicy(emptySet)：bump policy epoch、不发受害
            assertTrue(h.repo.setToolApproval("w1", BuiltinToolIds.WORKSPACE_SHELL, needsApproval = false))
            assertTrue(op.isActive) // 发布完成期间操作未被取消

            opRelease.complete(Unit)
            val result = withTimeout(3_000) { op.await() }
            assertTrue("approval 变更不得把运行中工具打成 policy cancel", result.isSuccess)
            assertEquals(1L, h.gate.currentEpoch("w1"))
            assertEquals(0L, h.gate.currentToolEpoch("w1", BuiltinToolIds.WORKSPACE_SHELL))
            val event = h.gate.eventsFlow("w1").value
            assertNotNull(event)
            assertEquals(emptySet<String>(), event!!.affectedStableIds)
            assertTrue(event.committed)
            assertEquals(
                mapOf(BuiltinToolIds.WORKSPACE_SHELL to false),
                h.dao.getById("w1")!!.toolApprovalOverrides(),
            )
            Unit
        }
    }

    // --------------------------------------------------------- no-op 不动 epoch

    @Test
    fun `no-op patch keeps epochs and emits no event`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val opDone = CompletableDeferred<Unit>()
            val op = async(Dispatchers.Default) {
                toolOp(h.gate, "w1", BuiltinToolIds.WORKSPACE_SHELL) { opDone.complete(Unit) }
            }
            withTimeout(3_000) { opDone.await() }

            val updated = h.repo.update("w1") { it }
            assertNotNull(updated)
            assertEquals(0L, h.gate.currentEpoch("w1"))
            assertEquals(0L, h.gate.currentViewEpoch("w1"))
            assertNull("no-op 不发变更事件", h.gate.eventsFlow("w1").value)
            assertFalse(h.gate.snapshot("w1").sealed)
            op.await()
            // 行仍在（updated_at 被 touch 刷新）
            assertTrue(h.dao.getById("w1")!!.updatedAt > 0L)
            Unit
        }
    }

    // --------------------------------------------------------- 非法 patch 无 side effect

    @Test
    fun `illegal patch throws before seal and leaves no side effects`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))

            // 1) transform 写出损坏 enablement：prepare 阶段抛错
            val corrupt = runCatching {
                h.repo.update("w1") { it.copy(toolEnablement = "not-json") }
            }.exceptionOrNull()
            assertTrue(corrupt is IllegalArgumentException)

            // 2) 身份字段变更：require 在 prepare 阶段拦截
            val identity = runCatching {
                h.repo.update("w1") { it.copy(root = "other-root") }
            }.exceptionOrNull()
            assertTrue(identity is IllegalArgumentException)

            // 3) setToolEnablement 未知 ID：mergedJson 校验在 transform 内抛错
            val unknown = runCatching {
                h.repo.setToolEnablement("w1", BuiltinToolIds.SEARCH_WEB, enabled = false)
            }.exceptionOrNull()
            assertTrue(unknown is IllegalArgumentException)

            // 无 side effect：DB 原样、无 epoch、无事件、无 seal，新准入照常
            val row = h.dao.getById("w1")!!
            assertEquals("{}", row.toolEnablement)
            assertEquals("w1", row.root)
            assertEquals(0L, h.gate.currentEpoch("w1"))
            assertNull(h.gate.eventsFlow("w1").value)
            assertFalse(h.gate.snapshot("w1").sealed)
            toolOp(h.gate, "w1", BuiltinToolIds.WORKSPACE_SHELL) {}
            Unit
        }
    }

    // --------------------------------------------------------- prepare 读最新 / 事务同 baseline

    @Test
    fun `prepare reads latest row and patch preserves concurrent outside write`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            // 绕过 repo 的直接定向写（如 Web 定向 SQL 通道）
            h.dao.updateName("w1", "outside-rename", updatedAt = 2L)
            var transformSawName: String? = null
            val updated = h.repo.applyConfigPatch("w1") { current ->
                transformSawName = current.name
                current.copy(subagentAllowContext = true)
            }
            assertNotNull(updated)
            assertEquals("prepare 必须读到 admission 锁内最新行", "outside-rename", transformSawName)
            val row = h.dao.getById("w1")!!
            assertEquals("outside-rename", row.name)
            assertTrue(row.subagentAllowContext)
            assertEquals(true, h.gate.eventsFlow("w1").value!!.committed)
            Unit
        }
    }

    @Test
    fun `publish baseline check fails loud on out-of-band write during victim cleanup`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val victimStarted = CompletableDeferred<Unit>()
            // victim 操作被取消时，其收尾在 gate 外绕 repo 直写 DAO：
            // publish 事务的 latest != baseline 检查必须 fail，不静默发布过期实体
            val victim = async(Dispatchers.Default) {
                runCatching {
                    h.gate.withOperation(
                        workspaceId = "w1",
                        kind = WorkspaceOperationKind.TOOL,
                        stableIds = setOf(BuiltinToolIds.WORKSPACE_SHELL),
                    ) {
                        try {
                            victimStarted.complete(Unit)
                            CompletableDeferred<Unit>().await()
                        } finally {
                            withContext(NonCancellable) {
                                h.dao.updateName("w1", "bypassed", updatedAt = 9L)
                            }
                        }
                    }
                }
            }
            victimStarted.await()
            val outcome = runCatching {
                h.repo.setToolEnablement("w1", BuiltinToolIds.WORKSPACE_SHELL, enabled = false)
            }
            assertTrue(
                "绕过发布通道的并发写必须触发 baseline 校验失败",
                outcome.exceptionOrNull() is IllegalStateException,
            )
            victim.await()
            // 未提交：enablement 仍 "{}"，外部 name 写保留（不是 repo 回滚的）
            val row = h.dao.getById("w1")!!
            assertEquals("{}", row.toolEnablement)
            assertEquals("bypassed", row.name)
            assertEquals(false, h.gate.eventsFlow("w1").value!!.committed)
            Unit
        }
    }

    // --------------------------------------------------------- disable→enable 吊销旧实例

    @Test
    fun `disable then reenable revokes stale tool instance`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val staleEpoch = h.gate.currentToolEpoch("w1", BuiltinToolIds.WORKSPACE_SHELL)
            assertEquals(0L, staleEpoch)

            assertTrue(h.repo.setToolEnablement("w1", BuiltinToolIds.WORKSPACE_SHELL, enabled = false))
            assertTrue(h.repo.setToolEnablement("w1", BuiltinToolIds.WORKSPACE_SHELL, enabled = true))
            assertEquals(2L, h.gate.currentToolEpoch("w1", BuiltinToolIds.WORKSPACE_SHELL))

            // 装配期捕获的旧 instance（epoch=0）不得复活
            val stale = runCatching {
                toolOp(
                    h.gate, "w1", BuiltinToolIds.WORKSPACE_SHELL,
                    expectedToolEpochs = mapOf(BuiltinToolIds.WORKSPACE_SHELL to staleEpoch),
                ) {}
            }.exceptionOrNull()
            assertTrue(stale is WorkspaceUnavailableException)
            assertEquals(
                WorkspaceUnavailableReason.STABLE_ID_REVOKED,
                (stale as WorkspaceUnavailableException).reason,
            )
            // 重新捕获的准入可执行；DB 最终值为显式启用
            toolOp(
                h.gate, "w1", BuiltinToolIds.WORKSPACE_SHELL,
                expectedToolEpochs = mapOf(
                    BuiltinToolIds.WORKSPACE_SHELL to h.gate.currentToolEpoch("w1", BuiltinToolIds.WORKSPACE_SHELL),
                ),
            ) {}
            assertEquals(
                mapOf(BuiltinToolIds.WORKSPACE_SHELL to true),
                WorkspaceToolEnablement.parse(h.dao.getById("w1")!!.toolEnablement),
            )
            Unit
        }
    }

    // --------------------------------------------------------- 备份 disabled/approvals 保持

    @Test
    fun `unrelated patches preserve stored disabled state approvals and disabled shell status`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(
                newWorkspace(
                    "w1",
                    shellStatus = WorkspaceShellStatus.DISABLED.name,
                    toolEnablement = """{"workspace_shell":false}""",
                    toolApprovals = """{"workspace_edit_file":true}""",
                )
            )
            // 元数据 + 其他工具开关都不允许清空既有 disabled/approvals/shellStatus
            assertTrue(h.repo.rename("w1", "renamed-ws"))
            assertTrue(h.repo.setToolEnablement("w1", BuiltinToolIds.SUBAGENT, enabled = false))
            assertTrue(h.repo.setToolApproval("w1", BuiltinToolIds.WORKSPACE_SHELL, needsApproval = true))

            val row = h.dao.getById("w1")!!
            assertEquals("renamed-ws", row.name)
            assertEquals(WorkspaceShellStatus.DISABLED.name, row.shellStatus)
            assertEquals(
                mapOf(BuiltinToolIds.WORKSPACE_SHELL to false, BuiltinToolIds.SUBAGENT to false),
                WorkspaceToolEnablement.parse(row.toolEnablement),
            )
            assertEquals(
                mapOf(BuiltinToolIds.WORKSPACE_EDIT_FILE to true, BuiltinToolIds.WORKSPACE_SHELL to true),
                row.toolApprovalOverrides(),
            )
            // 只有 subagent 一次真实翻转（true→false）→ 仅它 bump；
            // 一直在禁用的 shell 不能因其他 patch 被莫名吊销
            assertEquals(0L, h.gate.currentToolEpoch("w1", BuiltinToolIds.WORKSPACE_SHELL))
            assertEquals(1L, h.gate.currentToolEpoch("w1", BuiltinToolIds.SUBAGENT))
            Unit
        }
    }

    // --------------------------------------------------------- subagent 内嵌套 child 热关闭

    @Test
    fun `nested child tool op is cancelled while subagent parent stays active`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val childStarted = CompletableDeferred<Unit>()
            val childRelease = CompletableDeferred<Unit>()
            val parentProceed = CompletableDeferred<Result<String>>()
            var childResult: Result<String>? = null
            var parentActiveAfterCancel = false

            // 父 = subagent 操作（stableIds=subagent）。child 工具调用在主线程
            // 作为 CHILD op 经同一 gate 嵌套准入（修复后的适配语义）：
            // 禁用 workspace_shell 只取消 child，父 Job 不级联、父操作仍在册。
            val parent = async(Dispatchers.Default) {
                h.gate.withOperation(
                    workspaceId = "w1",
                    kind = WorkspaceOperationKind.TOOL,
                    stableIds = setOf(BuiltinToolIds.SUBAGENT),
                ) {
                    childResult = runCatching {
                        h.gate.withOperation(
                            workspaceId = "w1",
                            kind = WorkspaceOperationKind.CHILD,
                            stableIds = setOf(BuiltinToolIds.WORKSPACE_SHELL),
                        ) {
                            childStarted.complete(Unit)
                            childRelease.await()
                            "child-finished"
                        }
                    }
                    // child 被取消（或直接失败）后父仍须 active —— 它的 Job 是调用方 SupervisorJob 的子节点
                    parentActiveAfterCancel = currentCoroutineContext()[Job]!!.isActive
                    "parent-survived:${parentProceed.await().getOrThrow()}"
                }
            }
            childStarted.await()
            assertEquals(2, h.gate.snapshot("w1").activeOperations)

            // 真实 repo 发布通道：只吊销 workspace_shell
            val publish = async { h.repo.setToolEnablement("w1", BuiltinToolIds.WORKSPACE_SHELL, enabled = false) }
            delay(50)
            childRelease.complete(Unit) // child 收尾，让 publish join 完成
            publish.await()

            // 此刻父仍登记在册（统计里只剩父一个 active op）
            assertEquals(1, h.gate.snapshot("w1").activeOperations)
            parentProceed.complete(Result.success("done"))
            val parentResult = withTimeout(3_000) { parent.await() }

            assertTrue(
                "child 应是 policy cancel（独立 victim），不是父传播取消",
                childResult!!.exceptionOrNull() is WorkspacePolicyCancellationException,
            )
            assertEquals("parent-survived:done", parentResult)
            assertTrue("父 subagent 操作在 child 取消后必须仍 active", parentActiveAfterCancel)
            assertEquals(
                mapOf(BuiltinToolIds.WORKSPACE_SHELL to false),
                WorkspaceToolEnablement.parse(h.dao.getById("w1")!!.toolEnablement),
            )
            // 只吊销 shell；父的 subagent 不受影响
            assertEquals(1L, h.gate.currentToolEpoch("w1", BuiltinToolIds.WORKSPACE_SHELL))
            assertEquals(0L, h.gate.currentToolEpoch("w1", BuiltinToolIds.SUBAGENT))
            Unit
        }
    }

    // --------------------------------------------------------- workspace subagent preset storage

    private fun preset(key: String = "worker") = WorkspaceSubagentPreset(
        key = key, name = "Worker", description = "Implement changes", systemPrompt = "Work carefully",
    )

    private suspend fun storedPresets(h: Harness) = WorkspaceSubagentPresets.parse(h.dao.getById("w1")!!.subagentPresets)

    @Test
    fun `preset create allocates server ID once normalizes resets counters and appends`() = runBlocking {
        newHarness().use { h ->
            val original = newWorkspace("w1", toolEnablement = "{\"bash\":false}", toolApprovals = "{\"bash\":true}")
            h.dao.insert(original)
            val input = preset().copy(id = "caller-id-is-not-authoritative", key = " worker ", name = " Worker ",
                description = " Implement changes ", sortOrder = 99, version = 99, executionRevision = 99)
            val first = h.repo.createSubagentPreset("w1", input)
            assertFalse(first.id == input.id)
            assertEquals("worker", first.key)
            assertEquals("Worker", first.name)
            assertEquals("Implement changes", first.description)
            assertEquals(1L, first.version)
            assertEquals(1L, first.executionRevision)
            assertEquals(0, first.sortOrder)
            val second = h.repo.createSubagentPreset("w1", preset("second"))
            assertEquals(1, second.sortOrder)
            assertEquals(listOf(first, second), storedPresets(h))
            val row = h.dao.getById("w1")!!
            assertEquals(original.copy(subagentPresets = row.subagentPresets, updatedAt = row.updatedAt), row)
            assertEquals(2L, h.gate.currentEpoch("w1"))
            assertEquals(0L, h.gate.currentToolEpoch("w1", BuiltinToolIds.SUBAGENT))
            Unit
        }
    }

    @Test
    fun `preset update increments edit version but execution revision only for semantics`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val role = h.repo.createSubagentPreset("w1", preset())
            val unrelated = h.repo.createSubagentPreset("w1", preset("other"))
            val display = h.repo.updateSubagentPreset("w1", role.copy(name = " Renamed ", description = " Changed description ",
                sortOrder = 99, version = 999, executionRevision = 999), role.version)
            assertEquals(2L, display.version)
            assertEquals(1L, display.executionRevision)
            assertEquals(0, display.sortOrder)
            assertEquals("Renamed", display.name)
            val execution = h.repo.updateSubagentPreset("w1", display.copy(systemPrompt = "New execution instructions"), display.version)
            assertEquals(3L, execution.version)
            assertEquals(2L, execution.executionRevision)
            assertEquals(unrelated, storedPresets(h).first { it.id == unrelated.id })
            assertEquals(0L, h.gate.currentToolEpoch("w1", BuiltinToolIds.SUBAGENT))
            Unit
        }
    }

    @Test
    fun `preset updates reject key changes and unknown identity`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val role = h.repo.createSubagentPreset("w1", preset())
            val before = h.dao.getById("w1")
            val epochs = h.gate.currentEpoch("w1")
            assertTrue(runCatching { h.repo.updateSubagentPreset("w1", role.copy(key = "changed-key"), 1L) }
                .exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { h.repo.updateSubagentPreset("w1", role.copy(id = preset().id), 1L) }
                .exceptionOrNull() is NoSuchElementException)
            assertEquals(before, h.dao.getById("w1"))
            assertEquals(epochs, h.gate.currentEpoch("w1"))
            Unit
        }
    }

    @Test
    fun `stale versions reject update delete and reorder with no storage or gate side effects`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val role = h.repo.createSubagentPreset("w1", preset())
            val latest = h.repo.updateSubagentPreset("w1", role.copy(name = "Renamed"), 1L)
            val before = h.dao.getById("w1")
            val epoch = h.gate.currentEpoch("w1")
            assertTrue(runCatching { h.repo.updateSubagentPreset("w1", role, 1L) }.exceptionOrNull() is SubagentPresetConflictException)
            assertTrue(runCatching { h.repo.deleteSubagentPreset("w1", role.id, 1L) }.exceptionOrNull() is SubagentPresetConflictException)
            assertTrue(runCatching { h.repo.reorderSubagentPresets("w1", listOf(role.id), mapOf(role.id to 1L)) }
                .exceptionOrNull() is SubagentPresetConflictException)
            assertEquals(before, h.dao.getById("w1"))
            assertEquals(epoch, h.gate.currentEpoch("w1"))
            h.repo.deleteSubagentPreset("w1", role.id, latest.version)
            assertTrue(storedPresets(h).isEmpty())
            assertEquals(1L, h.gate.currentToolEpoch("w1", BuiltinToolIds.SUBAGENT))
            Unit
        }
    }

    @Test
    fun `simultaneous same preset edits allow exactly one expected version winner`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val role = h.repo.createSubagentPreset("w1", preset())
            val results = coroutineScope {
                listOf("First editor", "Second editor").map { name ->
                    async(Dispatchers.Default) {
                        runCatching { h.repo.updateSubagentPreset("w1", role.copy(name = name), role.version) }
                    }
                }.awaitAll()
            }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is SubagentPresetConflictException })
            assertEquals(results.first { it.isSuccess }.getOrThrow(), storedPresets(h).single())
            assertEquals(2L, storedPresets(h).single().version)
            assertEquals(2L, h.gate.currentEpoch("w1"))
            Unit
        }
    }

    @Test
    fun `delete preserves unrelated presets and new create appends after remaining orders`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val a = h.repo.createSubagentPreset("w1", preset("alpha"))
            val b = h.repo.createSubagentPreset("w1", preset("beta"))
            h.repo.deleteSubagentPreset("w1", a.id, a.version)
            assertEquals(listOf(b), storedPresets(h))
            val c = h.repo.createSubagentPreset("w1", preset("gamma"))
            assertEquals(2, c.sortOrder)
            assertEquals(listOf(b, c), storedPresets(h))
            Unit
        }
    }

    @Test
    fun `preset reorder validates exact membership and increments only changed sort positions`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val a = h.repo.createSubagentPreset("w1", preset("alpha"))
            val b = h.repo.createSubagentPreset("w1", preset("beta"))
            val c = h.repo.createSubagentPreset("w1", preset("gamma"))
            val versions = mapOf(a.id to a.version, b.id to b.version, c.id to c.version)
            val before = h.dao.getById("w1")
            assertTrue(runCatching { h.repo.reorderSubagentPresets("w1", listOf(a.id, b.id), versions) }
                .exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { h.repo.reorderSubagentPresets("w1", listOf(a.id, a.id, c.id), versions) }
                .exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { h.repo.reorderSubagentPresets("w1", listOf(a.id, b.id, c.id), versions - c.id) }
                .exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { h.repo.reorderSubagentPresets("w1", listOf(a.id, b.id, c.id), versions + ("extra" to 1L)) }
                .exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { h.repo.reorderSubagentPresets("w1", listOf(a.id, b.id, "missing"), versions) }
                .exceptionOrNull() is NoSuchElementException)
            assertEquals(before, h.dao.getById("w1"))
            val reordered = h.repo.reorderSubagentPresets("w1", listOf(c.id, b.id, a.id), versions)
            assertEquals(listOf(c.id, b.id, a.id), reordered.map { it.id })
            assertEquals(listOf(0, 1, 2), reordered.map { it.sortOrder })
            assertEquals(listOf(2L, 1L, 2L), reordered.map { it.version })
            assertTrue(reordered.all { it.executionRevision == 1L })
            val noOp = h.repo.reorderSubagentPresets("w1", reordered.map { it.id }, reordered.associate { it.id to it.version })
            assertEquals(reordered, noOp)
            assertEquals(0L, h.gate.currentToolEpoch("w1", BuiltinToolIds.SUBAGENT))
            Unit
        }
    }

    @Test
    fun `invalid duplicate and over limit presets cannot be persisted`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val first = h.repo.createSubagentPreset("w1", preset())
            val before = h.dao.getById("w1")
            assertTrue(runCatching { h.repo.createSubagentPreset("w1", preset()) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { h.repo.createSubagentPreset("w1", preset("default")) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { h.repo.updateSubagentPreset("w1", first.copy(maxSteps = 0), 1L) }
                .exceptionOrNull() is IllegalArgumentException)
            assertEquals(before, h.dao.getById("w1"))
            for (index in 1 until WorkspaceSubagentPresets.MAX_PRESETS) {
                h.repo.createSubagentPreset("w1", preset("worker$index"))
            }
            val full = h.dao.getById("w1")
            assertTrue(runCatching { h.repo.createSubagentPreset("w1", preset("overflow")) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(full, h.dao.getById("w1"))
            Unit
        }
    }

    @Test
    fun `concurrent edits on separate presets preserve metadata and every role`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val a = h.repo.createSubagentPreset("w1", preset("alpha"))
            val b = h.repo.createSubagentPreset("w1", preset("beta"))
            coroutineScope {
                awaitAll(
                    async(Dispatchers.Default) { h.repo.updateSubagentPreset("w1", a.copy(name = "Alpha renamed"), a.version) },
                    async(Dispatchers.Default) { h.repo.updateSubagentPreset("w1", b.copy(name = "Beta renamed"), b.version) },
                    async(Dispatchers.Default) { h.repo.createSubagentPreset("w1", preset("gamma")) },
                    async(Dispatchers.Default) { h.repo.rename("w1", "Workspace renamed") },
                    async(Dispatchers.Default) { h.repo.setToolApproval("w1", BuiltinToolIds.BASH, true) },
                )
            }
            val roles = storedPresets(h)
            assertEquals(setOf("alpha", "beta", "gamma"), roles.map { it.key }.toSet())
            assertEquals("Alpha renamed", roles.first { it.id == a.id }.name)
            assertEquals("Beta renamed", roles.first { it.id == b.id }.name)
            assertEquals("Workspace renamed", h.dao.getById("w1")!!.name)
            assertEquals(mapOf(BuiltinToolIds.BASH to true), h.dao.getById("w1")!!.toolApprovalOverrides())
            Unit
        }
    }

    @Test
    fun `corrupt catalog survives unrelated patches rejects CRUD and requires explicit repair`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1").copy(subagentPresets = "{broken-presets"))
            h.repo.rename("w1", "Renamed")
            h.repo.setToolApproval("w1", BuiltinToolIds.BASH, true)
            h.repo.setShellCompatibilityMode("w1", true)
            assertEquals("{broken-presets", h.dao.getById("w1")!!.subagentPresets)
            val before = h.dao.getById("w1")
            assertTrue(runCatching { h.repo.createSubagentPreset("w1", preset()) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { h.repo.updateSubagentPreset("w1", preset(), 1L) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { h.repo.deleteSubagentPreset("w1", "missing", 1L) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { h.repo.reorderSubagentPresets("w1", emptyList(), emptyMap()) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { h.repo.applyConfigPatch("w1") { it.copy(subagentPresets = "{different-corruption") } }
                .exceptionOrNull() is IllegalArgumentException)
            assertEquals(before, h.dao.getById("w1"))
            h.repo.resetSubagentPresets("w1")
            assertEquals("[]", h.dao.getById("w1")!!.subagentPresets)
            // Structural terminal-setting publication already revoked every family once; repair adds one.
            assertEquals(2L, h.gate.currentToolEpoch("w1", BuiltinToolIds.SUBAGENT))
            val healthyEmpty = h.dao.getById("w1")
            assertTrue(runCatching { h.repo.resetSubagentPresets("w1") }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(healthyEmpty, h.dao.getById("w1"))
            h.repo.createSubagentPreset("w1", preset())
            val healthyNonempty = h.dao.getById("w1")
            assertTrue(runCatching { h.repo.resetSubagentPresets("w1") }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(healthyNonempty, h.dao.getById("w1"))
            Unit
        }
    }

    @Test
    fun `all preset APIs throw missing workspace and missing preset exceptions`() = runBlocking {
        newHarness().use { h ->
            assertTrue(runCatching { h.repo.createSubagentPreset("missing", preset()) }.exceptionOrNull() is NoSuchElementException)
            assertTrue(runCatching { h.repo.updateSubagentPreset("missing", preset(), 1L) }.exceptionOrNull() is NoSuchElementException)
            assertTrue(runCatching { h.repo.deleteSubagentPreset("missing", "missing", 1L) }.exceptionOrNull() is NoSuchElementException)
            assertTrue(runCatching { h.repo.reorderSubagentPresets("missing", emptyList(), emptyMap()) }.exceptionOrNull() is NoSuchElementException)
            assertTrue(runCatching { h.repo.resetSubagentPresets("missing") }.exceptionOrNull() is NoSuchElementException)
            h.dao.insert(newWorkspace("w1"))
            assertTrue(runCatching { h.repo.deleteSubagentPreset("w1", "missing", 1L) }.exceptionOrNull() is NoSuchElementException)
            assertEquals(0L, h.gate.currentEpoch("missing"))
            Unit
        }
    }

    @Test
    fun `preset execution edit invalidates request policy but does not interrupt active tools`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val role = h.repo.createSubagentPreset("w1", preset())
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val running = async(Dispatchers.Default) {
                toolOp(h.gate, "w1", BuiltinToolIds.SUBAGENT) { started.complete(Unit); release.await() }
            }
            started.await()
            h.repo.updateSubagentPreset("w1", role.copy(systemPrompt = "Changed instructions"), role.version)
            assertEquals(1, h.gate.snapshot("w1").activeOperations)
            assertEquals(2L, h.gate.currentEpoch("w1"))
            assertEquals(0L, h.gate.currentToolEpoch("w1", BuiltinToolIds.SUBAGENT))
            release.complete(Unit)
            withTimeout(3_000) { running.await() }
            Unit
        }
    }

    @Test
    fun `disable delete reduced context workspace tightening and repair cancel only subagent family`() = runBlocking {
        for (action in listOf("disable", "delete", "context", "workspace-context", "repair")) {
            newHarness().use { h ->
                h.dao.insert(newWorkspace("w1").copy(subagentAllowContext = true))
                val role = h.repo.createSubagentPreset("w1", preset())
                if (action == "repair") h.dao.updateSubagentPresetsJson("w1", "{broken-presets", 2L)
                val subagentStarted = CompletableDeferred<Unit>()
                val shellStarted = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val victim = async(Dispatchers.Default) {
                    runCatching { toolOp(h.gate, "w1", BuiltinToolIds.SUBAGENT) { subagentStarted.complete(Unit); release.await() } }
                }
                val unaffected = async(Dispatchers.Default) {
                    toolOp(h.gate, "w1", BuiltinToolIds.WORKSPACE_SHELL) { shellStarted.complete(Unit); release.await() }
                }
                subagentStarted.await(); shellStarted.await()
                withTimeout(3_000) {
                    when (action) {
                        "disable" -> h.repo.updateSubagentPreset("w1", role.copy(enabled = false), role.version)
                        "delete" -> h.repo.deleteSubagentPreset("w1", role.id, role.version)
                        "context" -> h.repo.updateSubagentPreset("w1", role.copy(acceptContext = false), role.version)
                        "workspace-context" -> h.repo.setSubagentAllowContext("w1", false)
                        else -> h.repo.resetSubagentPresets("w1")
                    }
                }
                assertTrue("$action must cancel subagent", victim.await().exceptionOrNull() is WorkspacePolicyCancellationException)
                assertEquals(1, h.gate.snapshot("w1").activeOperations)
                assertEquals(1L, h.gate.currentToolEpoch("w1", BuiltinToolIds.SUBAGENT))
                assertEquals(0L, h.gate.currentToolEpoch("w1", BuiltinToolIds.WORKSPACE_SHELL))
                release.complete(Unit)
                withTimeout(3_000) { unaffected.await() }
                Unit
            }
        }
    }

    // --------------------------------------------------------- 其他

    @Test
    fun `structural change cancels every op in workspace`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val shellStarted = CompletableDeferred<Unit>()
            val editStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val shell = async(Dispatchers.Default) {
                runCatching {
                    toolOp(h.gate, "w1", BuiltinToolIds.WORKSPACE_SHELL) {
                        shellStarted.complete(Unit); release.await()
                    }
                }
            }
            val edit = async(Dispatchers.Default) {
                runCatching {
                    toolOp(h.gate, "w1", BuiltinToolIds.WORKSPACE_EDIT_FILE) {
                        editStarted.complete(Unit); release.await()
                    }
                }
            }
            shellStarted.await(); editStarted.await()

            val publish = async { h.repo.setShellCompatibilityMode("w1", true) }
            delay(50)
            release.complete(Unit)
            publish.await()

            assertTrue(shell.await().exceptionOrNull() is WorkspacePolicyCancellationException)
            assertTrue(edit.await().exceptionOrNull() is WorkspacePolicyCancellationException)
            assertTrue(h.dao.getById("w1")!!.shellCompatibilityMode)
            // 结构级双 bump；所有已知 stableId 吊销
            assertEquals(1L, h.gate.currentEpoch("w1"))
            assertEquals(1L, h.gate.currentViewEpoch("w1"))
            assertNull(h.gate.eventsFlow("w1").value!!.affectedStableIds)
            Unit
        }
    }

    @Test
    fun `patch on missing workspace returns null without gate side effects`() = runBlocking {
        newHarness().use { h ->
            assertNull(h.repo.update("ghost") { it.copy(name = "x") })
            assertFalse(h.repo.setToolEnablement("ghost", BuiltinToolIds.BASH, enabled = false))
            assertEquals(0L, h.gate.currentEpoch("ghost"))
            assertNull(h.gate.eventsFlow("ghost").value)
            Unit
        }
    }

    // --------------------------------------------------------- 新建默认值与首条消息文件列表

    @Test
    fun `new workspaces start with the subagent tool off and other tools on`() = runBlocking {
        newHarness().use { h ->
            for (minimal in listOf(false, true)) {
                val created = h.repo.create("new-$minimal", harnessMinimal = minimal)
                val enablement = WorkspaceToolEnablement.parse(h.dao.getById(created.id)!!.toolEnablement)
                assertEquals(mapOf(BuiltinToolIds.SUBAGENT to false), enablement)
                WorkspaceToolEnablement.idsFor(minimal).filter { it != BuiltinToolIds.SUBAGENT }
                    .forEach { assertTrue(it, enablement[it] ?: true) }
                assertFalse(created.firstMessageFileSnapshot)
            }
        }
    }

    @Test
    fun `first message file snapshot lists one level of the working directory only when enabled and ready`() = runBlocking {
        newHarness().use { h ->
            h.dao.insert(newWorkspace("w1"))
            val files = File(h.baseDir, "w1/files").apply { mkdirs() }
            File(files, "notes.md").writeText("hi")
            File(files, "src/deep").mkdirs()
            File(files, "src/main.kt").writeText("x")

            assertNull("off by default", h.repo.fileSnapshotForFirstMessage("w1", null))
            h.repo.applyConfigPatch("w1") { it.copy(firstMessageFileSnapshot = true) }
            val root = h.repo.fileSnapshotForFirstMessage("w1", null)!!
            assertTrue(root, root.startsWith("<workspace_files path=\"/workspace\">"))
            assertTrue(root, root.contains("\nnotes.md  ") && root.contains("\nsrc/\n"))
            assertFalse("one level only", root.contains("main.kt"))

            val sub = h.repo.fileSnapshotForFirstMessage("w1", "/workspace/src")!!
            assertTrue(sub, sub.contains("path=\"/workspace/src\"") && sub.contains("\ndeep/\n") && sub.contains("\nmain.kt  "))
            assertNull("bad cwd", h.repo.fileSnapshotForFirstMessage("w1", "/etc"))

            h.dao.insert(newWorkspace("w2", shellStatus = WorkspaceShellStatus.DISABLED.name).copy(firstMessageFileSnapshot = true))
            assertNull("not ready", h.repo.fileSnapshotForFirstMessage("w2", null))
            Unit
        }
    }
}
