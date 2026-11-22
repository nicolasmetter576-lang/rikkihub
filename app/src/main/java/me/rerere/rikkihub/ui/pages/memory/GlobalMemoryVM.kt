package me.rerere.rikkihub.ui.pages.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkihub.data.ai.MemoryConsolidationStatus
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.MemoryFile
import me.rerere.rikkihub.data.repository.MemoryConflictStrategy
import me.rerere.rikkihub.data.repository.MemoryMigrationPreview
import me.rerere.rikkihub.data.repository.MemoryMigrationResult
import me.rerere.rikkihub.data.repository.MemoryRepository
import kotlin.uuid.Uuid

class GlobalMemoryVM(
    settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val memoryConsolidationScheduler: MemoryConsolidationScheduler,
    private val appScope: AppScope,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val memories: StateFlow<List<MemoryFile>> = memoryRepository
        .getFilesFlow(MemoryRepository.GLOBAL_MEMORY_ID)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val consolidation: StateFlow<MemoryConsolidationStatus> = memoryConsolidationScheduler
        .statusOf(MemoryRepository.GLOBAL_MEMORY_ID)
        .stateIn(viewModelScope, SharingStarted.Eagerly, MemoryConsolidationStatus())

    val fileCountsByAssistant: StateFlow<Map<String, Int>> = memoryRepository
        .getFileCountsByAssistantFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun organizeMemory() {
        memoryConsolidationScheduler.organize(MemoryRepository.GLOBAL_MEMORY_ID)
    }

    fun cancelDream() {
        memoryConsolidationScheduler.cancel(MemoryRepository.GLOBAL_MEMORY_ID)
    }

    suspend fun countBackfillCandidates(sinceMs: Long): Int =
        memoryConsolidationScheduler.countBackfillCandidates(MemoryRepository.GLOBAL_MEMORY_ID, sinceMs)

    fun backfill(sinceMs: Long) {
        memoryConsolidationScheduler.backfill(MemoryRepository.GLOBAL_MEMORY_ID, sinceMs)
    }

    fun deleteMemory(memory: MemoryFile) {
        appScope.launch {
            memoryRepository.deleteFile(MemoryRepository.GLOBAL_MEMORY_ID, memory.path)
        }
    }

    suspend fun previewMigrationFrom(assistantId: Uuid): MemoryMigrationPreview =
        memoryRepository.previewMigration(
            fromMemoryId = assistantId.toString(),
            toMemoryId = MemoryRepository.GLOBAL_MEMORY_ID,
        )

    suspend fun migrateFrom(
        assistantId: Uuid,
        strategy: MemoryConflictStrategy,
        syncMarks: Boolean,
    ): MemoryMigrationResult {
        val result = memoryRepository.migrateFiles(
            fromMemoryId = assistantId.toString(),
            toMemoryId = MemoryRepository.GLOBAL_MEMORY_ID,
            strategy = strategy,
        )
        if (syncMarks) {
            memoryConsolidationScheduler.syncMarksToGlobal(assistantId)
        }
        return result
    }
}
