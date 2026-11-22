package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkihub.data.ai.tools.WorkspacePersistentBashSessionManager
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.WorkspaceEnvItem
import me.rerere.rikkihub.data.workspace.WorkspaceEnvMirror
import me.rerere.rikkihub.data.workspace.WorkspaceEnvSetupService
import me.rerere.rikkihub.data.workspace.defaultRootfsUrl

class WorkspaceVM(
    private val repository: WorkspaceRepository,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
    private val persistentBashSessionManager: WorkspacePersistentBashSessionManager,
    private val envSetupService: WorkspaceEnvSetupService,
) : ViewModel() {
    val workspaces = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val lastEnvMirror: WorkspaceEnvMirror get() = envSetupService.lastMirror

    /** [envMirror] non-null: install the rootfs right away and run the default environment setup on it. */
    fun create(name: String, harnessMinimal: Boolean = false, envMirror: WorkspaceEnvMirror? = null) {
        viewModelScope.launch {
            val workspace = runCatching { repository.create(name, harnessMinimal) }.getOrNull() ?: return@launch
            if (envMirror != null) {
                runCatching {
                    envSetupService.installRootfsThenSetup(workspace.id, defaultRootfsUrl(), WorkspaceEnvItem.DEFAULT, envMirror)
                }
            }
        }
    }

    fun rename(workspace: WorkspaceEntity, name: String) {
        viewModelScope.launch {
            runCatching { repository.rename(workspace.id, name) }
        }
    }

    fun delete(workspace: WorkspaceEntity) {
        viewModelScope.launch {
            envSetupService.cancel(workspace.id)
            terminalSessionManager.closeWorkspace(workspace.root)
            persistentBashSessionManager.closeWorkspace(workspace.id)
            repository.delete(workspace.id)
        }
    }
}
