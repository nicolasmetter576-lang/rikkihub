package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.db.entity.WorkspaceDeviceMountEntity
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceAvailability
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceDirectoryEntry
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceDirectoryService
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceStorage

/**
 * 设备目录连接页 VM。直连写语义：启用/解绑/换 source 全部交给
 * [WorkspaceDeviceDirectoryService]，由 gate/事务保证不静默失败；本层只把
 * `WorkspaceDeviceConfigException`/`WorkspaceDeviceStorageException`/Busy 等
 * 异常 message 如实放进 [WorkspaceDeviceDirectoryState.error] 展示。
 *
 * 依赖全部构造注入（服务已在 Koin 注册为单例），未在 DI 模块新增注册；
 * 组装（remember 保存实例），测试可直接构造传 fake。
 */
class WorkspaceDeviceDirectoryVM(
    private val workspaceId: String,
    private val service: WorkspaceDeviceDirectoryService,
    private val storage: WorkspaceDeviceStorage,
    private val runtimeGate: me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkspaceDeviceDirectoryState())
    val state = _state.asStateFlow()
    private var runtimeJob: Job? = null
    private var browseJob: Job? = null

    init {
        // 连接列表走 Room Flow 自动刷新（App/Web 双端写都反映）
        viewModelScope.launch {
            service.listFlow(workspaceId).collect { refreshRuntime() }
        }
        runtimeGate?.let { gate ->
            viewModelScope.launch { gate.epochsFlow(workspaceId).collect { refreshAvailability() } }
        }
        refreshAvailability()
    }

    /**
     * 实时重检授权可用性（授权状态不缓存；从系统设置页返回时页面会再次调用）。
     * 授权翻转后顺带重算运行态。
     */
    fun refreshAvailability() {
        _state.update { it.copy(availability = storage.availability()) }
        refreshRuntime()
    }

    fun refreshRuntime() {
        runtimeJob?.cancel()
        runtimeJob = viewModelScope.launch {
            try {
                val frame = withContext(Dispatchers.IO) {
                    val read: suspend () -> WorkspaceDeviceDirectoryState = {
                        val ready = storage.availability() == WorkspaceDeviceAvailability.READY
                        val available = if (ready) {
                            service.resolveMounts(workspaceId).mapTo(HashSet()) { it.id }
                        } else emptySet()
                        WorkspaceDeviceDirectoryState(
                            mounts = service.list(workspaceId), availableMountIds = available,
                            viewToken = service.viewToken(workspaceId),
                        )
                    }
                    runtimeGate?.withSnapshot(workspaceId) { read() } ?: read()
                }
                _state.update { it.copy(
                    mounts = frame.mounts, availableMountIds = frame.availableMountIds,
                    viewToken = frame.viewToken,
                ) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(availableMountIds = emptySet(), error = e.message) }
            }
        }
    }

    // ---------------------------------------------------------- 浏览

    /**
     * 受控目录浏览：只列出主共享存储卷根下的子目录。
     * 空路径仅浏览根下可连接子目录；所有层级统一过 service 禁区/软链过滤。
     */
    fun browseTo(relativePath: String) {
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    browsePath = relativePath,
                    browseEntries = emptyList(),
                    browseLoading = true,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    service.browse(relativePath)
                }
            }.onSuccess { entries ->
                _state.update { it.copy(browseEntries = entries, browseLoading = false) }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(
                        browseEntries = emptyList(),
                        browseLoading = false,
                        error = error.message ?: "Browse failed",
                    )
                }
            }
        }
    }

    fun browseUp() {
        val path = state.value.browsePath
        if (path.isEmpty()) return
        browseTo(path.substringBeforeLast('/', missingDelimiterValue = ""))
    }

    // ---------------------------------------------------------- 变更

    /** 新增连接。alias 非法时 UI 已前置拦截；服务侧冲突/限额异常原样上抛到 error。 */
    fun createMount(alias: String, sourceRelativePath: String) {
        mutate {
            service.create(
                workspaceId = workspaceId,
                alias = alias,
                sourceRelativePath = sourceRelativePath,
            )
        }
    }

    /** 编辑连接：只把**确实变化**的字段传给 update（未变的传 null，免去无谓的结构发布）。 */
    fun updateMount(
        mount: WorkspaceDeviceMountEntity,
        alias: String,
        sourceRelativePath: String,
    ) {
        mutate {
            service.update(
                workspaceId = workspaceId,
                mountId = mount.id,
                alias = alias.trim().takeIf { it != mount.alias },
                sourceRelativePath = sourceRelativePath.takeIf { it != mount.sourceRelativePath },
            )
        }
    }

    fun setEnabled(mount: WorkspaceDeviceMountEntity, enabled: Boolean) {
        mutate {
            service.update(
                workspaceId = workspaceId,
                mountId = mount.id,
                enabled = enabled,
            )
        }
    }

    /** 解绑：只删连接配置，不碰宿主 source 目录本体（服务端保证）。 */
    fun deleteMount(mount: WorkspaceDeviceMountEntity) {
        mutate { service.delete(workspaceId = workspaceId, mountId = mount.id) }
    }

    fun showError(message: String) { _state.update { it.copy(error = message) } }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun mutate(block: suspend () -> Any?) {
        viewModelScope.launch {
            _state.update { it.copy(mutating = true, error = null) }
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // WorkspaceDeviceConfigException / WorkspaceDeviceStorageException /
                // gate Busy 等：如实展示 message，不静默
                _state.update { it.copy(error = e.message ?: "Operation failed") }
            } finally {
                _state.update { it.copy(mutating = false) }
            }
            refreshAvailability()
        }
    }
}

data class WorkspaceDeviceDirectoryState(
    val availability: WorkspaceDeviceAvailability = WorkspaceDeviceAvailability.PRIMARY_VOLUME_UNAVAILABLE,
    val mounts: List<WorkspaceDeviceMountEntity> = emptyList(),
    /** 当前授权+目录校验通过、运行态实际可挂载的连接 id */
    val availableMountIds: Set<String> = emptySet(),
    val viewToken: String? = null,
    /** 写操作进行中（启用/解绑/编辑），UI 禁用重复提交 */
    val mutating: Boolean = false,
    /** 目录浏览器当前卷根相对路径（空 = 卷根本层） */
    val browsePath: String = "",
    val browseEntries: List<WorkspaceDeviceDirectoryEntry> = emptyList(),
    val browseLoading: Boolean = false,
    val error: String? = null,
)
