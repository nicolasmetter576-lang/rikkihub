package me.rerere.rikkihub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.ai.tools.SubagentPresetConflictException
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolEnablement
import me.rerere.rikkihub.data.ai.tools.WorkspaceToolDefaultApprovals
import me.rerere.rikkihub.data.ai.tools.toolEnabled
import me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate
import me.rerere.rikkihub.data.workspace.WorkspaceOperationKind
import me.rerere.rikkihub.data.workspace.WorkspaceHandleClass
import me.rerere.rikkihub.data.workspace.readBoundedAgentsText
import me.rerere.rikkihub.data.workspace.formatWorkspaceFileTree
import me.rerere.rikkihub.data.workspace.WorkspaceChangeSpec
import me.rerere.rikkihub.data.workspace.WorkspaceLegacySessionInvalidation
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceDirectoryService
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.dao.WorkspaceDAO
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.WorkspaceArchiveResult
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceDeleteResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceImportNode
import me.rerere.workspace.WorkspaceImportResult
import me.rerere.workspace.WorkspaceBackend
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceMountSelection
import me.rerere.workspace.WorkspaceDeviceMountSpec
import me.rerere.workspace.WorkspacePathInfo
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceTransferMode
import me.rerere.workspace.WorkspaceTransferProgress
import me.rerere.workspace.WorkspaceTransferResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceBackend,
    private val settingsStore: SettingsStore,
    val runtimeGate: WorkspaceRuntimeGate = WorkspaceRuntimeGate(),
    val sessionInvalidation: WorkspaceLegacySessionInvalidation = WorkspaceLegacySessionInvalidation(),
    private val deviceDirectoryService: WorkspaceDeviceDirectoryService? = null,
) {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    fun getByIdFlow(id: String): Flow<WorkspaceEntity?> =
        dao.listFlow().map { workspaces -> workspaces.firstOrNull { it.id == id } }.distinctUntilChanged()

    suspend fun list(): List<WorkspaceEntity> = dao.getAll()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            if (!manager.workspaceExists(workspace.root)) {
                // 目录缺失时不删除记录(例如恢复备份后工作区文件未随数据库一起恢复),
                // 仅标记为 BROKEN 以保留记录与助手绑定, 避免误删用户工作区
                Log.w(TAG, "Workspace directory missing, marking as broken: id=${workspace.id}, root=${workspace.root}")
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    /** 所有 App/Web 配置写入都在同一发布通道：先封准入并等待受影响操作，再事务写列差分。 */
    suspend fun update(
        id: String,
        transform: suspend (WorkspaceEntity) -> WorkspaceEntity,
    ): WorkspaceEntity? = applyConfigPatch(id, transform)

    /** [transform] must be side-effect-free: preview for cancellation, then merge again inside Room transaction. */
    suspend fun applyConfigPatch(
        id: String,
        transform: suspend (WorkspaceEntity) -> WorkspaceEntity,
    ): WorkspaceEntity? {
        var baseline: WorkspaceEntity? = null
        var prepared: WorkspaceEntity? = null
        var preparedSpec: WorkspaceChangeSpec? = null
        return runtimeGate.publishPrepared(
            workspaceId = id,
            prepare = {
                val current = dao.getById(id)
                baseline = current
                val next = current?.let { transform(it) }
                prepared = next
                if (current == null || next == null) null else {
                    require(next.id == current.id && next.root == current.root &&
                        next.createdAt == current.createdAt && next.harnessMinimal == current.harnessMinimal
                    ) { "Workspace identity and creation-time mode cannot be changed" }
                    WorkspaceToolEnablement.parse(next.toolEnablement)
                    if (next.subagentPresets != current.subagentPresets) {
                        WorkspaceSubagentPresets.parse(next.subagentPresets)
                        validateLinkedPresetOwnership(current, next)
                    }
                    workspaceConfigChangeSpec(current, next).also { spec ->
                        preparedSpec = spec
                    }
                }
            },
            publish = {
                val next = prepared
                preparedSpec?.let { spec ->
                    // Structural changes close existing terminals; a tool toggle only closes
                    // model resources explicitly registered for that stable ID (never user terminals).
                    baseline?.let {
                        sessionInvalidation.closeWorkspace(it.id, it.root, spec.affectedStableIds)
                    }
                }
                if (next == null) null else dao.updateInTransaction(id) { latest ->
                    // Fail rather than publish a stale entity if a caller bypassed this repository.
                    check(latest == baseline) { "Workspace changed outside the configuration publication gate" }
                    // JSON maps are read/merged inside the Room transaction, not written from a UI entity.
                    val merged = transform(latest)
                    check(merged == next) { "Workspace configuration transform must be deterministic" }
                    merged
                }
            },
        )
    }

    /** Trusted library projector only: sorted gates, complete preparation, then one caller-owned Room transaction. */
    internal suspend fun <T> publishPresetLibraryBatch(
        workspaceIds: Collection<String>,
        prepare: suspend () -> Map<String, Pair<WorkspaceEntity, WorkspaceEntity>>,
        publish: suspend (Map<String, Pair<WorkspaceEntity, WorkspaceEntity>>) -> T,
    ): T {
        val ids = workspaceIds.toSet()
        var plans: Map<String, Pair<WorkspaceEntity, WorkspaceEntity>> = emptyMap()
        return runtimeGate.publishPreparedMany(ids, prepare = {
            plans = prepare().toMap()
            require(plans.keys == ids) { "Publication must include exactly its prepared workspaces" }
            plans.mapValues { (id, pair) ->
                val (current, next) = pair
                require(current.id == id && next.id == current.id && next.root == current.root &&
                    next.createdAt == current.createdAt && next.harnessMinimal == current.harnessMinimal
                ) { "Workspace identity cannot be changed" }
                if (next.toolEnablement != current.toolEnablement) WorkspaceToolEnablement.parse(next.toolEnablement)
                if (next.subagentPresets != current.subagentPresets) WorkspaceSubagentPresets.parse(next.subagentPresets)
                workspaceConfigChangeSpec(current, next)
            }
        }, publish = {
            plans.values.forEach { (current, next) ->
                workspaceConfigChangeSpec(current, next)?.let { spec ->
                    sessionInvalidation.closeWorkspace(current.id, current.root, spec.affectedStableIds)
                }
            }
            publish(plans)
        })
    }

    /** Must be invoked inside the library's outer Room transaction; no whole-row UPDATE/REPLACE. */
    internal suspend fun writePreparedPresetLibraryBatch(plans: Map<String, Pair<WorkspaceEntity, WorkspaceEntity>>) {
        for ((id, pair) in plans.toSortedMap()) {
            val (baseline, next) = pair
            if (next == baseline) {
                if (dao.getById(id) != baseline) throw SubagentPresetConflictException("Workspace changed concurrently")
                continue
            }
            val saved = dao.updateInTransaction(id) { latest ->
                if (latest != baseline) throw SubagentPresetConflictException("Workspace changed concurrently")
                next
            }
            if (saved == null) throw NoSuchElementException("Workspace not found: $id")
        }
    }

    /** Local editors may reorder shared snapshots but cannot edit/remove/forge their provenance. */
    private fun validateLinkedPresetOwnership(current: WorkspaceEntity, next: WorkspaceEntity) {
        val before = WorkspaceSubagentPresets.tryParse(current.subagentPresets)
        val after = WorkspaceSubagentPresets.parse(next.subagentPresets)
        if (before == null) {
            require(after.none { it.libraryPresetId != null }) { "Corrupt workspace repair cannot create shared links" }
            return
        }
        val oldById = before.associateBy { it.id }
        for (preset in after.filter { it.libraryPresetId != null }) {
            val old = oldById[preset.id]
                ?: throw SubagentPresetConflictException("Enable shared presets through the preset library")
            if (preset.copy(sortOrder = old.sortOrder, version = old.version) != old || preset.version < old.version) {
                throw SubagentPresetConflictException("Edit shared presets through the preset library")
            }
        }
        val afterById = after.associateBy { it.id }
        for (old in before.filter { it.libraryPresetId != null }) {
            val retained = afterById[old.id]
                ?: throw SubagentPresetConflictException("Disable shared presets through the preset library")
            if (retained.copy(sortOrder = old.sortOrder, version = old.version) != old || retained.version < old.version) {
                throw SubagentPresetConflictException("Edit shared presets through the preset library")
            }
        }
    }

    suspend fun create(name: String, harnessMinimal: Boolean = false): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
            harnessMinimal = harnessMinimal,
            exposeUpload = !harnessMinimal,
            exposeSkills = !harnessMinimal,
            exposeToolOutputs = true,
            // 新工作区默认关闭子代理工具（可在「工具启用」里打开）；已有工作区不受影响
            toolEnablement = WorkspaceToolEnablement.validatedJson(mapOf(BuiltinToolIds.SUBAGENT to false)),
        )
        manager.ensureWorkspace(workspace.root)
        dao.insert(workspace)
        return workspace
    }

    suspend fun rename(id: String, name: String): Boolean = applyConfigPatch(id) { workspace ->
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        workspace.copy(name = finalName)
    } != null

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setExposedMount(id: String, target: String, exposed: Boolean): Boolean {
        if (target !in setOf("/upload", "/skills", "/tool_outputs")) return false
        return applyConfigPatch(id) { workspace ->
            when (target) {
                "/upload" -> workspace.copy(exposeUpload = exposed)
                "/skills" -> workspace.copy(exposeSkills = exposed)
                else -> workspace.copy(exposeToolOutputs = exposed)
            }
        } != null
    }

    suspend fun setSubagentModel(id: String, modelId: String?): Boolean = applyConfigPatch(id) {
        it.copy(subagentModelId = modelId?.trim()?.takeIf(String::isNotEmpty))
    } != null

    suspend fun setSubagentAllowContext(id: String, allowContext: Boolean): Boolean = applyConfigPatch(id) {
        it.copy(subagentAllowContext = allowContext)
    } != null

    /** Allocate identity outside the twice-executed transform; callers cannot choose server IDs/versions. */
    suspend fun createSubagentPreset(
        workspaceId: String,
        preset: WorkspaceSubagentPreset,
        /** Management gateway only: host-allocated ID frozen in an approved proposal. */
        assignedId: String? = null,
    ): WorkspaceSubagentPreset {
        require(preset.libraryPresetId == null) { "Local presets cannot forge a library association" }
        assignedId?.let { require(runCatching { Uuid.parse(it) }.isSuccess) { "Invalid assigned preset id" } }
        val newId = assignedId ?: Uuid.random().toString()
        val normalized = WorkspaceSubagentPresets.normalize(preset).copy(
            id = newId, version = 1, executionRevision = 1,
        )
        val saved = applyConfigPatch(workspaceId) { workspace ->
            val current = WorkspaceSubagentPresets.parse(workspace.subagentPresets)
            if (current.any { it.id == newId }) throw SubagentPresetConflictException("Preset id already exists")
            val sortOrder = current.maxOfOrNull { it.sortOrder }?.let { Math.addExact(it, 1) } ?: 0
            workspace.copy(subagentPresets = WorkspaceSubagentPresets.encode(
                current + normalized.copy(sortOrder = sortOrder),
            ))
        } ?: throw NoSuchElementException("Workspace not found: $workspaceId")
        return WorkspaceSubagentPresets.parse(saved.subagentPresets).first { it.id == newId }
    }

    /** Key/identity are immutable; order and counters remain server-owned (use reorder for order). */
    suspend fun updateSubagentPreset(
        workspaceId: String,
        preset: WorkspaceSubagentPreset,
        expectedVersion: Long,
    ): WorkspaceSubagentPreset {
        val normalized = WorkspaceSubagentPresets.normalize(preset)
        val saved = applyConfigPatch(workspaceId) { workspace ->
            val current = WorkspaceSubagentPresets.parse(workspace.subagentPresets)
            val old = current.firstOrNull { it.id == normalized.id }
                ?: throw NoSuchElementException("Subagent preset not found: ${normalized.id}")
            checkSubagentPresetVersion(old, expectedVersion)
            if (old.libraryPresetId != null) throw SubagentPresetConflictException("Edit shared presets through the preset library")
            require(normalized.id == old.id && normalized.key == old.key) {
                "Subagent preset id and key cannot be changed"
            }
            val updated = normalized.copy(
                sortOrder = old.sortOrder,
                version = Math.addExact(old.version, 1L),
                executionRevision = if (WorkspaceSubagentPresets.sameExecution(old, normalized)) {
                    old.executionRevision
                } else Math.addExact(old.executionRevision, 1L),
            )
            workspace.copy(subagentPresets = WorkspaceSubagentPresets.encode(
                current.map { if (it.id == old.id) updated else it },
            ))
        } ?: throw NoSuchElementException("Workspace not found: $workspaceId")
        return WorkspaceSubagentPresets.parse(saved.subagentPresets).first { it.id == normalized.id }
    }

    suspend fun deleteSubagentPreset(workspaceId: String, presetId: String, expectedVersion: Long) {
        applyConfigPatch(workspaceId) { workspace ->
            val current = WorkspaceSubagentPresets.parse(workspace.subagentPresets)
            val old = current.firstOrNull { it.id == presetId }
                ?: throw NoSuchElementException("Subagent preset not found: $presetId")
            checkSubagentPresetVersion(old, expectedVersion)
            if (old.libraryPresetId != null) throw SubagentPresetConflictException("Disable shared presets through the preset library")
            workspace.copy(subagentPresets = WorkspaceSubagentPresets.encode(
                current.filterNot { it.id == presetId },
            ))
        } ?: throw NoSuchElementException("Workspace not found: $workspaceId")
    }

    /** Exact membership and every expected version are checked before changing any order. */
    suspend fun reorderSubagentPresets(
        workspaceId: String,
        presetIds: List<String>,
        expectedVersions: Map<String, Long>,
    ): List<WorkspaceSubagentPreset> {
        // Snapshot mutable caller collections before preview/transaction execution.
        val ids = presetIds.toList()
        val versions = expectedVersions.toMap()
        val saved = applyConfigPatch(workspaceId) { workspace ->
            val current = WorkspaceSubagentPresets.parse(workspace.subagentPresets)
            val byId = current.associateBy { it.id }
            ids.forEach { id ->
                if (id !in byId) throw NoSuchElementException("Subagent preset not found: $id")
            }
            require(ids.size == byId.size && ids.toSet() == byId.keys) {
                "Preset order must contain the entire exact ID set without duplicates"
            }
            require(versions.keys == byId.keys) { "Expected versions must contain the entire exact ID set" }
            current.forEach { checkSubagentPresetVersion(it, versions.getValue(it.id)) }
            val reordered = ids.mapIndexed { order, id ->
                val old = byId.getValue(id)
                if (old.sortOrder == order) old else old.copy(
                    sortOrder = order, version = Math.addExact(old.version, 1L),
                )
            }
            workspace.copy(subagentPresets = WorkspaceSubagentPresets.encode(reordered))
        } ?: throw NoSuchElementException("Workspace not found: $workspaceId")
        return WorkspaceSubagentPresets.parse(saved.subagentPresets).sortedBy { it.sortOrder }
    }

    /** Explicit recovery only: healthy catalogs must be edited/deleted through version-checked APIs. */
    suspend fun resetSubagentPresets(workspaceId: String) {
        applyConfigPatch(workspaceId) { workspace ->
            require(WorkspaceSubagentPresets.tryParse(workspace.subagentPresets) == null) {
                "Only a corrupt subagent preset collection can be reset"
            }
            workspace.copy(subagentPresets = WorkspaceSubagentPresets.encode(emptyList()))
        } ?: throw NoSuchElementException("Workspace not found: $workspaceId")
    }

    private fun checkSubagentPresetVersion(preset: WorkspaceSubagentPreset, expectedVersion: Long) {
        if (preset.version != expectedVersion) throw SubagentPresetConflictException()
    }

    suspend fun setShellCompatibilityMode(id: String, enabled: Boolean) {
        applyConfigPatch(id) { it.copy(shellCompatibilityMode = enabled) }
    }

    /**
     * 「首条消息附带文件列表」：列会话工作目录 [workspaceCwd]（null = `/workspace`；限定到该目录时也是它）
     * 的一层。开关关、未 READY、目录不存在或读不到时返回 null（不附带）。
     */
    suspend fun fileSnapshotForFirstMessage(id: String, workspaceCwd: String?): String? {
        val workspace = dao.getById(id) ?: return null
        if (!workspace.firstMessageFileSnapshot || workspace.shellStatus != WorkspaceShellStatus.READY.name) return null
        val relative = runCatching { me.rerere.workspace.WorkspaceFilesScope.normalize(workspaceCwd) ?: "" }
            .getOrNull() ?: return null
        val entries = try {
            listFiles(id, WorkspaceStorageArea.FILES, relative)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val guestPath = if (relative.isEmpty()) "/workspace" else "/workspace/$relative"
        return withContext(Dispatchers.IO) {
            formatWorkspaceFileTree(guestPath, workspace.firstMessageFileSnapshotDepth, entries) { dir ->
                runCatching { manager.listFiles(workspace.root, dir.path, WorkspaceStorageArea.FILES) }.getOrNull()
            }
        }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        require(toolName in WorkspaceToolDefaultApprovals) { "Unknown workspace tool id: $toolName" }
        return applyConfigPatch(id) {
            it.copy(toolApprovals = JsonInstant.encodeToString(it.toolApprovalOverrides() + (toolName to needsApproval)))
        } != null
    }

    /** 增量 patch 仅接受稳定 ID；损坏配置拒绝写入，不静默恢复全开。 */
    suspend fun setToolEnablement(id: String, toolId: String, enabled: Boolean): Boolean = applyConfigPatch(id) {
        it.copy(toolEnablement = WorkspaceToolEnablement.mergedJson(it.toolEnablement, mapOf(toolId to enabled)))
    } != null

    /** 用户明确修复/重置才允许全量替换；发布仍经过同一个运行准入 gate。 */
    suspend fun replaceToolEnablement(id: String, overrides: Map<String, Boolean>): Boolean {
        val json = WorkspaceToolEnablement.validatedJson(overrides)
        return applyConfigPatch(id) { it.copy(toolEnablement = json) } != null
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                manager.installRootfs(workspace.root, url, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.writeText(workspace.root, path, text, overwrite)
    }

    /** Manual editor snapshot: resource gate, not model tool/shell enablement. */
    suspend fun readTextSnapshotForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): me.rerere.workspace.WorkspaceTextSnapshot = runtimeGate.withOperation(
        workspaceId = id,
        kind = WorkspaceOperationKind.FILE_OPERATION,
        handleClass = WorkspaceHandleClass.FILE_OPERATION,
        waitIfSealed = true,
        validate = { dao.getById(id) != null },
    ) {
        withContext(Dispatchers.IO) {
            val workspace = requireWorkspace(id)
            manager.readTextSnapshot(workspace.root, path, area)
        }
    }

    /** Existing FILES only; never recreate a deleted target or silently overwrite a stale revision. */
    suspend fun writeTextWithRevision(
        id: String,
        path: String,
        text: String,
        expectedRevision: String,
    ): me.rerere.workspace.WorkspaceTextWriteResult = runtimeGate.withOperation(
        workspaceId = id,
        kind = WorkspaceOperationKind.FILE_OPERATION,
        handleClass = WorkspaceHandleClass.FILE_OPERATION,
        waitIfSealed = true,
        validate = { dao.getById(id) != null },
    ) {
        withContext(Dispatchers.IO) {
            val workspace = requireWorkspace(id)
            manager.writeTextWithRevision(workspace.root, path, text, expectedRevision)
        }
    }

    /** Manual FILES creation: user resource access, never a model tool invocation. */
    suspend fun createFile(
        id: String,
        path: String,
        text: String = "",
        charset: java.nio.charset.Charset = Charsets.UTF_8,
    ): WorkspaceFileEntry = runtimeGate.withOperation(
        workspaceId = id,
        kind = WorkspaceOperationKind.FILE_OPERATION,
        handleClass = WorkspaceHandleClass.FILE_OPERATION,
        waitIfSealed = true,
        validate = { dao.getById(id) != null },
    ) {
        withContext(Dispatchers.IO) {
            val workspace = requireWorkspace(id)
            // Do not manufacture a missing parent or depend on shell/tool enablement.
            require(charset == Charsets.UTF_8) { "Only UTF-8 text files can be created" }
            manager.createFile(workspace.root, path, text)
        }
    }

    /** Exclusive empty directory creation. Unlike legacy mkdir, never creates a parent chain. */
    suspend fun createDirectory(id: String, path: String): WorkspaceFileEntry = runtimeGate.withOperation(
        workspaceId = id,
        kind = WorkspaceOperationKind.FILE_OPERATION,
        handleClass = WorkspaceHandleClass.FILE_OPERATION,
        waitIfSealed = true,
        validate = { dao.getById(id) != null },
    ) {
        withContext(Dispatchers.IO) {
            val workspace = requireWorkspace(id)
            manager.createDirectory(workspace.root, path)
        }
    }

    suspend fun mkdir(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.mkdir(workspace.root, path, area)
    }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceManager.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path)
            WorkspaceStorageArea.LINUX -> {
                val size = manager.fileSize(workspace.root, path, area)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 (${size} bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    manager.exportFile(workspace.root, path, area, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
    }

    /**
     * 整文件夹导入: [treeRoot] 由 UI 侧用 DocumentsContract 适配 OpenDocumentTree
     * 返回的 uri (WorkspaceFolderImporter), 本层只管调度到 IO 线程并交给 manager.
     * 保留根文件夹与全部层级/空目录, 同名根目录整体重命名, 失败时清掉半成品.
     */
    suspend fun importFolder(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        treeRoot: WorkspaceImportNode,
    ): WorkspaceImportResult = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.importFolder(workspace.root, destinationPath, area, treeRoot)
    }

    /**
     * 把选中的若干文件/目录打包成 ZIP 流式写入 [outputStream] (由 workspace 层关闭).
     * 空选择/根目录/越界路径/符号链接都会在 manager 层被拒绝.
     */
    suspend fun exportArchive(
        id: String,
        area: WorkspaceStorageArea,
        paths: List<String>,
        outputStream: OutputStream,
    ): WorkspaceArchiveResult = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.exportArchive(workspace.root, paths, area, outputStream)
    }

    /** 解压 FILES 区里的压缩包 (App 与 Web 共用), 细节见 [me.rerere.workspace.WorkspaceArchiveExtractor]. */
    suspend fun extractArchive(
        id: String,
        path: String,
        password: CharArray? = null,
        nameEncoding: String? = null,
        onProgress: (me.rerere.workspace.WorkspaceExtractProgress) -> Unit = {},
    ): me.rerere.workspace.WorkspaceExtractResult = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.extractArchive(workspace.root, path, WorkspaceStorageArea.FILES, password, nameEncoding, onProgress)
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.fileSize(workspace.root, path, area)
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.exportFile(workspace.root, path, area, outputStream)
    }

    /** Automatic AGENTS IO, not a model tool invocation. Structural changes revoke this read.
     * Uses the existing canonical resolver (not race-proof/no-symlink traversal).
     */
    internal suspend fun readAgentsInstructions(id: String, path: String, filesScope: String? = null): String =
        runtimeGate.withOperation(
            workspaceId = id,
            kind = WorkspaceOperationKind.FILE_OPERATION,
            handleClass = WorkspaceHandleClass.FILE_OPERATION,
            validate = { dao.getById(id)?.shellStatus == WorkspaceShellStatus.READY.name },
        ) {
            withContext(Dispatchers.IO) {
                val workspace = requireWorkspace(id)
                require(workspace.shellStatus == WorkspaceShellStatus.READY.name) { "Workspace is not ready" }
                val mounts = agentMounts(workspace, filesScope)
                readBoundedAgentsText(
                    manager.rootfsFileSize(workspace.root, path, mounts),
                ) { output -> manager.exportRootfsFile(workspace.root, path, output, mounts) }
            }
        }

    /** 按 Rootfs 内绝对路径读取文件大小, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun rootfsFileSize(
        id: String,
        path: String,
        filesScope: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = requireWorkspace(id)
        manager.rootfsFileSize(workspace.root, path, agentMounts(workspace, filesScope))
    }

    /** 按 Rootfs 内绝对路径导出文件内容, 支持 /workspace、bind mount 与 Rootfs 内部路径 */
    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
        filesScope: String? = null,
    ) = withContext(Dispatchers.IO) {
        val workspace = requireWorkspace(id)
        manager.exportRootfsFile(workspace.root, path, outputStream, agentMounts(workspace, filesScope))
    }

    suspend fun statRootfs(id: String, path: String, filesScope: String? = null): WorkspacePathInfo = withContext(Dispatchers.IO) {
        val workspace = requireWorkspace(id)
        manager.rootfsStat(workspace.root, path, agentMounts(workspace, filesScope))
    }

    /**
     * Host file behind a guest [path], seen exactly as the model sees the workspace (same mounts and
     * [filesScope]). Null when the path is refused, missing or not a regular file.
     */
    suspend fun resolveRootfsFile(id: String, path: String, filesScope: String? = null): File? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext null
        val mounts = try {
            agentMounts(workspace, filesScope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext null
        }
        runCatching { manager.resolveRootfsFile(workspace.root, path, mounts) }
            .getOrNull()
            ?.takeIf { it.isFile }
    }

    suspend fun listRootfs(id: String, path: String, filesScope: String? = null): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = requireWorkspace(id)
        manager.listRootfs(workspace.root, path, agentMounts(workspace, filesScope))
    }

    suspend fun readRootfsText(
        id: String,
        path: String,
        maxBytes: Long,
        filesScope: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val workspace = requireWorkspace(id)
        val mounts = agentMounts(workspace, filesScope)
        val size = manager.rootfsFileSize(workspace.root, path, mounts)
        require(size <= maxBytes) {
            "File is too large to read: $path (${size / 1024 / 1024}MB, max ${maxBytes / 1024 / 1024}MB)."
        }
        ByteArrayOutputStream(size.toInt()).use { out ->
            manager.exportRootfsFile(workspace.root, path, out, mounts)
            out.toString(Charsets.UTF_8.name())
        }
    }

    suspend fun writeRootfsText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
        filesScope: String? = null,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = requireWorkspace(id)
        manager.writeRootfsText(
            root = workspace.root,
            path = path,
            text = text,
            overwrite = overwrite,
            mounts = agentMounts(workspace, filesScope),
        )
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            manager.deleteFile(workspace.root, path, recursive, area)
        }
        return deleted
    }

    /** 多选批量删除; [paths] 为发起时的快照, 语义见 WorkspaceManager.deleteFiles */
    suspend fun deleteFiles(
        id: String,
        area: WorkspaceStorageArea,
        paths: List<String>,
    ): WorkspaceDeleteResult = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.deleteFiles(workspace.root, paths, area)
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    /**
     * 「移动到 / 复制到」：把 [sourceId] FILES 区的 [paths] 放进 [targetId]（可与源相同）FILES 区的 [targetDir]，
     * 保留原名、重名自动改名，逐项结果见 [me.rerere.workspace.WorkspaceFileTransfer]。
     */
    suspend fun transferFiles(
        sourceId: String,
        paths: List<String>,
        targetId: String,
        targetDir: String,
        mode: WorkspaceTransferMode,
        onProgress: (WorkspaceTransferProgress) -> Unit = {},
    ): WorkspaceTransferResult = withContext(Dispatchers.IO) {
        val source = dao.getById(sourceId) ?: throw WorkspaceTransferWorkspaceNotFoundException(sourceId)
        val target = if (targetId == sourceId) source else dao.getById(targetId)
            ?: throw WorkspaceTransferWorkspaceNotFoundException(targetId)
        manager.ensureWorkspace(source.root)
        manager.ensureWorkspace(target.root)
        manager.transferFiles(source.root, paths, target.root, targetDir, mode, onProgress)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        filesScope: String? = null,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        val mounts = agentMounts(workspace, filesScope)
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.executeCommand(
                root = workspace.root,
                command = command,
                cwd = cwd,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                mounts = mounts,
                shellCompatibilityMode = workspace.shellCompatibilityMode,
            )
        }
    }

    suspend fun delete(id: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        runtimeGate.closeWorkspace(id)
        sessionInvalidation.closeWorkspace(workspace.id, workspace.root)
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            manager.deleteWorkspace(workspace.root)
        }
        cleanupAssistantReferences(id)
        return true
    }

    private suspend fun requireWorkspace(id: String): WorkspaceEntity {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        return workspace
    }

    /**
     * Exposed mount targets + enabled device directories (unavailable ones become read-only placeholders).
     * Confined to [filesScope]: only that FILES directory plus `/tool_outputs` (if exposed); no other
     * global mount and no device directory.
     */
    private suspend fun agentMounts(workspace: WorkspaceEntity, filesScope: String? = null): WorkspaceMountSelection {
        if (filesScope != null) {
            return WorkspaceMountSelection(
                exposedTargets = workspace.exposedMountTargets() intersect SCOPED_MOUNT_TARGETS,
                filesScope = filesScope,
            )
        }
        val devices = deviceDirectoryService?.resolveMountSet(workspace.id)
        return WorkspaceMountSelection(
            exposedTargets = workspace.exposedMountTargets(),
            deviceMounts = devices?.available.orEmpty().map {
                WorkspaceDeviceMountSpec(it.id, it.alias, it.source.absolutePath)
            },
            unavailableDeviceAliases = devices?.unavailableAliases.orEmpty(),
        )
    }

    /** Mount selection used for commands and shells of [workspaceId]. */
    suspend fun executionMounts(workspaceId: String, filesScope: String? = null): WorkspaceMountSelection {
        val workspace = dao.getById(workspaceId) ?: error("Workspace not found: $workspaceId")
        return agentMounts(workspace, filesScope)
    }

    /** Opens an interactive bash inside the workspace rootfs ([filesScope]: model shells only). */
    suspend fun openShell(
        workspaceId: String,
        request: me.rerere.workspace.WorkspaceShellRequest,
        filesScope: String? = null,
    ): me.rerere.workspace.WorkspacePty =
        withContext(Dispatchers.IO) {
            val workspace = requireWorkspace(workspaceId)
            val mounts = agentMounts(workspace, filesScope)
            manager.openShell(workspace.root, request.copy(mounts = mounts,
                shellCompatibilityMode = workspace.shellCompatibilityMode))
        }

    /**
     * Shell and distribution of a READY workspace's rootfs for the system prompt; null otherwise.
     * Two small host-side file reads, no PRoot.
     */
    fun rootfsEnvironment(workspace: WorkspaceEntity): me.rerere.workspace.RootfsEnvironment? =
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) null
        else runCatching { manager.rootfsEnvironment(workspace.root) }.getOrNull()

    /** Rootfs presence for the workspace. */
    suspend fun rootfsReady(workspaceId: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(workspaceId) ?: return@withContext false
        manager.hasRootfs(workspace.root)
    }

    /** Explicit per-workspace device list for bounded completion; never scans unconnected storage. */
    suspend fun deviceMounts(workspaceId: String): List<me.rerere.workspace.WorkspaceDeviceMount> =
        deviceDirectoryService?.resolveMounts(workspaceId).orEmpty()

    /** Materialize these alongside tool schemas under the caller's admission snapshot. */
    suspend fun executionMountTargets(workspaceId: String, filesScope: String? = null): Set<String> =
        executionMounts(workspaceId, filesScope).let { mounts ->
            WorkspaceManager.OPTIONAL_AGENT_MOUNT_TARGETS.filter { target ->
                when (target) {
                    "/skills", "/builtin_skills" -> "/skills" in mounts.exposedTargets
                    else -> target in mounts.exposedTargets
                }
            }.toCollection(linkedSetOf()) + mounts.deviceMounts.map {
                "${me.rerere.workspace.WorkspaceDeviceDirectoryGuard.DEVICE_GUEST_ROOT}/${it.alias}"
            }
        }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        applyConfigPatch(workspaceId) { it.copy(shellStatus = shellStatus) }
    }

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024

        /** Global mounts still visible when confined to a working directory. */
        val SCOPED_MOUNT_TARGETS = setOf(me.rerere.rikkihub.data.workspace.WorkspaceRequestCapabilities.TOOL_OUTPUTS_GUEST_PATH)
    }
}

/** 移动/复制的源或目标工作区不存在（已被删除）。 */
class WorkspaceTransferWorkspaceNotFoundException(val workspaceId: String) :
    IllegalArgumentException("Workspace not found: $workspaceId")
