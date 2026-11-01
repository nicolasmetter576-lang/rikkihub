package me.rerere.rikkihub.ui.pages.setting.subagent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.tools.SubagentPresetConflictException
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.repository.SubagentPresetRepository
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceSubagentPresetDraft
import me.rerere.rikkihub.ui.pages.extensions.workspace.workspaceSubagentPresetClone
import me.rerere.rikkihub.ui.pages.extensions.workspace.workspaceSubagentPresetCopyKey

const val SUBAGENT_LIBRARY_UI_LIMIT = SubagentPresetRepository.MAX_LIBRARY_PRESETS

data class SubagentLibraryUiState(
    val loading: Boolean = true,
    val corrupt: Boolean = false,
    val presets: List<WorkspaceSubagentPreset>? = null,
    val workspaces: List<WorkspaceEntity> = emptyList(),
    val busy: Boolean = false,
    val draft: SubagentLibraryDraft? = null,
    val error: Int? = null,
    val errorDetail: String? = null,
)

class SubagentLibraryVM(
    private val repository: SubagentPresetRepository,
    private val workspaceRepository: WorkspaceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SubagentLibraryUiState())
    val state = _state.asStateFlow()
    private var observing = false

    init { reload() }

    fun reload() {
        if (observing) return
        observing = true
        _state.update { it.copy(loading = true, error = null, errorDetail = null) }
        viewModelScope.launch {
            try {
                combine(repository.libraryFlow(), workspaceRepository.listFlow()) { library, workspaces ->
                    library to workspaces
                }.catch { error ->
                    if (error is CancellationException) throw error
                    _state.update { it.copy(loading = false, presets = null, error = R.string.subagent_library_page_load_failed,
                        errorDetail = error.message) }
                }.collect { (library, workspaces) ->
                    _state.update { current ->
                        current.copy(loading = false, corrupt = library.corrupt, presets = library.presets,
                            error = current.error.takeUnless { it == R.string.subagent_library_page_load_failed },
                            errorDetail = current.errorDetail.takeUnless { current.error == R.string.subagent_library_page_load_failed },
                            workspaces = workspaces, draft = current.draft?.let { draft ->
                                if (current.busy) draft else draft.copy(conflict = draft.conflict ||
                                    subagentLibraryDraftConflict(draft, library.presets, workspaces))
                            })
                    }
                }
            } finally { observing = false }
        }
    }

    fun edit(preset: WorkspaceSubagentPreset? = null, clone: Boolean = false) {
        val current = state.value
        if (current.busy || current.loading || current.corrupt || current.draft != null ||
            current.error == R.string.subagent_library_page_load_failed) return
        val presets = current.presets ?: return
        if ((preset == null || clone) && presets.size >= SUBAGENT_LIBRARY_UI_LIMIT) return
        val role = when {
            preset == null -> WorkspaceSubagentPreset(key = if (presets.none { it.key == "preset" }) "preset" else
                workspaceSubagentPresetCopyKey("preset", presets.map { it.key }.toSet()))
            clone -> workspaceSubagentPresetClone(preset, presets.map { it.key }.toSet())
            else -> preset
        }
        val assignments = if (preset != null && !clone) subagentLibraryWorkspaceIds(preset.id, current.workspaces) else emptySet()
        _state.update { it.copy(error = null, errorDetail = null, draft = SubagentLibraryDraft(
            role = WorkspaceSubagentPresetDraft(role, expectedVersion = role.version.takeIf { preset != null && !clone }),
            workspaceIds = assignments, originalWorkspaceIds = assignments,
        )) }
    }

    fun updateRole(transform: (WorkspaceSubagentPresetDraft) -> WorkspaceSubagentPresetDraft) {
        if (state.value.busy) return
        _state.update { current ->
            val draft = current.draft ?: return@update current
            val before = draft.role
            val after = transform(before)
            current.copy(draft = draft.copy(role = after.copy(expectedVersion = before.expectedVersion,
                workspaceId = null, preset = after.preset.copy(id = before.preset.id,
                    key = if (before.expectedVersion != null) before.preset.key else after.preset.key,
                    libraryPresetId = null, version = before.preset.version,
                    executionRevision = before.preset.executionRevision))))
        }
    }

    fun selectWorkspace(workspaceId: String, selected: Boolean) {
        if (state.value.busy) return
        _state.update { current ->
            val draft = current.draft ?: return@update current
            if (selected) {
                val workspace = current.workspaces.firstOrNull { it.id == workspaceId } ?: return@update current
                if (subagentBindingBlock(draft.role.preset, workspace) != null) return@update current
            }
            current.copy(draft = draft.copy(workspaceIds = if (selected) draft.workspaceIds + workspaceId else draft.workspaceIds - workspaceId))
        }
    }

    /** Explicit conflict acknowledgement: preserve every typed field and checkbox, refresh only CAS baseline. */
    fun keepDraftAgainstLatest() {
        val current = state.value
        val draft = current.draft ?: return
        if (current.busy || current.loading || current.corrupt || current.error == R.string.subagent_library_page_load_failed) return
        val latest = current.presets?.firstOrNull { it.id == draft.role.preset.id }
        if (draft.role.expectedVersion != null && latest == null) return
        _state.update { it.copy(draft = draft.copy(conflict = false,
            role = draft.role.copy(expectedVersion = latest?.version,
                preset = draft.role.preset.copy(version = latest?.version ?: draft.role.preset.version,
                    executionRevision = latest?.executionRevision ?: draft.role.preset.executionRevision)),
            originalWorkspaceIds = subagentLibraryWorkspaceIds(draft.role.preset.id, current.workspaces)),
            error = null, errorDetail = null) }
    }

    fun dismissDraft() {
        if (!state.value.busy) _state.update { it.copy(draft = null) }
    }

    fun save() {
        val current = state.value
        val draft = current.draft ?: return
        if (current.loading || current.corrupt || current.presets == null || draft.conflict || draft.role.validationError() != null ||
            current.error == R.string.subagent_library_page_load_failed) return
        if (current.presets?.any { it.id != draft.role.preset.id && it.key == draft.role.preset.key.trim() } == true) return
        if (draft.workspaceIds.any { selected -> current.workspaces.firstOrNull { it.id == selected }
                ?.let { subagentBindingBlock(draft.role.preset, it) != null } != false }) {
            _state.update { it.copy(error = R.string.subagent_library_page_assignment_blocked) }
            return
        }
        mutate {
            val role = draft.role.toPreset().copy(libraryPresetId = null)
            if (draft.role.expectedVersion == null) repository.create(role, draft.workspaceIds)
            else repository.update(role, draft.role.expectedVersion, draft.workspaceIds)
            _state.update { it.copy(draft = null) }
        }
    }

    fun delete(preset: WorkspaceSubagentPreset) = mutate { repository.delete(preset.id, preset.version) }
    fun reset() = mutate { repository.reset() }
    fun dismissError() { _state.update { it.copy(error = null, errorDetail = null) } }

    private fun mutate(block: suspend () -> Unit) {
        if (state.value.busy) return
        _state.update { it.copy(busy = true, error = null, errorDetail = null) }
        viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: SubagentPresetConflictException) {
                _state.update { it.copy(error = R.string.subagent_library_page_conflict, errorDetail = error.message,
                    draft = it.draft?.copy(conflict = true)) }
            } catch (error: Throwable) {
                _state.update { it.copy(error = R.string.workspace_subagent_preset_save_failed, errorDetail = error.message) }
            } finally {
                _state.update { current -> current.copy(busy = false, draft = current.draft?.let { draft ->
                    draft.copy(conflict = draft.conflict ||
                        subagentLibraryDraftConflict(draft, current.presets, current.workspaces))
                }) }
            }
        }
    }
}
