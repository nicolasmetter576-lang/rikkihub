package me.rerere.rikkihub.data.db.dao

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolEnablement
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 针对 WorkspaceDAO 定向 UPDATE / 事务 merge 的离线测试.
 *
 * [FakeWorkspaceDAO] 用内存 map 模拟 `workspaces` 表:
 * - `insert` 复现 INSERT ... ABORT (重复 id 直接抛错, 不允许 REPLACE 整行);
 * - 每条原语(单行 UPDATE/SELECT)用 `statement` 锁串行, 对应 SQLite 单语句原子性;
 * - `updateInTransaction` / `mergeToolApprovals` 是接口默认方法, 真实实现在
 *   Room 事务里执行, fake 侧用外层 `transaction` 锁包住 `super` 默认实现来
 *   模拟同一保证; 默认方法内部的 getById/updateXxx 仍走 `statement` 锁,
 *   因此如果事务包裹丢失, 并发读-改-写竞态会被断言抓住.
 *
 * 并发用例跑在 [Dispatchers.Default] 上制造真实交错, 不是单线程假并发.
 */
class WorkspaceDaoUpdateTest {

    private class FakeWorkspaceDAO : WorkspaceDAO {
        private val rows = LinkedHashMap<String, WorkspaceEntity>()
        private val flow = MutableStateFlow<List<WorkspaceEntity>>(emptyList())

        /** 单语句原子性 */
        private val statement = Mutex()

        /** 事务串行化: 只包住接口默认事务方法, 模拟 Room @Transaction */
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
            // 穿插让出, 让并发协程在锁外排队, 模拟事务串行化
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

    private fun newWorkspace(id: String, name: String = id) = WorkspaceEntity(
        id = id,
        name = name,
        root = id,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun insertAbortsOnDuplicateId() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1"))
        assertNotNull(dao.getById("w1"))
        var thrown = false
        try {
            dao.insert(newWorkspace("w1", name = "other"))
        } catch (e: Throwable) {
            thrown = true
        }
        assertTrue("INSERT must abort on duplicate id", thrown)
        // 原行未被替换
        assertEquals("w1", dao.getById("w1")!!.name)
    }

    @Test
    fun directedUpdateOnlyTouchesItsColumn() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1"))
        assertEquals(1, dao.updateName("w1", "renamed", 2L))
        assertEquals(1, dao.updateExposeUpload("w1", false, 3L))
        assertEquals(1, dao.updateSubagentModelId("w1", "model-a", 4L))
        val row = dao.getById("w1")!!
        assertEquals("renamed", row.name)
        assertFalse(row.exposeUpload)
        assertEquals("model-a", row.subagentModelId)
        // 未触碰的列保持默认
        assertTrue(row.exposeSkills)
        assertTrue(row.exposeToolOutputs)
        assertEquals("{}", row.toolApprovals)
        // 不存在的行: 定向 UPDATE 命中 0 行
        assertEquals(0, dao.updateName("missing", "x", 5L))
        assertEquals(0, dao.updateExposeSkills("missing", true, 5L))
    }

    @Test
    fun updateInTransactionReadsLatestRow() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1"))
        dao.mergeToolApprovals("w1", mapOf("workspace_shell" to false), 2L)
        // 调用方早前拿过的快照不含之后的更新, 但 transform 收到的仍是事务内最新行
        val earlySnapshot = dao.getById("w1")!!
        dao.updateName("w1", "new-name", 3L)
        assertEquals("w1", earlySnapshot.name)
        val updated = dao.updateInTransaction("w1") { current ->
            // current 必须包含事务内已提交的 merge 结果和最新 name
            assertEquals("new-name", current.name)
            assertEquals(mapOf("workspace_shell" to false), current.toolApprovalOverrides())
            current.copy(subagentAllowContext = true)
        }
        assertNotNull(updated)
        val row = dao.getById("w1")!!
        assertEquals("new-name", row.name)
        assertTrue(row.subagentAllowContext)
        // 事务内读到的 tool_approvals 是最新 merge 结果, 未被整行写回盖掉
        assertEquals(mapOf("workspace_shell" to false), row.toolApprovalOverrides())
    }

    @Test
    fun updateInTransactionRejectsIdentityFieldChanges() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1"))
        // transform 试图改身份/创建期字段: 整次抛错, 不写回
        for (mutate in listOf<suspend (WorkspaceEntity) -> WorkspaceEntity>(
            { it.copy(id = "other") },
            { it.copy(root = "other-root") },
            { it.copy(createdAt = 999L) },
            { it.copy(harnessMinimal = true) },
        )) {
            var thrown = false
            try {
                dao.updateInTransaction("w1", mutate)
            } catch (e: IllegalArgumentException) {
                thrown = true
            }
            assertTrue("identity field mutation must be rejected", thrown)
        }
        val row = dao.getById("w1")!!
        assertEquals("w1", row.id)
        assertEquals("w1", row.root)
        assertEquals(1L, row.createdAt)
        assertFalse(row.harnessMinimal)
    }

    @Test
    fun updateInTransactionNoDiffOnlyTouchesUpdatedAt() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1"))
        dao.mergeToolApprovals("w1", mapOf("bash" to true), 2L)
        val before = dao.getById("w1")!!
        val updated = dao.updateInTransaction("w1") { it }
        assertNotNull(updated)
        val row = dao.getById("w1")!!
        // 所有列保持原值 (包括 merge 进的 tool_approvals), 只有 updatedAt 变化
        assertEquals(before.name, row.name)
        assertEquals(before.toolApprovals, row.toolApprovals)
        assertEquals(before.exposeUpload, row.exposeUpload)
        assertTrue(row.updatedAt != before.updatedAt)
    }

    @Test
    fun updateInTransactionRejectsCorruptToolEnablementValue() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1"))
        var thrown = false
        try {
            dao.updateInTransaction("w1") { it.copy(toolEnablement = "not-json") }
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
        assertEquals("{}", dao.getById("w1")!!.toolEnablement)
    }

    @Test
    fun updateInTransactionCanSetNullableFieldsToNull() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1").copy(subagentModelId = "m1", lastAccessAt = 7L))
        val updated = dao.updateInTransaction("w1") {
            it.copy(subagentModelId = null, lastAccessAt = null)
        }
        assertNotNull(updated)
        val row = dao.getById("w1")!!
        // presence flag 保证「显式置空」不会被当成「不变」丢弃
        assertNull(row.subagentModelId)
        assertNull(row.lastAccessAt)
    }

    @Test
    fun concurrentDifferentColumnUpdatesDoNotLoseEachOther() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1"))
        coroutineScope {
            awaitAll(
                async(Dispatchers.Default) { dao.updateName("w1", "name-x", 10L) },
                async(Dispatchers.Default) { dao.updateExposeUpload("w1", false, 11L) },
                async(Dispatchers.Default) { dao.updateExposeSkills("w1", false, 12L) },
                async(Dispatchers.Default) { dao.updateExposeToolOutputs("w1", false, 13L) },
                async(Dispatchers.Default) { dao.updateSubagentModelId("w1", "m", 14L) },
                async(Dispatchers.Default) { dao.updateSubagentAllowContext("w1", true, 15L) },
                async(Dispatchers.Default) { dao.setShellCompatibilityMode("w1", true, 16L) },
                async(Dispatchers.Default) { dao.updateShellStatus("w1", "READY", 17L) },
            )
        }
        val row = dao.getById("w1")!!
        assertEquals("name-x", row.name)
        assertFalse(row.exposeUpload)
        assertFalse(row.exposeSkills)
        assertFalse(row.exposeToolOutputs)
        assertEquals("m", row.subagentModelId)
        assertTrue(row.subagentAllowContext)
        assertTrue(row.shellCompatibilityMode)
        assertEquals("READY", row.shellStatus)
    }

    @Test
    fun concurrentMergeToolApprovalsKeepsEveryKey() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1"))
        val keys = listOf(
            "workspace_read_file", "workspace_write_file", "workspace_edit_file",
            "workspace_shell", "bash", "str_replace_editor", "subagent",
        )
        coroutineScope {
            keys.map { key ->
                async(Dispatchers.Default) { dao.mergeToolApprovals("w1", mapOf(key to true), 10L) }
            }.awaitAll()
        }
        // 打乱顺序再刷一轮, 每键最终应唯一且一个都不丢
        coroutineScope {
            keys.shuffled(Random(42)).mapIndexed { i, key ->
                async(Dispatchers.Default) {
                    dao.mergeToolApprovals("w1", mapOf(key to (i % 2 == 0)), 20L + i)
                }
            }.awaitAll()
        }
        val overrides = dao.getById("w1")!!.toolApprovalOverrides()
        assertEquals(keys.toSet(), overrides.keys)
    }

    @Test
    fun concurrentTransactionUpdateAndMergeKeepBothChanges() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1"))
        coroutineScope {
            awaitAll(
                async(Dispatchers.Default) {
                    dao.updateInTransaction("w1") { it.copy(name = "tx-name", exposeUpload = false) }
                },
                async(Dispatchers.Default) { dao.mergeToolApprovals("w1", mapOf("subagent" to false), 10L) },
                async(Dispatchers.Default) { dao.mergeToolApprovals("w1", mapOf("bash" to true), 11L) },
            )
        }
        val row = dao.getById("w1")!!
        assertEquals("tx-name", row.name)
        assertFalse(row.exposeUpload)
        assertEquals(
            mapOf("subagent" to false, "bash" to true),
            row.toolApprovalOverrides(),
        )
    }

    @Test
    fun concurrentMergeToolEnablementKeepsEveryKey() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1"))
        val keys = listOf(
            "workspace_read_file", "workspace_write_file", "workspace_edit_file",
            "workspace_shell", "bash", "str_replace_editor", "subagent",
        )
        coroutineScope {
            keys.shuffled(Random(7)).map { key ->
                async(Dispatchers.Default) { dao.mergeToolEnablement("w1", mapOf(key to false), 10L) }
            }.awaitAll()
        }
        val entity = dao.getById("w1")!!
        val overrides = WorkspaceToolEnablement.parse(entity.toolEnablement)
        assertEquals(keys.toSet(), overrides.keys)
        assertTrue(overrides.values.none { it })
        // 启用配置写不触碰审批配置
        assertEquals("{}", entity.toolApprovals)
    }

    @Test
    fun mergeToolEnablementFailsClosedOnCorruptStoredJson() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1"))
        dao.updateToolEnablementJson("w1", "not-json", 2L)
        var thrown = false
        try {
            dao.mergeToolEnablement("w1", mapOf("bash" to false), 3L)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("corrupt tool_enablement must reject merge", thrown)
        // 事务回滚语义: 坏数据原样保留, 不被部分覆盖
        assertEquals("not-json", dao.getById("w1")!!.toolEnablement)
        // 修复路径: 全量替换为合法 JSON 后可再增量 merge
        dao.updateToolEnablementJson("w1", "{\"bash\":false}", 4L)
        assertTrue(dao.mergeToolEnablement("w1", mapOf("subagent" to false), 5L))
        assertEquals(
            mapOf("bash" to false, "subagent" to false),
            WorkspaceToolEnablement.parse(dao.getById("w1")!!.toolEnablement),
        )
    }

    @Test
    fun mergeToolEnablementRejectsUnknownKey() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1"))
        var thrown = false
        try {
            dao.mergeToolEnablement("w1", mapOf("search_web" to false), 2L)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
        assertEquals("{}", dao.getById("w1")!!.toolEnablement)
    }

    @Test
    fun presetColumnDiffIsTargetedAndPreservesOtherConfiguration() = runBlocking {
        val dao = FakeWorkspaceDAO()
        val original = newWorkspace("w1").copy(toolEnablement = "{\"bash\":false}", toolApprovals = "{\"bash\":true}")
        dao.insert(original)
        val json = WorkspaceSubagentPresets.encode(listOf(WorkspaceSubagentPreset(
            key = "worker", name = "Worker", description = "Implement changes", systemPrompt = "Work carefully",
        )))
        val patch = WorkspaceConfigPatch.diff(original, original.copy(subagentPresets = json))
        assertTrue(patch.hasChanges)
        assertEquals(json, patch.subagentPresets)
        assertNull(patch.toolEnablement)
        val saved = dao.updateInTransaction("w1") { it.copy(subagentPresets = json) }!!
        assertEquals(original.copy(subagentPresets = json, updatedAt = saved.updatedAt), saved)
        assertEquals(saved, dao.getById("w1"))
        assertEquals(0, dao.updateSubagentPresetsJson("missing", json, 2L))
    }

    @Test
    fun changedPresetJsonIsStrictlyValidatedBeforeAnyColumnIsWritten() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1"))
        for (json in listOf("not-json", "{}", "[{}]")) {
            val before = dao.getById("w1")
            val error = runCatching {
                dao.updateInTransaction("w1") { it.copy(name = "must-not-write", subagentPresets = json) }
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals(before, dao.getById("w1"))
        }
    }

    @Test
    fun unchangedCorruptPresetsRemainByteIdenticalOnUnrelatedUpdates() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1").copy(subagentPresets = "{broken-presets"))
        val before = dao.getById("w1")!!
        assertNull(WorkspaceConfigPatch.diff(before, before.copy(name = "renamed")).subagentPresets)
        dao.updateInTransaction("w1") { it.copy(name = "renamed", subagentAllowContext = true) }
        assertEquals("{broken-presets", dao.getById("w1")!!.subagentPresets)
        assertEquals("renamed", dao.getById("w1")!!.name)
    }

    @Test
    fun concurrentPresetAndApprovalTransactionUpdatesPreserveBothColumns() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1"))
        val json = WorkspaceSubagentPresets.encode(listOf(WorkspaceSubagentPreset(
            key = "worker", name = "Worker", description = "Implement changes", systemPrompt = "Work carefully",
        )))
        coroutineScope {
            awaitAll(
                async(Dispatchers.Default) { dao.updateInTransaction("w1") { it.copy(subagentPresets = json) } },
                async(Dispatchers.Default) { dao.mergeToolApprovals("w1", mapOf("bash" to true), 3L) },
                async(Dispatchers.Default) { dao.updateInTransaction("w1") { it.copy(name = "new-name") } },
            )
        }
        val saved = dao.getById("w1")!!
        assertEquals(json, saved.subagentPresets)
        assertEquals(mapOf("bash" to true), saved.toolApprovalOverrides())
        assertEquals("new-name", saved.name)
    }

    @Test
    fun missingWorkspaceReturnsNullOrFalse() = runBlocking {
        val dao = FakeWorkspaceDAO()
        assertNull(dao.updateInTransaction("missing") { it })
        assertFalse(dao.mergeToolApprovals("missing", mapOf("bash" to true), 1L))
        assertFalse(dao.mergeToolEnablement("missing", mapOf("bash" to true), 1L))
        assertEquals(0, dao.deleteById("missing"))
    }

    @Test
    fun otherWorkspaceUnaffected() = runBlocking {
        val dao = FakeWorkspaceDAO()
        dao.insert(newWorkspace("w1"))
        dao.insert(newWorkspace("w2", name = "second"))
        dao.mergeToolApprovals("w1", mapOf("workspace_shell" to false), 5L)
        dao.updateName("w1", "first-renamed", 6L)
        val other = dao.getById("w2")!!
        assertEquals("second", other.name)
        assertEquals("{}", other.toolApprovals)
        assertTrue(other.exposeUpload)
    }
}
