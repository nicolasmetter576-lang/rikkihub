package me.rerere.rikkihub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import kotlinx.serialization.json.Json
import android.content.Context
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.data.fonts.FontDownloadService
import me.rerere.rikkihub.data.fonts.okHttpFontFetcher
import java.io.File
import me.rerere.rikkihub.claudelife.ClaudeLifeManager
import me.rerere.rikkihub.data.ai.tools.WorkspacePersistentBashSessionManager
import me.rerere.rikkihub.data.ai.tools.local.LocalTools
import me.rerere.rikkihub.data.ai.tools.ChatToolFactory
import me.rerere.rikkihub.data.creator.definition.AssistantRegistry
import me.rerere.rikkihub.data.creator.runtime.CreatorToolSurface
import me.rerere.rikkihub.data.creator.storage.CreatorReadDomain
import me.rerere.rikkihub.data.creator.storage.CreatorTranscriptStore
import me.rerere.rikkihub.data.creator.vault.CreatorDatabase
import me.rerere.rikkihub.data.creator.vault.CreatorDatabaseFactory
import me.rerere.rikkihub.data.creator.vault.CreatorVault
import me.rerere.rikkihub.data.creator.vault.CreatorVaultKeyStore
import me.rerere.rikkihub.data.event.AppEventBus
import me.rerere.rikkihub.data.workspace.WorkspaceLegacySessionInvalidation
import me.rerere.rikkihub.data.workspace.AndroidWorkspaceDeviceStorage
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceDirectoryService
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceStorage
import me.rerere.rikkihub.data.workspace.WorkspaceDeviceUsage
import me.rerere.rikkihub.data.workspace.WorkspaceRuntimeGate
import me.rerere.rikkihub.data.workspace.WorkspaceRootfsInstallService
import me.rerere.rikkihub.service.ChatNotificationManager
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import me.rerere.rikkihub.web.routes.WebWorkspaceTerminalManager
import me.rerere.rikkihub.utils.EmojiData
import me.rerere.rikkihub.utils.EmojiUtils
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.utils.SoundEffectPlayer
import me.rerere.rikkihub.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module
import me.rerere.rikkihub.service.MediaCreationService

val appModule = module {
    single<Json> { JsonInstant }

    single { WorkspaceLegacySessionInvalidation() }
    single<WorkspaceDeviceStorage> { AndroidWorkspaceDeviceStorage(context = get()) }
    single {
        WorkspaceDeviceDirectoryService(
            storage = get(),
            mountDao = get(),
            workspaceDao = get(),
            gate = get(),
            sessionInvalidation = get(),
            usage = get(),
        )
    }

    // 设备目录 busy 探针桥：ChatService 注册「已派发未准入生成 + 无 job 待审批」，
    // WorkspaceDeviceDirectoryService.rejectIfBusy 在 admissionMutex 内消费。
    single { WorkspaceDeviceUsage() }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get())
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get(), get())
    }

    single {
        Firebase.crashlytics
    }

    single {
        Firebase.analytics
    }

    single {
        SoundEffectPlayer(get())
    }

    single {
        WorkspaceTerminalSessionManager(get(), get(), workspaceRepository = get())
    }

    single {
        WorkspacePersistentBashSessionManager(get(), get(), get())
    }

    single {
        WebWorkspaceTerminalManager(get(), get())
    }

    // Rootfs 安装改为服务级任务（App 工作区页与 Web 共用，离开页面不中断）
    single {
        val appTerminals = get<WorkspaceTerminalSessionManager>()
        val persistentBash = get<WorkspacePersistentBashSessionManager>()
        val webTerminals = get<WebWorkspaceTerminalManager>()
        WorkspaceRootfsInstallService(
            repository = get(),
            scope = get<AppScope>(),
            closeSessions = { workspaceId, root ->
                appTerminals.closeWorkspace(root)
                persistentBash.closeWorkspace(workspaceId)
                webTerminals.closeWorkspace(workspaceId)
            },
        )
    }

    // 编辑器/聊天可选字体按需下载（服务级任务，App 与 Web 共用）
    single {
        FontDownloadService(
            fontsRoot = File(get<Context>().filesDir, FileFolders.FONTS),
            scope = get<AppScope>(),
            fetcher = okHttpFontFetcher(get()),
        )
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    single {
        ChatToolFactory(
            memoryRepository = get(),
            conversationRepository = get(),
            localTools = get(),
            mcpManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            context = get(),
            settingsStore = get(),
            generationLoop = get(),
            persistentBashSessionManager = get(),
            creatorToolSurface = get(),
        )
    }

    // ---- 创造域 ----

    single { AssistantRegistry() }

    single<CreatorDatabase> { CreatorDatabaseFactory.create(get()) }

    single { CreatorVault(database = get()) }

    single<me.rerere.rikkihub.data.creator.vault.CreatorStore> { get<CreatorVault>() }

    single { CreatorVaultKeyStore(context = get()) }

    single {
        CreatorToolSurface(
            readDomain = get(),
            bridge = get(),
            providerTester = me.rerere.rikkihub.data.creator.runtime.CreatorProviderTester(
                settingsProvider = { get<me.rerere.rikkihub.data.datastore.SettingsStore>().settingsFlow.value },
                client = me.rerere.rikkihub.data.creator.runtime.CreatorProviderTester.ManagerClient(get()),
            ),
        )
    }

    single {
        CreatorTranscriptStore(
            dir = java.io.File(CreatorDatabaseFactory.vaultDir(get()), "transcripts"),
            json = get(),
        )
    }

    single {
        val conversations = get<me.rerere.rikkihub.data.repository.ConversationRepository>()
        CreatorReadDomain(
            json = get(),
            settingsProvider = {
                get<me.rerere.rikkihub.data.datastore.SettingsStore>().settingsFlow.value
            },
            assistantRegistry = get(),
            managementRead = { get<me.rerere.rikkihub.data.management.runtime.ManagementRuntime>().readPort },
            conversationLister = { filter, offset, limit ->
                conversations.managedConversationPage(
                    me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.ID, filter, offset, limit,
                ).map {
                    me.rerere.rikkihub.data.creator.storage.CreatorConversationRow(
                        id = it.id, assistantId = it.assistantId, title = it.title,
                        updateAtMillis = it.updateAt, pinned = it.isPinned, deleted = it.deletedAt != null,
                    )
                }
            },
        )
    }

    single {
        val conversations = get<me.rerere.rikkihub.data.repository.ConversationRepository>()
        me.rerere.rikkihub.data.creator.storage.CreatorTranscriptMigration(
            transcripts = get(),
            conversationExists = { conversations.existsConversationById(it) },
            insertConversation = { conversations.insertConversation(it) },
        )
    }

    // ---- 管理链：提案/原生批准/协调器/三存储网关 ----
    single {
        val context = get<android.content.Context>()
        val mutationRuntime = me.rerere.rikkihub.data.mutation.MutationRuntime.forContext(context)
        val scope = this
        me.rerere.rikkihub.data.management.runtime.ManagementRuntime(
            root = java.io.File(context.noBackupFilesDir, "management"),
            settingsStore = get(),
            mutationRuntime = mutationRuntime,
            roomBackend = me.rerere.rikkihub.data.management.runtime.AppRoomManagementBackend(
                chatService = { scope.get<me.rerere.rikkihub.service.ChatService>() },
                conversations = get(),
                subagents = get(),
                workspaces = get(),
                mutationRuntime = mutationRuntime,
            ),
            skillsBackend = me.rerere.rikkihub.data.management.runtime.AppSkillsBackend(
                filesRoot = context.filesDir,
                receiptsDir = java.io.File(context.noBackupFilesDir, "management/receipts/files"),
                mutationRuntime = mutationRuntime,
            ),
            cipher = me.rerere.rikkihub.data.management.recovery.AesGcmPayloadCipher.android(),
            mutationAccepted = { me.rerere.rikkihub.data.management.runtime.ManagementReadiness.mutationBaseAccepted },
            definitionVersion = { me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.DEFINITION_VERSION },
        )
    }

    single {
        me.rerere.rikkihub.data.creator.runtime.CreatorManagementBridge(
            runtime = { get<me.rerere.rikkihub.data.management.runtime.ManagementRuntime>() },
            json = me.rerere.rikkihub.utils.JsonInstant,
        )
    }

    // claudelife（缓存保活）：Koin single。全局配置读取、各会话 warmer 创建、
    // 经 ProviderManager 的 provider 流式适配器重放。ChatService 构造注入。
    single {
        ClaudeLifeManager(
            appScope = get(),
            settingsStore = get(),
            providerManager = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            memoryConsolidationScheduler = get(),
            generationLoop = get(),
            translationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            chatToolFactory = get(),
            mcpManager = get(),
            filesManager = get(),
            workspaceRepository = get(),
            folderRepository = get(),
            persistentBashSessionManager = get(),
            claudeLifeManager = get(),
            workspaceDeviceUsage = get(),
        )
    }

    single {
        MediaCreationService(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            repository = get(),
            manager = get(),
            remoteFileStore = get(),
            okHttpClient = get(),
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get(),
            memoryRepo = get(),
            providerManager = get(),
            workspaceRepository = get(),
            terminalManager = get(),
            persistentBashSessionManager = get(),
            favoriteRepo = get(),
            translationHandler = get(),
            deviceService = get(),
            deviceStorage = get(),
            subagentPresetRepository = get(),
            skillManager = get(),
            rootfsInstallService = get(),
            fontDownloadService = get(),
            translationHistoryRepo = get(),
            memoryScheduler = get(),
        )
    }
}
