package me.rerere.rikkihub.di

import me.rerere.rikkihub.data.repository.CharacterRepository
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.ui.pages.memory.GlobalMemoryVM
import me.rerere.rikkihub.ui.pages.memory.MemoryFileVM
import me.rerere.rikkihub.ui.pages.assistant.AssistantVM
import me.rerere.rikkihub.ui.pages.assistant.detail.AssistantDetailVM
import me.rerere.rikkihub.ui.pages.backup.BackupVM
import me.rerere.rikkihub.ui.pages.chat.ChatDrawerVM
import me.rerere.rikkihub.ui.pages.character.CharacterVM
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
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.BookImportVM
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.BeatBoardVM
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.BookOutlineVM
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.BookReplaceVM
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.ChapterOutlineVM
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.BookVM
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.bookMentions
import me.rerere.rikkihub.ui.pages.setting.SettingVM
import me.rerere.rikkihub.ui.pages.setting.subagent.SubagentLibraryVM
import me.rerere.rikkihub.ui.pages.share.handler.ShareHandlerVM
import me.rerere.rikkihub.ui.pages.translator.TranslatorVM
import org.koin.core.module.dsl.viewModel
import me.rerere.rikkihub.ui.components.creator.CreatorProposalsVM
import org.koin.core.module.dsl.viewModelOf
import me.rerere.rikkihub.ui.pages.files.FileManagerVM
import org.koin.dsl.module
import kotlin.uuid.Uuid
import me.rerere.rikkihub.ui.pages.mediacreation.MediaCreationVM
import me.rerere.rikkihub.ui.pages.mediacreation.MediaCreationSessionsVM

val viewModelModule = module {
    viewModel<ChatVM> { params ->
        ChatVM(
            id = params.get(),
            characterId = params.getOrNull<Uuid>(),
            context = get(),
            settingsStore = get(),
            conversationRepo = get(),
            chatService = get(),
            analytics = get(),
            filesManager = get(),
            favoriteRepository = get(),
            promptPolishHandler = get(),
            characterRepository = get(),
        )
    }
    viewModelOf(::ChatDrawerVM)
    viewModelOf(::CharacterVM)
    viewModelOf(::SettingVM)
    viewModelOf(::SubagentLibraryVM)
    viewModelOf(::DebugVM)
    viewModelOf(::FileManagerVM)
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
    viewModelOf(::GlobalMemoryVM)
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
    viewModel {
        val settingsStore = get<SettingsStore>()
        val characters = get<CharacterRepository>()
        BookReplaceVM(
            workspaceId = it.get(),
            store = get(),
            replacer = get(),
            aliasFinder = get(),
            settingsStore = settingsStore,
            workspaceRepository = get(),
            mentions = { words -> bookMentions(words, settingsStore.settingsFlow.value, characters) },
        )
    }
    viewModel { BookImportVM(workspaceId = it.get(), context = get(), importer = get()) }
    viewModel { BookOutlineVM(workspaceId = it.get(), store = get(), outliner = get()) }
    viewModel { params -> ChapterOutlineVM(workspaceId = params[0], chapterId = params[1], store = get(), outliner = get()) }
    viewModel { params -> BeatBoardVM(workspaceId = params[0], chapterId = params[1], store = get(), writer = get()) }
    viewModel { BookVM(workspaceId = it.get(), store = get()) }
    viewModel<WorkspaceDetailVM> {
        WorkspaceDetailVM(
            id = it.get(),
            repository = get(),
            subagentPresetRepository = get(),
            rootfsInstallService = get(),
            envSetupService = get(),
            context = get(),
        )
    }
    viewModelOf(::FavoriteVM)
    viewModelOf(::SearchVM)
    viewModelOf(::StatsVM)
}
