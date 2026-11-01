package me.rerere.rikkihub.data.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.rerere.rikkihub.data.db.dao.WorkspaceDeviceMountDAO
import me.rerere.rikkihub.data.db.dao.WorkspaceDAO
import me.rerere.rikkihub.data.db.entity.WorkspaceDeviceMountEntity
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [WorkspaceDeviceDirectoryService] 的纯 JVM 测试（fake DAO / fake storage）。覆盖契约：
 * alias 重复/嵌套/限额、跨工作区可共用 source、卷根可连接、busy 拒绝、
 * enabled 即生效、失效连接单独标出不连坐、授权撤销后仍可 list/delete/disable、cancel 透传。
 *
 * fake primary root 用临时目录并预置 `Documents/` 等普通目录（与生产一致：
 * 测试所选 source 是真实存在的目录，不是凭空路径）。
 */
class WorkspaceDeviceDirectoryServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var primaryRoot: File
    private lateinit var storage: FakeDeviceStorage
    private lateinit var mountDao: FakeDeviceMountDAO
    private lateinit var workspaceDao: FakeWorkspaceDAO
    private lateinit var gate: WorkspaceRuntimeGate
    private lateinit var invalidation: WorkspaceLegacySessionInvalidation
    private lateinit var service: WorkspaceDeviceDirectoryService

    @Before
    fun setUp() {
        primaryRoot = tempFolder.newFolder("primary")
        primaryRoot.resolve("Documents").mkdir()
        primaryRoot.resolve("Download").mkdir()
        primaryRoot.resolve("DCIM/Camera").mkdirs()
        primaryRoot.resolve("Android/data").mkdirs()

        storage = FakeDeviceStorage(primaryRoot)
        mountDao = FakeDeviceMountDAO()
        workspaceDao = FakeWorkspaceDAO()
        gate = WorkspaceRuntimeGate(joinTimeoutMillis = 2_000L)
        invalidation = WorkspaceLegacySessionInvalidation()
        service = WorkspaceDeviceDirectoryService(
            storage = storage,
            mountDao = mountDao,
            workspaceDao = workspaceDao,
            gate = gate,
            sessionInvalidation = invalidation,
        )
    }

    private suspend fun seedWorkspace(id: String = "w1") {
        workspaceDao.insert(
            WorkspaceEntity(id = id, name = id, root = id, createdAt = 1L, updatedAt = 1L)
        )
    }

    // ---------------------------------------------------------------- create

    @Test
    fun stalePickerOrEditorDoesNotWriteIntoRemappedSource() = runBlocking {
        seedWorkspace()
        val mount = service.create("w1", "docs", "Documents")
        val oldView = service.viewToken("w1")
        service.update("w1", mount.id, sourceRelativePath = "Download")
        var ran = false
        try {
            service.withMountOperation("w1", mount.id, expectedViewToken = oldView) { ran = true }
            error("must reject old view")
        } catch (_: WorkspaceUnavailableException) {
            assertFalse(ran)
        }
        service.withMountOperation("w1", mount.id, expectedViewToken = service.viewToken("w1")) {
            assertEquals(primaryRoot.resolve("Download").canonicalFile, it.source)
        }
    }

    @Test
    fun processRestartCannotReuseAnOldViewTokenEvenIfEpochCollides() = runBlocking {
        seedWorkspace()
        val mount = service.create("w1", "docs", "Documents")
        val oldView = service.viewToken("w1")
        val freshGate = WorkspaceRuntimeGate()
        freshGate.publishPrepared("w1", prepare = { WorkspaceChangeSpec.structural() }, publish = {})
        assertEquals(gate.currentViewEpoch("w1"), freshGate.currentViewEpoch("w1"))
        val freshService = WorkspaceDeviceDirectoryService(storage, mountDao, workspaceDao, freshGate, invalidation)
        var ran = false
        try {
            freshService.withMountOperation("w1", mount.id, expectedViewToken = oldView) { ran = true }
            error("old process token must be rejected")
        } catch (_: WorkspaceUnavailableException) {
            assertFalse(ran)
        }
    }

    @Test
    fun createValidMountIsImmediatelyAvailable() = runBlocking {
        seedWorkspace()
        val entity = service.create("w1", "docs", "Documents")
        assertEquals("docs", entity.alias)
        assertEquals("Documents", entity.sourceRelativePath)
        val resolved = service.resolveMounts("w1")
        assertEquals(1, resolved.size)
        assertEquals(entity.id, resolved[0].id)
        assertTrue(resolved[0].source.isDirectory)
    }

    @Test
    fun createAllowsWholeSharedStorageRootButNothingElseBesideIt() = runBlocking {
        seedWorkspace()
        val root = service.create("w1", "sdcard", "")
        assertEquals("", root.sourceRelativePath)
        assertEquals(primaryRoot.canonicalFile, service.resolveMounts("w1").single().source)
        try {
            service.create("w1", "docs", "Documents") // 与卷根嵌套
            fail("nested under the root mount must abort")
        } catch (expected: IllegalArgumentException) {
        }
    }
    @Test
    fun createRejectsDuplicateAliasSameWorkspace() = runBlocking {
        seedWorkspace()
        service.create("w1", "docs", "Documents")
        try {
            service.create("w1", "docs", "Download")
            fail("duplicate alias must abort")
        } catch (expected: IllegalArgumentException) {
        }
        assertEquals(1, mountDao.listByWorkspace("w1").size)
    }

    @Test
    fun createRejectsNestedOrDuplicateSource() = runBlocking {
        seedWorkspace()
        service.create("w1", "dcim", "DCIM")
        try {
            service.create("w1", "cam", "DCIM/Camera") // 嵌套
            fail("nested source must abort")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            service.create("w1", "dcim2", "DCIM") // 重复
            fail("duplicate source must abort")
        } catch (expected: IllegalArgumentException) {
        }
        assertEquals(1, mountDao.listByWorkspace("w1").size)
    }

    @Test
    fun createRejectsBeyondQuota() = runBlocking {
        seedWorkspace()
        repeat(8) { i ->
            val dir = primaryRoot.resolve("d$i").apply { mkdir() }
            service.create("w1", "m$i", dir.name)
        }
        val extra = primaryRoot.resolve("extra").apply { mkdir() }
        try {
            service.create("w1", "extra", extra.name)
            fail("quota (8) must abort")
        } catch (expected: IllegalArgumentException) {
        }
        assertEquals(8, mountDao.listByWorkspace("w1").size)
    }

    @Test
    fun createAllowsCrossWorkspaceSharedSource() = runBlocking {
        seedWorkspace("w1"); seedWorkspace("w2")
        service.create("w1", "docs", "Documents")
        service.create("w2", "docs", "Documents")
        assertEquals(1, mountDao.listByWorkspace("w2").size)
        assertEquals(1, service.resolveMounts("w2").size)
    }
    @Test
    fun createRejectsForbiddenSource() = runBlocking {
        seedWorkspace()
        // alias 保持合法，只让 source 决定失败
        listOf("..", "/", "Android", "Android/data", "missing-dir")
            .forEachIndexed { i, path ->
                try {
                    service.create("w1", "bad$i", path)
                    fail("forbidden source must abort: '$path'")
                } catch (expected: Exception) {
                }
            }
        assertEquals(0, mountDao.listByWorkspace("w1").size)
    }

    // ---------------------------------------------------------------- update

    @Test
    fun updateToggleEnabledStopsAndResumesResolving() = runBlocking {
        seedWorkspace()
        val e = service.create("w1", "docs", "Documents")
        service.update("w1", e.id, enabled = false)
        assertTrue(service.resolveMounts("w1").isEmpty())
        service.update("w1", e.id, enabled = true)
        assertEquals(e.id, service.resolveMounts("w1").single().id)
    }
    @Test
    fun updateSourceChangeTakesEffectImmediately() = runBlocking {
        seedWorkspace()
        val e = service.create("w1", "docs", "Documents")
        service.update("w1", e.id, sourceRelativePath = "Download")
        val resolved = service.resolveMounts("w1")
        assertEquals(1, resolved.size)
        assertTrue(resolved[0].source.path.endsWith("Download"))
    }
    @Test
    fun updateAliasChangeTakesEffectImmediately() = runBlocking {
        seedWorkspace()
        val e = service.create("w1", "docs", "Documents")
        service.update("w1", e.id, alias = "papers")
        assertEquals("papers", mountDao.getById(e.id)!!.alias)
        assertEquals("papers", service.resolveMounts("w1").single().alias)
    }
    @Test
    fun updateValidatesAliasAgainstLatestSet() = runBlocking {
        seedWorkspace()
        val a = service.create("w1", "a1", "Documents")
        service.create("w1", "a2", "Download")
        try {
            service.update("w1", a.id, alias = "a2")
            fail("alias collision must abort")
        } catch (expected: IllegalArgumentException) {
        }
        assertEquals("a1", mountDao.getById(a.id)!!.alias)
    }

    @Test
    fun updateRejectsMissingMount() = runBlocking {
        seedWorkspace()
        try {
            service.update("w1", "nope", alias = "x")
            fail("missing mount must abort")
        } catch (expected: WorkspaceDeviceConfigException) {
        }
    }

    @Test
    fun updateNoOpDoesNotPublish() = runBlocking {
        seedWorkspace()
        val e = service.create("w1", "docs", "Documents")
        val beforeEpoch = gate.currentViewEpoch("w1")
        service.update("w1", e.id) // 全 null → no-op
        assertEquals(beforeEpoch, gate.currentViewEpoch("w1"))
    }
    // ---------------------------------------------------------------- resolveMounts

    @Test
    fun resolveMountsEmptyWhenNothingConfigured() = runBlocking {
        seedWorkspace()
        assertTrue(service.resolveMounts("w1").isEmpty())
    }

    @Test
    fun revokedPermissionMarksEnabledMountsUnavailableWithoutThrowing() = runBlocking {
        seedWorkspace()
        service.create("w1", "docs", "Documents")
        storage.availability = WorkspaceDeviceAvailability.PERMISSION_REQUIRED
        val set = service.resolveMountSet("w1")
        assertTrue(set.available.isEmpty())
        assertEquals(listOf("docs"), set.unavailableAliases)
    }
    @Test
    fun removedSourceOnlyAffectsItsOwnMount() = runBlocking {
        seedWorkspace()
        service.create("w1", "docs", "Documents")
        service.create("w1", "dl", "Download")
        primaryRoot.resolve("Documents").deleteRecursively()
        val set = service.resolveMountSet("w1")
        assertEquals(listOf("dl"), set.available.map { it.alias })
        assertEquals(listOf("docs"), set.unavailableAliases)
    }
    @Test
    fun resolveMountsPropagatesCancellation() = runBlocking {
        seedWorkspace()
        service.create("w1", "docs", "Documents")
        val job = async {
            service.resolveMounts("w1")
        }
        job.cancel()
        try {
            job.await()
            fail("cancelled resolveMounts must propagate CE")
        } catch (expected: CancellationException) {
        }
    }

    // ---------------------------------------------------------------- 授权撤销后仍可做配置管理

    @Test
    fun deleteAndDisableStillWorkAfterPermissionRevoked() = runBlocking {
        seedWorkspace()
        val e = service.create("w1", "docs", "Documents")
        storage.availability = WorkspaceDeviceAvailability.PERMISSION_REQUIRED
        // disable：不需要授权
        val disabled = service.update("w1", e.id, enabled = false)
        assertFalse(disabled!!.enabled)
        // 再启用需要授权 → 拒绝（在 fake storage 仍是 revoked 下）
        try {
            service.update("w1", e.id, enabled = true)
            fail("re-enable must require availability")
        } catch (expected: WorkspaceDeviceConfigException) {
        }
        // 删除也不需要授权
        assertTrue(service.delete("w1", e.id))
    }

    @Test
    fun browseRejectsWhenPermissionRevoked() = runBlocking {
        seedWorkspace()
        storage.availability = WorkspaceDeviceAvailability.PERMISSION_REQUIRED
        try {
            service.browse("Documents")
            fail("browse requires availability")
        } catch (expected: WorkspaceDeviceConfigException) {
        }
    }

    // ---------------------------------------------------------------- browse

    @Test
    fun browseRootListsNonForbiddenDirs() = runBlocking {
        seedWorkspace()
        val entries = service.browse("")
        val names = entries.map { it.name }
        assertTrue("Documents" in names)
        assertTrue("Download" in names)
        // Android 子树被过滤
        assertFalse(names.any { it.equals("Android", ignoreCase = true) })
    }

    @Test
    fun browseRejectsSymlinkEntry() = runBlocking {
        seedWorkspace()
        val target = tempFolder.newFolder("outside-target")
        val link = File(primaryRoot, "linked")
        java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        val entries = service.browse("")
        assertFalse(entries.any { it.name == "linked" })
    }

    @Test
    fun browseTooManyEntriesFails() = runBlocking {
        seedWorkspace()
        repeat(1001) { primaryRoot.resolve("d$it").mkdir() }
        try {
            service.browse("")
            fail(">1000 entries must error, not truncate")
        } catch (expected: WorkspaceDeviceConfigException) {
        }
    }

    // ---------------------------------------------------------------- busy 拒绝

    @Test
    fun structuralChangeRejectedWhileGenerationActive() = runBlocking {
        seedWorkspace()
        service.create("w1", "docs", "Documents")
        val release = CompletableDeferred<Unit>()
        val op = async {
            gate.withOperation(
                workspaceId = "w1",
                kind = WorkspaceOperationKind.GENERATION,
                handleClass = WorkspaceHandleClass.MODEL_EXECUTION,
            ) { _ -> release.await() }
        }
        yield() // 让 op 完成准入登记
        try {
            service.create("w1", "dl", "Download")
            fail("busy generation must reject config change")
        } catch (expected: WorkspaceDeviceBusyException) {
        }
        release.complete(Unit)
        op.await()
        // 结束后可变更
        service.create("w1", "dl", "Download")
        assertEquals(2, mountDao.listByWorkspace("w1").size)
    }

    @Test
    fun fileOperationDoesNotBlockConfigChange() = runBlocking {
        seedWorkspace()
        val e = service.create("w1", "docs", "Documents")
        val started = CompletableDeferred<Unit>()
        val op = async {
            service.withMountOperation("w1", e.id) {
                started.complete(Unit)
                try {
                    kotlinx.coroutines.delay(60_000L)
                } finally {
                    it
                }
                it
            }
        }
        started.await() // FILE_OPERATION 已在册
        // 结构变更不被 busy 拒绝（FILE_OPERATION 不计入 busy 类）
        service.create("w1", "dl", "Download")
        // 但它仍是结构级 spec(null) 的 victim：被取消 → policy CE 上抛
        try {
            op.await()
            fail("file op must be cancelled as structural victim")
        } catch (expected: WorkspacePolicyCancellationException) {
        }
        assertEquals(2, mountDao.listByWorkspace("w1").size)
    }

    // ---------------------------------------------------------------- withMountOperation

    @Test
    fun withMountOperationRunsBlockWithResolvedMount() = runBlocking {
        seedWorkspace()
        val e = service.create("w1", "docs", "Documents")
        val mount = service.withMountOperation("w1", e.id) { it }
        assertEquals(e.id, mount.id)
        assertEquals("docs", mount.alias)
        assertTrue(mount.source.isDirectory)
    }

    @Test
    fun withMountOperationRejectsMountWhoseSourceIsGone() = runBlocking {
        seedWorkspace()
        val e = service.create("w1", "docs", "Documents")
        primaryRoot.resolve("Documents").deleteRecursively()
        try {
            withTimeout(2_000L) {
                service.withMountOperation("w1", e.id) { it }
            }
            fail("unavailable mount must reject the operation")
        } catch (expected: WorkspaceUnavailableException) {
        } catch (expected: WorkspaceDeviceConfigException) {
        }
    }
    @Test
    fun withMountOperationRejectsDisabledMount() = runBlocking {
        seedWorkspace()
        val e = service.create("w1", "docs", "Documents")
        service.update("w1", e.id, enabled = false)
        try {
            withTimeout(2_000L) {
                service.withMountOperation("w1", e.id) { it }
            }
            fail("disabled mount must reject")
        } catch (expected: WorkspaceUnavailableException) {
        } catch (expected: WorkspaceDeviceConfigException) {
        }
    }

    // ---------------------------------------------------------------- snapshotFingerprint

    @Test
    fun snapshotFingerprintChangesOnStructure() = runBlocking {
        seedWorkspace()
        val f0 = service.snapshotFingerprint("w1")
        val e = service.create("w1", "docs", "Documents")
        val f1 = service.snapshotFingerprint("w1")
        assertTrue(f0 != f1)
        service.update("w1", e.id, enabled = false)
        val f2 = service.snapshotFingerprint("w1")
        assertTrue(f1 != f2)
        storage.availability = WorkspaceDeviceAvailability.PERMISSION_REQUIRED
        assertTrue(f2 != service.snapshotFingerprint("w1")) // 授权翻转也进指纹
    }

    // ================================================================ fakes

    private class FakeDeviceStorage(private val root: File) : WorkspaceDeviceStorage {
        @Volatile var availability = WorkspaceDeviceAvailability.READY
        override fun availability(): WorkspaceDeviceAvailability = availability
        override fun primaryRoot(): File =
            if (availability == WorkspaceDeviceAvailability.READY) root
            else throw WorkspaceDeviceStorageException("primary root unavailable")
    }

    private class FakeDeviceMountDAO : WorkspaceDeviceMountDAO {
        private val rows = LinkedHashMap<String, WorkspaceDeviceMountEntity>()
        private val flow = MutableStateFlow<List<WorkspaceDeviceMountEntity>>(emptyList())

        private fun publishFlow() {
            flow.value = rows.values.sortedBy { it.alias }
        }

        override fun listFlowByWorkspace(workspaceId: String): Flow<List<WorkspaceDeviceMountEntity>> =
            MutableStateFlow(rows.values.filter { it.workspaceId == workspaceId }.sortedBy { it.alias })

        override suspend fun listByWorkspace(workspaceId: String): List<WorkspaceDeviceMountEntity> =
            rows.values.filter { it.workspaceId == workspaceId }.sortedBy { it.alias }

        override suspend fun listAll(): List<WorkspaceDeviceMountEntity> =
            rows.values.sortedBy { it.workspaceId + it.alias }

        override suspend fun getById(id: String): WorkspaceDeviceMountEntity? = rows[id]

        override suspend fun insert(mount: WorkspaceDeviceMountEntity) {
            check(mount.id !in rows) { "duplicate id" }
            check(rows.values.none { it.workspaceId == mount.workspaceId && it.alias == mount.alias }) {
                "duplicate alias"
            }
            rows[mount.id] = mount
            publishFlow()
        }

        override suspend fun updateAlias(id: String, alias: String, updatedAt: Long): Int {
            val r = rows[id] ?: return 0
            rows[id] = r.copy(alias = alias, updatedAt = updatedAt)
            publishFlow(); return 1
        }

        override suspend fun updateSource(id: String, sourceRelativePath: String, updatedAt: Long): Int {
            val r = rows[id] ?: return 0
            rows[id] = r.copy(sourceRelativePath = sourceRelativePath, updatedAt = updatedAt)
            publishFlow(); return 1
        }

        override suspend fun updateEnabled(id: String, enabled: Boolean, updatedAt: Long): Int {
            val r = rows[id] ?: return 0
            rows[id] = r.copy(enabled = enabled, updatedAt = updatedAt)
            publishFlow(); return 1
        }

        override suspend fun updateColumns(
            id: String, alias: String, sourceRelativePath: String,
            enabled: Boolean, updatedAt: Long,
        ): Int {
            val r = rows[id] ?: return 0
            rows[id] = r.copy(
                alias = alias, sourceRelativePath = sourceRelativePath,
                enabled = enabled, updatedAt = updatedAt,
            )
            publishFlow(); return 1
        }

        override suspend fun deleteById(id: String): Int {
            val removed = rows.remove(id) != null
            if (removed) publishFlow()
            return if (removed) 1 else 0
        }

        // 默认方法 updateMountsInTransaction 由接口提供，走上面的原语
    }

    private class FakeWorkspaceDAO : WorkspaceDAO {
        private val rows = LinkedHashMap<String, WorkspaceEntity>()
        private val flow = MutableStateFlow<List<WorkspaceEntity>>(emptyList())

        override fun listFlow(): Flow<List<WorkspaceEntity>> = flow
        override suspend fun getById(id: String): WorkspaceEntity? = rows[id]
        override suspend fun insert(workspace: WorkspaceEntity) {
            rows[workspace.id] = workspace; flow.value = rows.values.toList()
        }
        override suspend fun getAll(): List<WorkspaceEntity> = rows.values.toList()
        override suspend fun updateName(id: String, name: String, updatedAt: Long) =
            (rows[id]?.let { rows[id] = it.copy(name = name, updatedAt = updatedAt); 1 }) ?: 0
        override suspend fun updateSubagentModelId(id: String, modelId: String?, updatedAt: Long) = 0
        override suspend fun updateSubagentAllowContext(id: String, allowContext: Boolean, updatedAt: Long) = 0
        override suspend fun updateExposeUpload(id: String, exposed: Boolean, updatedAt: Long) = 0
        override suspend fun updateExposeSkills(id: String, exposed: Boolean, updatedAt: Long) = 0
        override suspend fun updateExposeToolOutputs(id: String, exposed: Boolean, updatedAt: Long) = 0
        override suspend fun updateToolApprovalsJson(id: String, json: String, updatedAt: Long) = 0
        override suspend fun updateToolEnablementJson(id: String, json: String, updatedAt: Long) = 0
        override suspend fun updateSubagentPresetsJson(id: String, json: String, updatedAt: Long) = 0
        override suspend fun updateLastAccessAt(id: String, lastAccessAt: Long?, updatedAt: Long) = 0
        override suspend fun touchUpdatedAt(id: String, updatedAt: Long) = 0
        override suspend fun updateShellStatus(id: String, shellStatus: String, updatedAt: Long) = 0
        override suspend fun setShellCompatibilityMode(id: String, enabled: Boolean, updatedAt: Long) = 0
        override suspend fun setFirstMessageFileSnapshot(id: String, enabled: Boolean, updatedAt: Long) = 0
        override suspend fun setFirstMessageFileSnapshotDepth(id: String, depth: Int, updatedAt: Long) = 0
        override suspend fun deleteById(id: String): Int = if (rows.remove(id) != null) 1 else 0
        // updateInTransaction / mergeToolApprovals / mergeToolEnablement 用接口默认实现
    }
}
