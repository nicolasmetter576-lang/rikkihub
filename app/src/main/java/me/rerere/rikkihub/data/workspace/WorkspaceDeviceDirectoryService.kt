package me.rerere.rikkihub.data.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkihub.data.db.dao.WorkspaceDAO
import me.rerere.rikkihub.data.db.dao.WorkspaceDeviceMountDAO
import me.rerere.rikkihub.data.db.dao.WorkspaceDeviceMountPatch
import me.rerere.rikkihub.data.db.entity.WorkspaceDeviceMountEntity
import me.rerere.workspace.WorkspaceDeviceDirectoryGuard
import me.rerere.workspace.WorkspaceDeviceMount
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

/**
 * 工作区设备目录直连的领域服务（直连写，无 upper/COW/commit/discard）。
 *
 * 语义契约（针对实现必须保住的点）：
 * - 只管理「连接配置」（[WorkspaceDeviceMountEntity]）；enabled 即生效（2.7.0 起无激活记录）；
 *   绝不删除/修改宿主 source 目录本体；普通写盘不推进 view epoch。
 * - source 只接受**主共享存储卷下的相对路径**（空串 = 卷根）：经
 *   `WorkspaceDeviceDirectoryGuard` 逐组件校验解析为 canonical host 目录后
 *   才参与 mount；不持久化任意宿主绝对路径，不支持可插拔外置卷。
 * - **resolve 不连坐**：[resolveMountSet] 把当前可用的连接与失效的连接（授权缺失、
 *   目录被删）分开返回；失效连接由运行时挂成只读占位目录，不让整个 shell 不可用，
 *   也不让 `/device/<alias>` 回落 rootfs。
 * - **busy 拒绝（不 cancel）**：结构变更（create/update/delete + 换 source/
 *   alias/启用）在该工作区存在 GENERATION/TOOL/CHILD/PENDING_APPROVAL 在册
 *   操作时直接抛 [WorkspaceDeviceBusyException]；只有 FILE_OPERATION/
 *   HANDLE_CREATION（用户手动文件/终端资源类）不阻塞配置提交。两层检查：
 *   prepare 前快速读 `gate.snapshot` + `publishPrepared` 的 admissionMutex
 *   内 `validateBusy` 复查（与准入互斥，无 TOCTOU）。gate 依然对
 *   受管 handle 做 close+await（用户终端资源类）。
 * - `withMountOperation`：文件 IO 操作走 `gate.withOperation(kind=
 *   FILE_OPERATION, handleClass=FILE_OPERATION)` 登记——不参与「结构变更
 *   busy 拒绝」的判定（单文件操作不阻塞连接编辑），但会随结构级 spec 作为
 *   victim 被取消；不拿全局 configMutex（IO 不进配置临界区）。
 * - API26~29：`availability()==UNSUPPORTED_API` 时
 *   `requireAvailability()` 的写路径（create/source 变更/显式 activate/
 *   browse）拒绝；`list`/`delete`/纯 `enabled=false` 关闭仍允许（关闭不依赖
 *   授权事实）。`/workspace` 等既有功能不受影响。
 *
 * 依赖刻意只含 DAO（不依赖 WorkspaceRepository，避免仓库↔服务的 DI 环）。
 */
class WorkspaceDeviceDirectoryService(
    private val storage: WorkspaceDeviceStorage,
    private val mountDao: WorkspaceDeviceMountDAO,
    private val workspaceDao: WorkspaceDAO,
    private val gate: WorkspaceRuntimeGate,
    private val sessionInvalidation: WorkspaceLegacySessionInvalidation,
    private val usage: WorkspaceDeviceUsage? = null,
) {
    /**
     * 配置互斥：仅包住「读 mount → 校验 → publishPrepared」这段短临界区。不用于运行时 IO 锁
     * （`withMountOperation` 走 gate 登记，不进本锁）。
     */
    private val configMutex = Mutex()

    // guard 不缓存主卷根：每次按当前 primaryRoot 新建（storage 每次访问重检，
    // 授权/挂载点变化不沿用旧实例）。
    private fun guard(): WorkspaceDeviceDirectoryGuard =
        WorkspaceDeviceDirectoryGuard(storage.primaryRoot())

    // ------------------------------------------------------------ 查询

    suspend fun list(workspaceId: String): List<WorkspaceDeviceMountEntity> =
        mountDao.listByWorkspace(workspaceId)

    fun listFlow(workspaceId: String): Flow<List<WorkspaceDeviceMountEntity>> =
        mountDao.listFlowByWorkspace(workspaceId)

    /**
     * 受控目录浏览：列出主卷根下 [relativePath] 的直接子目录（供 App/Web 选择器；
     * 不提供任意宿主路径浏览，相对路径同样过 guard 校验）。
     *
     * [relativePath] 空串 = 卷根第一层：列出可见子目录供继续下钻，但
     * `Android/`（大小写不敏感）与符号链接条目不展示（防外跳/forbidden child）。
     * 非空路径经 [resolveRelative] canonical 校验；目录不可读/条目超过
     * [BROWSE_MAX_ENTRIES] 一律抛错，不静默截断。
     */
    suspend fun browse(relativePath: String): List<WorkspaceDeviceDirectoryEntry> {
        requireAvailability()
        val root = storage.primaryRoot().canonicalFile
        val dir = if (relativePath.isEmpty()) root else resolveRelative(relativePath)
        val entries = dir.listFiles()
            ?: throw WorkspaceDeviceConfigException("Directory is not readable")
        if (entries.size > BROWSE_MAX_ENTRIES) {
            throw WorkspaceDeviceConfigException(
                "Too many entries (${entries.size} > $BROWSE_MAX_ENTRIES)"
            )
        }
        return entries
            .asSequence()
            .filter { it.isDirectory }
            // 卷根层防 Android 子树与符号链接外跳；canonical 与组件式检查互补
            .filterNot { child ->
                child.name.equals(WorkspaceDeviceDirectoryGuard.ANDROID_DIR_NAME, ignoreCase = true) ||
                    runCatching {
                        java.nio.file.Files.isSymbolicLink(child.toPath())
                    }.getOrDefault(true) ||
                    child.canonicalFile.path != child.absoluteFile.normalize().path
            }
            .sortedBy { it.name.lowercase() }
            .map { child ->
                WorkspaceDeviceDirectoryEntry(
                    name = child.name,
                    relativePath = root.toPath().relativize(child.toPath()).toString()
                        .replace(File.separatorChar, '/'),
                )
            }
            .toList()
    }

    /**
     * 文件/手动 IO 操作入口：以 [WorkspaceOperationKind.FILE_OPERATION] +
     * [WorkspaceHandleClass.FILE_OPERATION] 登记——不参与「结构变更 busy
     * 拒绝」的 busy 计数（不与 GENERATION/TOOL/CHILD 同级），但结构级
     * publish 会把它当 victim 取消（files 操作不能挡住连接编辑是计划要求）。
     * 不持 configMutex：IO 时长不串行化配置提交。
     */
    fun viewToken(workspaceId: String): String = "${gate.activationSession}:${gate.currentViewEpoch(workspaceId)}"

    suspend fun <T> withMountOperation(
        workspaceId: String,
        mountId: String,
        expectedViewToken: String? = null,
        block: suspend (WorkspaceDeviceMount) -> T,
    ): T = gate.withOperation(
        workspaceId = workspaceId,
        kind = WorkspaceOperationKind.FILE_OPERATION,
        handleClass = WorkspaceHandleClass.FILE_OPERATION,
        // 无 stableIds：mount 文件 IO 是资源操作不是模型工具；结构 spec(null)
        // 的 affected=null 仍波及它（victim），工具开关 spec 不波及。
        waitIfSealed = true,
        validate = {
            if (expectedViewToken != null && expectedViewToken != viewToken(workspaceId)) {
                throw WorkspaceUnavailableException(
                    workspaceId, WorkspaceUnavailableReason.STALE_VIEW_EPOCH,
                    "Device directory view changed; reopen the directory before accessing files",
                )
            }
            // admissionMutex 内读最新：mount 必须仍存在且当前可挂载；
            // 失效/停用 → VALIDATION_FAILED 拒绝，不 body 执行
            val mount = resolveMountForOperation(workspaceId, mountId)
            mount != null
        },
    ) { _ ->
        val mount = resolveMountForOperation(workspaceId, mountId)
            ?: throw WorkspaceDeviceConfigException("Device mount is not available")
        block(mount)
    }

    // ------------------------------------------------------------ 写操作

    /**
     * 创建连接。同工作区内 source 重复/嵌套拒绝（guard 校验）；跨工作区可共用同一目录。
     */
    suspend fun create(
        workspaceId: String,
        alias: String,
        sourceRelativePath: String,
    ): WorkspaceDeviceMountEntity = configMutex.withLock {
        requireAvailability()
        val entity = newEntity(workspaceId, alias, sourceRelativePath)
        val committed = gate.publishPrepared(
            workspaceId = workspaceId,
            prepare = {
                val ws = workspaceDao.getById(workspaceId)
                    ?: throw WorkspaceDeviceConfigException("Workspace not found")
                rejectIfBusy(workspaceId)
                val current = mountDao.listByWorkspace(workspaceId)
                validateMountSet(current + entity)
                WorkspaceChangeSpec.structural()
            },
            publish = {
                sessionInvalidation.closeWorkspace(workspaceId, wsRoot(workspaceId))
                // 事务内读最新行复校验（限额/alias/嵌套/禁区 + busy 复查），
                // 防 prepare/publish 之间并发写漏进非法状态
                mountDao.updateMountsInTransaction(workspaceId) { latest ->
                    rejectIfBusy(workspaceId)
                    validateMountSet(latest + entity)
                    WorkspaceDeviceMountPatch(insert = entity)
                }
                entity
            },
        )
        committed
    }

    /**
     * 补丁式更新（alias/source/enabled 任意组合；null = 不变）。无变更 → prepare null →
     * metadata 直发（不 bump/cancel/event）。
     */
    suspend fun update(
        workspaceId: String,
        mountId: String,
        alias: String? = null,
        sourceRelativePath: String? = null,
        enabled: Boolean? = null,
    ): WorkspaceDeviceMountEntity? = configMutex.withLock {
        // 授权只在「引入/变更 source 或显式启用」时要求：授权撤销后仍允许
        // 停用/删除/改 alias（fail-closed 不意味着配置都动不了）。
        val touchesSourceOrEnable = sourceRelativePath != null || enabled == true || alias != null
        val disablingOnly = enabled == false && alias == null && sourceRelativePath == null
        if (touchesSourceOrEnable) requireAvailability()
        var committedEntity: WorkspaceDeviceMountEntity? = null
        val result = gate.publishPrepared(
            workspaceId = workspaceId,
            prepare = prepare@{
                val ws = workspaceDao.getById(workspaceId)
                    ?: throw WorkspaceDeviceConfigException("Workspace not found")
                rejectIfBusy(workspaceId)
                val current = mountDao.listByWorkspace(workspaceId)
                val existing = current.firstOrNull { it.id == mountId }
                    ?: throw WorkspaceDeviceConfigException("Device mount not found")
                val patched = existing.copy(
                    alias = alias?.trim() ?: existing.alias,
                    sourceRelativePath = sourceRelativePath ?: existing.sourceRelativePath,
                    enabled = enabled ?: existing.enabled,
                )
                if (patched == existing) return@prepare null
                val next = current.map { if (it.id == mountId) patched else it }
                if (!disablingOnly) validateMountSet(next)
                WorkspaceChangeSpec.structural()
            },
            publish = {
                sessionInvalidation.closeWorkspace(workspaceId, wsRoot(workspaceId))
                // 事务内读最新行再定向写：quota/alias/nooverlap 在最新
                // 快照上复查；业务行已删则 fail-loud（不静默 no-op）
                val written = mountDao.updateMountsInTransaction(workspaceId) { latest ->
                    rejectIfBusy(workspaceId)
                    val target = latest.firstOrNull { it.id == mountId }
                        ?: throw WorkspaceDeviceConfigException("Device mount not found")
                    val next = target.copy(
                        alias = alias?.trim() ?: target.alias,
                        sourceRelativePath = sourceRelativePath ?: target.sourceRelativePath,
                        enabled = enabled ?: target.enabled,
                    )
                    if (!disablingOnly) validateMountSet(latest.map { if (it.id == mountId) next else it })
                    WorkspaceDeviceMountPatch(updates = listOf(next))
                }
                committedEntity = written.firstOrNull { it.id == mountId }
                committedEntity
            },
        )
        result
    }

    /**
     * 只删连接配置：绝不删除/修改宿主 source。授权撤销后仍允许（清理配置
     * 不需要授权）。dirty=「正在使用」由 busy 拒绝保证；删除同样先关旧资源。
     */
    suspend fun delete(workspaceId: String, mountId: String): Boolean = configMutex.withLock {
        gate.publishPrepared(
            workspaceId = workspaceId,
            prepare = prepare@{
                rejectIfBusy(workspaceId)
                val current = mountDao.listByWorkspace(workspaceId)
                if (current.none { it.id == mountId }) return@prepare null
                WorkspaceChangeSpec.structural()
            },
            publish = {
                sessionInvalidation.closeWorkspace(workspaceId, wsRoot(workspaceId))
                mountDao.updateMountsInTransaction(workspaceId) { latest ->
                    rejectIfBusy(workspaceId)
                    WorkspaceDeviceMountPatch(
                        deletes = if (latest.any { it.id == mountId }) listOf(mountId)
                        else emptyList()
                    )
                }
                true
            },
        ) ?: false
    }

    /**
     * 解析为运行态 mount：enabled 的连接按授权与目录现状分成可用与失效两组，
     * 不抛错、不连坐（失效连接由运行时挂只读占位目录）。
     */
    suspend fun resolveMountSet(workspaceId: String): WorkspaceDeviceMountSet {
        coroutineContext.ensureActive()
        val entities = mountDao.listByWorkspace(workspaceId).filter { it.enabled }
        if (entities.isEmpty()) return WorkspaceDeviceMountSet(emptyList(), emptyList())
        val ready = storage.availability() == WorkspaceDeviceAvailability.READY
        val available = mutableListOf<WorkspaceDeviceMount>()
        val unavailable = mutableListOf<String>()
        for (entity in entities) {
            val source = if (!ready) null else try {
                resolveRelative(entity.sourceRelativePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (source != null) {
                available += WorkspaceDeviceMount(id = entity.id, alias = entity.alias, source = source)
            } else {
                unavailable += entity.alias
            }
        }
        coroutineContext.ensureActive()
        return WorkspaceDeviceMountSet(available.sortedBy { it.alias }, unavailable.sorted())
    }

    /** 当前可用的设备目录（失效连接不在其中）。 */
    suspend fun resolveMounts(workspaceId: String): List<WorkspaceDeviceMount> =
        resolveMountSet(workspaceId).available

    /**
     * 结构指纹（给工具 view snapshot）：覆盖每条连接的 id/alias/source/enabled +
     * 授权可用性。不含 name/root（由 `workspaceToolViewFingerprint` 承担），不含文件
     * 内容——普通写盘不推进 view epoch，只有结构变更（增删/换 source/启停/授权翻转）才变。
     */
    suspend fun snapshotFingerprint(workspaceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val available = storage.availability() == WorkspaceDeviceAvailability.READY
        digest.update(if (available) byteArrayOf(1) else byteArrayOf(0))
        for (entity in mountDao.listByWorkspace(workspaceId).sortedBy { it.id }) {
            val line = listOf(
                entity.id, entity.alias, entity.sourceRelativePath,
                if (entity.enabled) "1" else "0",
            ).joinToString("")
            digest.update(line.encodeToByteArray())
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------ 内部

    private fun requireAvailability() {
        val availability = storage.availability()
        if (availability != WorkspaceDeviceAvailability.READY) {
            throw WorkspaceDeviceConfigException(
                when (availability) {
                    WorkspaceDeviceAvailability.UNSUPPORTED_API ->
                        "Device directory access requires Android 11 (API 30) or later"
                    WorkspaceDeviceAvailability.PERMISSION_REQUIRED ->
                        "All-files access permission is not granted"
                    WorkspaceDeviceAvailability.PRIMARY_VOLUME_UNAVAILABLE ->
                        "Primary shared storage volume is unavailable"
                    WorkspaceDeviceAvailability.READY -> error("unreachable")
                }
            )
        }
    }

    /**
     * 结构变更前的 busy 判定：GENERATION/TOOL/CHILD/PENDING_APPROVAL 任一
     * 在册 → [WorkspaceDeviceBusyException] 拒绝（不 cancel 它们）；FILE_
     * OPERATION/HANDLE_CREATION/其他资源类不计 busy（允许先 close+await 由
     * gate victim 清理处理）。在 publishPrepared 的 prepare/publish 内调用
     * 即 admissionMutex 临界区复查——与准入互斥无 TOCTOU。
     */
    private fun rejectIfBusy(workspaceId: String) {
        val snapshot = gate.snapshot(workspaceId)
        val busy = snapshot.activeOperationsByKind
            .filterKeys { it in BUSY_OPERATION_KINDS }
            .values.sum()
        if (busy > 0 || usage?.isBusy(workspaceId) == true) {
            throw WorkspaceDeviceBusyException(
                workspaceId = workspaceId,
                busyOperations = busy.coerceAtLeast(1),
                detail = "active generation/tool/child/pending operations block " +
                    "device-mount changes; retry after they finish",
            )
        }
    }

    /** 相对路径 → canonical host 目录（guard 逐组件校验；绝不 mkdir source）。 */
    private fun resolveRelative(relativePath: String): File {
        require(!relativePath.startsWith('/') && '\\' !in relativePath && '\u0000' !in relativePath) {
            "Device directory path must be volume-relative"
        }
        val normalized = relativePath.trim().trimEnd('/')
        // 空串 = 主共享存储卷根本身
        if (normalized.isEmpty()) return guard().resolveSource(storage.primaryRoot().path)
        require(!normalized.split('/').any { it == ".." }) {
            "Device directory path must be a relative path inside the primary volume"
        }
        return guard().resolveSource(storage.primaryRoot().resolve(normalized).path)
    }

    private fun newEntity(
        workspaceId: String,
        alias: String,
        sourceRelativePath: String,
    ): WorkspaceDeviceMountEntity {
        val now = System.currentTimeMillis()
        // 先把 source 解析成 canonical host 目录，再把「卷根相对路径」规范化存库
        val resolved = resolveRelative(sourceRelativePath)
        val canonicalRelative = storage.primaryRoot().canonicalFile.toPath()
            .relativize(resolved.toPath()).toString().replace(File.separatorChar, '/')
        return WorkspaceDeviceMountEntity(
            id = Uuid.random().toString(),
            workspaceId = workspaceId,
            alias = alias.trim(),
            sourceRelativePath = canonicalRelative,
            enabled = true,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * 同工作区全量校验：限额/alias/同工作区重复嵌套/source 存在性与禁区
     * （`WorkspaceDeviceDirectoryGuard` 逐组件 canonical）。mounts 是全量
     * 最新集（含待 upsert 行），不是仅 diff。
     */
    private fun validateMountSet(mounts: List<WorkspaceDeviceMountEntity>) {
        guard().validateConnections(
            mounts.map {
                WorkspaceDeviceMount(
                    id = it.id,
                    alias = it.alias,
                    source = resolveRelative(it.sourceRelativePath),
                )
            }
        )
    }

    private suspend fun resolveMountForOperation(
        workspaceId: String,
        mountId: String,
    ): WorkspaceDeviceMount? {
        val entity = mountDao.getById(mountId)
            ?.takeIf { it.workspaceId == workspaceId && it.enabled }
            ?: return null
        val source = try {
            resolveRelative(entity.sourceRelativePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (!source.isDirectory) return null
        return WorkspaceDeviceMount(id = entity.id, alias = entity.alias, source = source)
    }

    private suspend fun wsRoot(workspaceId: String): String =
        workspaceDao.getById(workspaceId)?.root ?: workspaceId

    private companion object {
        /** 阻塞设备连接结构变更的操作种类（文件/手动资源不算 busy）。 */
        val BUSY_OPERATION_KINDS: Set<WorkspaceOperationKind> = setOf(
            WorkspaceOperationKind.GENERATION,
            WorkspaceOperationKind.TOOL,
            WorkspaceOperationKind.CHILD,
            WorkspaceOperationKind.PENDING_APPROVAL,
        )
        const val BROWSE_MAX_ENTRIES = 1000
    }
}

/** 受控浏览条目（名字 + 卷根相对路径；不暴露宿主绝对路径给前端） */
data class WorkspaceDeviceDirectoryEntry(
    val name: String,
    val relativePath: String,
)

class WorkspaceDeviceConfigException(message: String) : IllegalStateException(message)

/** 存在活跃 GENERATION/TOOL/CHILD/PENDING 操作时结构变更被拒（不取消在途）。 */
class WorkspaceDeviceBusyException(
    val workspaceId: String,
    val busyOperations: Int,
    detail: String,
) : IllegalStateException("Workspace $workspaceId is busy: $detail")

/** enabled 连接的运行态划分：可用挂载 + 失效（授权缺失/目录不在）连接的 alias。 */
data class WorkspaceDeviceMountSet(
    val available: List<WorkspaceDeviceMount>,
    val unavailableAliases: List<String>,
)
