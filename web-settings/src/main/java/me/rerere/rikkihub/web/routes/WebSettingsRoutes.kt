package me.rerere.rikkihub.web.routes

import io.ktor.server.routing.Route
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkihub.data.ai.TranslationHandler
import me.rerere.rikkihub.data.ai.tools.WorkspacePersistentBashSessionManager
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.MemoryRepository
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.service.ChatService

fun Route.installWebSettingsRoutes(
    settingsStore: SettingsStore,
    conversationRepo: ConversationRepository,
    memoryRepo: MemoryRepository,
    filesManager: FilesManager,
    providerManager: ProviderManager,
    workspaceRepository: WorkspaceRepository? = null,
    terminalManager: WebWorkspaceTerminalManager? = null,
    persistentBashSessionManager: WorkspacePersistentBashSessionManager? = null,
    chatService: ChatService? = null,
    translationHandler: TranslationHandler? = null,
    // 设备目录直连 (可选, 默认 null 保持旧调用点不破 / 不挂 device 路由)
    deviceService: me.rerere.rikkihub.data.workspace.WorkspaceDeviceDirectoryService? = null,
    deviceStorage: me.rerere.rikkihub.data.workspace.WorkspaceDeviceStorage? = null,
    subagentPresetRepository: me.rerere.rikkihub.data.repository.SubagentPresetRepository? = null,
    favoriteRepo: me.rerere.rikkihub.data.repository.FavoriteRepository? = null,
    skillManager: me.rerere.rikkihub.data.files.SkillManager? = null,
    rootfsInstallService: me.rerere.rikkihub.data.workspace.WorkspaceRootfsInstallService? = null,
    editorFonts: me.rerere.rikkihub.data.fonts.EditorFontFiles? = null,
    translationHistoryRepo: me.rerere.rikkihub.data.repository.TranslationHistoryRepository? = null,
    memoryScheduler: me.rerere.rikkihub.data.ai.MemoryConsolidationScheduler? = null,
) {
    assistantRoutes(
        settingsStore, conversationRepo, memoryRepo, filesManager,
        workspaceExists = workspaceRepository?.let { repo -> { id -> repo.getById(id) != null } },
        availableSkillNames = skillManager?.let { manager -> { manager.listSkills().mapTo(HashSet()) { it.name } } },
    )
    memoryRoutes(settingsStore, memoryRepo, memoryScheduler)
    if (skillManager != null) skillRoutes(skillManager) { settingsStore.settingsFlow.value.assistants }
    providerRoutes(settingsStore, providerManager)
    extensionRoutes(settingsStore)
    cacheKeepAliveRoutes(settingsStore)
    toolExecutionRoutes(settingsStore)
    tuiqiaoRoutes(settingsStore, me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoHandler(providerManager))
    zaizaoRoutes(settingsStore, me.rerere.rikkihub.data.ai.zaizao.ZaizaoHandler(providerManager))
    promptPolishRoutes(settingsStore, me.rerere.rikkihub.data.ai.polish.PromptPolishHandler(providerManager), conversationRepo)
    editorTypographyRoutes(settingsStore)
    if (editorFonts != null) fontRoutes(editorFonts, settingsStore)
    if (chatService != null) {
        storageRoutes(filesManager, conversationRepo, settingsStore, chatService)
        if (favoriteRepo != null) favoriteRoutes(favoriteRepo, conversationRepo, chatService)
        compressRoutes(chatService, conversationRepo)
    }
    if (translationHandler != null) {
        translatorRoutes(settingsStore, translationHandler, translationHistoryRepo)
    }
    if (workspaceRepository != null) {
        workspaceSubagentPresetRoutes(workspaceRepository, settingsStore)
        if (subagentPresetRepository != null) subagentPresetLibraryRoutes(subagentPresetRepository, workspaceRepository, settingsStore)
    }
    if (workspaceRepository != null && terminalManager != null && persistentBashSessionManager != null) {
        workspaceRoutes(
            workspaceRepository,
            terminalManager,
            persistentBashSessionManager,
            settingsStore,
            deviceService = deviceService,
            deviceStorage = deviceStorage,
            rootfsInstallService = rootfsInstallService,
        )
    }
}
