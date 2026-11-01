package me.rerere.rikkihub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.data.ai.MemoryDreamConfig
import me.rerere.rikkihub.data.ai.polish.PromptPolishConfig
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoConfig
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoConfig
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.DisplaySetting
import me.rerere.rikkihub.data.datastore.applyDisplaySettingsPatch
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.ai.mcp.McpManager

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
    private val appScope: AppScope,
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    internal suspend fun deleteSpeechProvider(target: SpeechDeletionTarget) {
        settingsStore.update { current -> deleteSpeechProvider(current, target) }
    }

    /** Display UI submits only changed fields, never an old whole Settings snapshot. */
    fun updateDisplaySettings(previous: DisplaySetting, next: DisplaySetting) {
        appScope.launch {
            settingsStore.tryUpdate { latest ->
                latest.copy(displaySetting = applyDisplaySettingsPatch(latest.displaySetting, previous, next))
            }
        }
    }

    /** 推敲 settings change from the latest stored value, never an old whole-Settings snapshot. */
    fun updateTuiqiao(transform: (TuiqiaoConfig) -> TuiqiaoConfig) {
        appScope.launch {
            settingsStore.update { latest -> latest.copy(tuiqiao = transform(latest.tuiqiao)) }
        }
    }

    /** 再造 settings change from the latest stored value. */
    fun updateZaizao(transform: (ZaizaoConfig) -> ZaizaoConfig) {
        appScope.launch {
            settingsStore.update { latest -> latest.copy(zaizao = transform(latest.zaizao)) }
        }
    }

    fun updatePromptPolish(transform: (PromptPolishConfig) -> PromptPolishConfig) {
        appScope.launch {
            settingsStore.update { latest -> latest.copy(promptPolish = transform(latest.promptPolish)) }
        }
    }

    /** 做梦设置同样在最新值上改 */
    fun updateMemoryDream(transform: (MemoryDreamConfig) -> MemoryDreamConfig) {
        appScope.launch {
            settingsStore.update { latest -> latest.copy(memoryDream = transform(latest.memoryDream)) }
        }
    }

    /**
     * Functional edit against the latest stored settings, never an old whole-Settings snapshot.
     * 写入必须活到 VM 销毁之后（CommitOnDoneTextField 在 onDispose 提交）
     */
    fun updateSettings(transform: (Settings) -> Settings) {
        appScope.launch {
            settingsStore.tryUpdate(transform)
        }
    }

}
