package me.rerere.rikkihub.di

import me.rerere.rikkihub.ui.pages.memory.MemoryFileVM
import me.rerere.rikkihub.ui.pages.assistant.AssistantVM
import me.rerere.rikkihub.ui.pages.assistant.detail.AssistantDetailVM
import me.rerere.rikkihub.ui.pages.backup.BackupVM
import me.rerere.rikkihub.ui.pages.chat.ChatDrawerVM
import me.rerere.rikkihub.ui.pages.chat.ChatVM
import me.rerere.rikkihub.ui.pages.debug.DebugVM
import me.rerere.rikkihub.ui.pages.favorite.FavoriteVM
import me.rerere.rikkihub.ui.pages.search.SearchVM
import me.rerere.rikkihub.ui.pages.history.HistoryVM
import me.rerere.rikkihub.ui.pages.stats.StatsVM
import me.rerere.rikkihub.ui.pages.imggen.ImgGenVM
import me.rerere.rikkihub.ui.pages.extensions.PromptVM
import me.rerere.rikkihub.ui.pages.extensions.QuickMessagesVM
import me.rerere.rikkihub.ui.pages.extensions.skills.SkillDetailVM
import me.rerere.rikkihub.ui.pages.extensions.skills.SkillsVM
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceDetailVM
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceVM
import me.rerere.rikkihub.ui.pages.setting.SettingVM
import me.rerere.rikkihub.ui.pages.setting.subagent.SubagentLibraryVM
import me.rerere.rikkihub.ui.pages.share.handler.ShareHandlerVM
import me.rerere.rikkihub.ui.pages.translator.TranslatorVM
import org.koin.core.module.dsl.viewModel
import me.rerere.rikkihub.ui.components.creator.CreatorProposalsVM
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import me.rerere.rikkihub.ui.pages.mediacreation.MediaCreationVM
import me.rerere.rikkihub.ui.pages.mediacreation.MediaCreationSessionsVM

val viewModelModule = module {
    viewModel<ChatVM> { params ->
        ChatVM(
            id = params.get(),
            context = get(),
            settingsStore = get(),
            conversationRepo = get(),
            chatService = get(),
            analytics = get(),
            filesManager = get(),
            favoriteRepository = get(),
            promptPolishHandler = get(),
        )
    }
    viewModelOf(::ChatDrawerVM)
    viewModelOf(::SettingVM)
    viewModelOf(::SubagentLibraryVM)
    viewModelOf(::DebugVM)
    viewModelOf(::CreatorProposalsVM)
    viewModelOf(::HistoryVM)
    viewModelOf(::AssistantVM)
    viewModel<AssistantDetailVM> {
        AssistantDetailVM(
            id = it.get(),
            settingsStore = get(),
            memoryRepository = get(),
            memoryConsolidationScheduler = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            appScope = get(),
        )
    }
    viewModel<MemoryFileVM> {
        MemoryFileVM(
            memoryId = it[0],
            path = it[1],
            memoryRepository = get(),
        )
    }
    viewModelOf(::TranslatorVM)
    viewModelOf(::MediaCreationSessionsVM)
    viewModel<MediaCreationVM> {
        MediaCreationVM(
            id = it.get(),
            context = get(),
            settingsStore = get(),
            repository = get(),
            service = get(),
            remoteFileStore = get(),
            appScope = get(),
        )
    }
    viewModel<ShareHandlerVM> {
        ShareHandlerVM(
            text = it.get(),
            settingsStore = get(),
        )
    }
    viewModelOf(::BackupVM)
    viewModelOf(::ImgGenVM)
    viewModelOf(::PromptVM)
    viewModelOf(::QuickMessagesVM)
    viewModelOf(::SkillsVM)
    // SkillDetailVM 的 io/result dispatcher 是带默认值的 CoroutineDispatcher 参数，
    // viewModelOf 会对全部参数做 DI 解析并直接炸 NoDefinitionFoundException —— 显式构造
    viewModel { SkillDetailVM(skillManager = get()) }
    viewModelOf(::WorkspaceVM)
    viewModel<WorkspaceDetailVM> {
        WorkspaceDetailVM(
            id = it.get(),
            repository = get(),
            subagentPresetRepository = get(),
            rootfsInstallService = get(),
            context = get(),
        )
    }
    viewModelOf(::FavoriteVM)
    viewModelOf(::SearchVM)
    viewModelOf(::StatsVM)
}
