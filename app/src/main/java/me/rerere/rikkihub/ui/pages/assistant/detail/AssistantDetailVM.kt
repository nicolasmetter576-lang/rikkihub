package me.rerere.rikkihub.ui.pages.assistant.detail

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkihub.data.ai.MemoryConsolidationStatus
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.mergeObjectEdits
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.files.SkillManager
import me.rerere.rikkihub.data.files.SkillMetadata
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.MemoryFile
import me.rerere.rikkihub.data.model.Avatar
import me.rerere.rikkihub.data.model.Tag
import me.rerere.rikkihub.data.repository.MemoryRepository
import me.rerere.rikkihub.data.repository.memoryId
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

private const val TAG = "AssistantDetailVM"

class AssistantDetailVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val memoryConsolidationScheduler: MemoryConsolidationScheduler,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val appScope: AppScope,
) : ViewModel() {
    private val assistantId = Uuid.parse(id)

    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val mcpServerConfigs = settingsStore
        .settingsFlow.map { settings ->
            settings.mcpServers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val assistant: StateFlow<Assistant> = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistants.find { it.id == assistantId } ?: Assistant()
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = Assistant()
        )

    // 助手刚加载出来前是占位的 Assistant()，id 不对，不能拿它查记忆库
    private val memoryIdFlow = assistant
        .map { currentAssistant -> currentAssistant.takeIf { it.id == assistantId }?.memoryId ?: assistantId.toString() }
        .distinctUntilChanged()

    val memories: StateFlow<List<MemoryFile>> = memoryIdFlow
        .flatMapLatest { memoryId -> memoryRepository.getFilesFlow(memoryId) }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val memoryConsolidation: StateFlow<MemoryConsolidationStatus> = memoryIdFlow
        .flatMapLatest { memoryId -> memoryConsolidationScheduler.statusOf(memoryId) }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = MemoryConsolidationStatus()
        )

    val providers = settingsStore
        .settingsFlow
        .map { settings ->
            settings.providers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val tags = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistantTags
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository
        .listFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    fun updateTags(tagIds: List<Uuid>, tags: List<Tag>) {
        appScope.launch {
            settingsStore.tryUpdate { settings ->
                settings.copy(
                    assistantTags = tags
                )
            }
            update(
                assistant.value.copy(
                    tags = tagIds.toList()
                )
            )
            Log.d(TAG, "updateTags: ${tagIds.joinToString(",")}")
            cleanupUnusedTags()
        }
    }

    fun cleanupUnusedTags() {
        appScope.launch {
            settingsStore.tryUpdate { settings ->
                val validTagIds = settings.assistantTags.map { it.id }.toSet()

                // 清理 assistant 中的无效 tag id
                val cleanedAssistants = settings.assistants.map { assistant ->
                    val validTags = assistant.tags.filter { tagId ->
                        validTagIds.contains(tagId)
                    }
                    if (validTags.size != assistant.tags.size) {
                        assistant.copy(tags = validTags)
                    } else {
                        assistant
                    }
                }

                // 获取清理后的 assistant 中使用的 tag id
                val usedTagIds = cleanedAssistants.flatMap { it.tags }.toSet()

                // 清理未使用的 tags
                val cleanedTags = settings.assistantTags.filter { tag ->
                    usedTagIds.contains(tag.id)
                }

                settings.copy(
                    assistants = cleanedAssistants,
                    assistantTags = cleanedTags
                )
            }
        }
    }

    fun update(assistant: Assistant) {
        // 写入必须活到 VM 销毁之后（CommitOnDoneTextField 在 onDispose 提交）。
        // 页面提交的是整份助手：以页面读取时的助手为基线算出这次改了哪些字段，只把这些
        // 字段写到最新的助手上。快速输入时基线可能落后于最新值，按字段合并不会冲突丢字，
        // 也不会把别处（网页端、创造助手）刚改的其他字段改回旧值。
        // 文件清理只在提交成功后、且无其他引用时进行。
        appScope.launch {
            val base = settingsStore.settingsFlow.value
            if (base.init) return@launch
            val old = base.assistants.find { it.id == assistant.id } ?: return@launch
            if (old == assistant) return@launch
            var replaced: Pair<Assistant, Assistant>? = null
            val committed = settingsStore.tryUpdate { latest ->
                val current = latest.assistants.find { it.id == assistant.id } ?: return@tryUpdate latest
                val merged = mergeObjectEdits(Assistant.serializer(), old, assistant, current)
                replaced = current to merged
                latest.copy(assistants = latest.assistants.map { if (it.id == assistant.id) merged else it })
            }
            replaced?.takeIf { committed }?.let { (before, after) -> releaseReplacedFiles(before, after) }
        }
    }

    private suspend fun releaseReplacedFiles(old: Assistant, new: Assistant) {
        val latest = settingsStore.settingsSnapshot()
        val stillReferenced = buildSet {
            latest.assistants.forEach { a ->
                (a.avatar as? Avatar.Image)?.let { add(it.url) }
                a.background?.let { add(it) }
            }
        }
        if (old.avatar is Avatar.Image && old.avatar != new.avatar && old.avatar.url !in stillReferenced) {
            checkAvatarDelete(old, new)
        }
        if (old.background != null && old.background != new.background && old.background !in stillReferenced) {
            checkBackgroundDelete(old, new)
        }
    }

    /** 当前助手读写的记忆库 */
    val memoryId: String
        get() = assistant.value.takeIf { it.id == assistantId }?.memoryId ?: assistantId.toString()

    /** 立即做梦：先整理所有待整理的对话，再把整个记忆库清理一遍 */
    fun organizeMemory() {
        memoryConsolidationScheduler.organize(memoryId)
    }

    fun deleteMemory(memory: MemoryFile) {
        val memoryId = memoryId
        appScope.launch {
            memoryRepository.deleteFile(memoryId, memory.path)
        }
    }

    fun checkAvatarDelete(old: Assistant, new: Assistant) {
        if (old.avatar is Avatar.Image && old.avatar != new.avatar) {
            filesManager.deleteChatFiles(listOf(old.avatar.url.toUri()))
        }
    }

    fun checkBackgroundDelete(old: Assistant, new: Assistant) {
        val oldBackground = old.background
        val newBackground = new.background

        if (oldBackground != null && oldBackground != newBackground) {
            try {
                val oldUri = oldBackground.toUri()
                if (oldUri.scheme == "content" || oldUri.scheme == "file") {
                    filesManager.deleteChatFiles(listOf(oldUri))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete background file: $oldBackground", e)
            }
        }
    }
}
