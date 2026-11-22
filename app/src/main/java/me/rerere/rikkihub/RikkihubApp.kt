package me.rerere.rikkihub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.registry.ModelsDevCatalog
import me.rerere.workspace.WorkspaceManager
import android.util.Log
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import me.rerere.rikkihub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkihub.data.ai.tools.markStaleRunningSubagentLogs
import me.rerere.rikkihub.data.files.FileFolders
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import me.rerere.common.android.appTempFolder
import me.rerere.rikkihub.di.appModule
import me.rerere.rikkihub.di.dataSourceModule
import me.rerere.rikkihub.di.repositoryModule
import me.rerere.rikkihub.di.viewModelModule
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.sync.BackupManager
import me.rerere.rikkihub.data.sync.LocalSnapshotScheduler
import me.rerere.rikkihub.data.sync.LocalSnapshotStore
import me.rerere.rikkihub.data.sync.RestoreFailedException
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.service.WebServerService
import me.rerere.rikkihub.utils.CrashHandler
import me.rerere.rikkihub.utils.EmojiData
import me.rerere.rikkihub.utils.fatalHaltMarkerFile
import me.rerere.rikkihub.utils.DatabaseUtil
import me.rerere.rikkihub.claudelife.ClaudeLifeManager
import me.rerere.rikkihub.data.mutation.MutationRuntime
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import me.rerere.rikkihub.service.MediaCreationService

private const val TAG = "RikkihubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"
const val MEDIA_CREATION_NOTIFICATION_CHANNEL_ID = "media_creation"
const val MEMORY_DREAM_NOTIFICATION_CHANNEL_ID = "memory_dream"

class RikkihubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 关闭 Compose 智能选择：ColorOS 的 TextClassifier 会把长按的词异步扩成整句，
        // 之后系统浮动工具栏只剩「全选」、没有「复制」。必须在任何 Compose 内容组合前设置。
        ComposeFoundationFlags.isSmartSelectionEnabled = false
        // 「记录请求」开关（默认开启）在任何网络请求之前恢复用户的选择
        me.rerere.rikkihub.data.ai.RequestLoggingPreference.restore(this)
        // models.dev 快照只补手写规则没覆盖的型号；首次用到才读，这里提前在后台读好
        ModelRegistry.installCatalog { assets.open("models_dev.json").use(ModelsDevCatalog::decode) }
        // Restore files and settings before eager Koin singletons or workers can access them.
        try {
            val restored = runBlocking(Dispatchers.IO) {
                BackupManager.applyPendingRestore(this@RikkihubApp, JsonInstant)
            }
            if (restored) {
                Log.i(TAG, "Pending backup restore committed")
            }
        } catch (e: RestoreFailedException) {
            Log.e(TAG, "Backup restore rolled back", e)
        }
        startKoin {
            androidLogger()
            androidContext(this@RikkihubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        // Pending restore is installed before Koin or this asynchronous package prewarm.
        prewarmBuiltinSkills()
        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // halt() bypasses crash handlers, so toMutableStateFlow records a marker file first.
        fatalHaltMarkerFile = File(noBackupFilesDir, "fatal-flow-halt.marker")

        // delete temp files
        deleteTempFiles()

        // cleanup stale tool output files
        cleanupToolOutputs()

        // 子代理日志目录不随启动清空 (重启后仍可阅读历史运行过程),
        // 只把进程被杀前仍是 running 的日志收尾为 interrupted
        markStaleSubagentLogs()

        // cleanup workspace temp dirs (proot + rootfs /tmp)
        cleanupWorkspaceTempDirs()

        // xdg-open inside workspaces (e.g. agy sign-in) opens links in the browser through this
        get<me.rerere.rikkihub.data.workspace.WorkspaceUrlOpener>().start()

        // check workspace integrity (mark workspaces with missing files as broken after backup restore)
        checkWorkspaceIntegrity()

        // sync upload files to DB
        syncManagedFiles()

        // 用量统计：订阅类提供商的额度读数在后台记录，有新用量时自动刷新额度
        runCatching {
            val quotaTracker = get<me.rerere.rikkihub.data.ai.QuotaTracker>()
            get<me.rerere.rikkihub.data.ai.UsageRecorder>().onAccountUsage = quotaTracker::onAccountUsage
            get<me.rerere.rikkihub.data.ai.UsageRecorder>().onQuotaReadings = quotaTracker::onRequestQuota
            get<me.rerere.rikkihub.data.ai.UsageRecorder>().spendGuard = me.rerere.rikkihub.data.ai.ProviderSpendGuard(
                context = this@RikkihubApp,
                settingsStore = get(),
                stats = get(),
                eventBus = get(),
            )
            quotaTracker.start()
        }.onFailure { Log.e(TAG, "Failed to start quota tracking", it) }

        // 酒馆引擎的分词器首次计数要加载编码表（约 1 秒），启动时在后台预热，免得第一次发消息时卡一下
        get<AppScope>().launch(Dispatchers.Default) { runCatching { get<me.rerere.rikkihub.data.ai.tavern.TavernRuntime>().warmUp() } }

        // Start WebServer if enabled in settings
        startWebServerIfEnabled()

        // 接管上次进程被杀时还没跑完的媒体生成
        resumeMediaCreations()

        // 记忆库做梦：切到后台时立即整理，启动时补上次没整理完的对话
        resumeMemoryConsolidation()

        // claudelife：settingsFlow 就绪后订阅全局保活配置与设置变化（关闭即停、
        // 间隔/上限重算、上下文复核）；幂等。保活任务只在真实请求开始侧建立，进程死即全停。
        startClaudeLife()

        // 管理链启动屏障：先同步恢复持久 fence 并安装普通写准入，再异步撤销旧批准/对账回执。
        startManagementRecovery()

        // 2.7.x 独立创造会话 → 普通会话（一次性；失败保留旧文件下次重试）
        migrateCreatorTranscript()

        // Increment launch count
        incrementLaunchCount()

        // 预热 emoji 数据，避免首次打开表情选择器时在调用线程解析大 JSON
        warmupEmojiData()
        get<AppScope>().launch(Dispatchers.Default) { ModelRegistry.MODEL_CONTEXT_LENGTH.getData("warm-up") }

        // 自动本地快照调度（仅在前台窗口延迟触发）
        get<LocalSnapshotScheduler>().start()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    private fun cleanupWorkspaceTempDirs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceManager>().cleanupAllTempDirs()
            }.onFailure {
                Log.e(TAG, "cleanupWorkspaceTempDirs failed", it)
            }
        }
    }

    private fun migrateCreatorTranscript() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<me.rerere.rikkihub.data.creator.storage.CreatorTranscriptMigration>().migrate()
            }.onFailure {
                Log.e(TAG, "migrateCreatorTranscript failed", it)
            }
        }
    }

    private fun startManagementRecovery() {
        val runtime = get<me.rerere.rikkihub.data.management.runtime.ManagementRuntime>()
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { runtime.installStartupProtection() }
        } catch (failure: Exception) {
            // Fences that cannot be read keep management writes closed (ensureStarted retries).
            Log.e(TAG, "Management fence restore failed", failure)
        }
        get<AppScope>().launch(Dispatchers.IO) {
            try {
                runtime.ensureStarted()
                // The mutation base is open only once fences are restored and every
                // interrupted proposal has been reconciled from the stores' receipts.
                me.rerere.rikkihub.data.management.runtime.ManagementReadiness.mutationBaseAccepted = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "Management startup reconciliation failed", failure)
            }
        }
    }


    private fun prewarmBuiltinSkills() {
        get<AppScope>().launch(Dispatchers.IO) {
            try {
                me.rerere.rikkihub.data.files.BuiltinSkills.ensure(this@RikkihubApp)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Builtin skill publication failed; lazy reads will retry", failure)
            }
        }
    }

    private fun warmupEmojiData() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<EmojiData>()
            }.onFailure {
                Log.e(TAG, "warmupEmojiData failed", it)
            }
        }
    }

    private fun startClaudeLife() {
        get<AppScope>().launch {
            runCatching {
                get<ClaudeLifeManager>().start()
            }.onFailure {
                Log.w(TAG, "startClaudeLife failed", it)
            }
        }
    }

    private fun resumeMediaCreations() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<MediaCreationService>().resumePending()
            }.onFailure {
                Log.e(TAG, "resumeMediaCreations failed", it)
            }
        }
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                get<SettingsStore>().incrementLaunchCount()
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun checkWorkspaceIntegrity() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceRepository>().checkIntegrity()
            }.onFailure {
                Log.e(TAG, "checkWorkspaceIntegrity failed", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            BackupManager.cleanupTempDir(this@RikkihubApp)
            runCatching { get<LocalSnapshotStore>().sweepIncomplete() }
                .onFailure { Log.w(TAG, "sweepIncomplete snapshots failed", it) }
        }
    }

    private fun cleanupToolOutputs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun markStaleSubagentLogs() {
        val processStartedAt = System.currentTimeMillis()
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(filesDir, FileFolders.SUBAGENT_RUNS)
                if (dir.exists()) {
                    markStaleRunningSubagentLogs(dir, nowMillis = processStartedAt)
                }
            }.onFailure {
                Log.e(TAG, "markStaleSubagentLogs failed", it)
            }
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
            runCatching { MutationRuntime.forContext(this@RikkihubApp).deleteRetiredFiles() }
                .onFailure { Log.e(TAG, "deleting retired mutation files failed", it) }
            // Deleted conversations past their undo window are dropped for good.
            runCatching { get<ConversationRepository>().purgeExpiredDeletedConversations() }
                .onFailure { Log.e(TAG, "purging deleted conversations failed", it) }
            // Released attachments whose retention window ended (references re-checked).
            runCatching {
                get<SettingsStore>().settingsSnapshot() // settings references must be loaded first
                attachmentRetentionSweeper().sweep()
            }
                .onFailure { Log.e(TAG, "attachment retention sweep failed", it) }
        }
    }

    private fun attachmentRetentionSweeper(): me.rerere.rikkihub.data.files.AttachmentRetentionSweeper {
        val files = get<FilesManager>()
        val conversations = get<ConversationRepository>()
        val settings = get<SettingsStore>()
        val database = get<me.rerere.rikkihub.data.db.AppDatabase>()
        val evaluator = me.rerere.rikkihub.web.routes.StorageProtectionEvaluator(
            dbHasReference = { url, relativePath -> conversations.hasStorageFileReference(url, relativePath) },
            sessionRefUrls = { get<me.rerere.rikkihub.service.ChatService>().referencedFileUrlsSnapshot() },
            // Not-yet-loaded (dummy) settings throw → CHECK_FAILED → file kept.
            settingsRefUrls = {
                me.rerere.rikkihub.web.routes.collectSettingsFileRefs(
                    settings.settingsFlow.value.takeUnless { it.init } ?: error("settings not loaded"))
            },
        )
        return me.rerere.rikkihub.data.files.AttachmentRetentionSweeper(
            expired = { now -> database.attachmentRetentionDao().expired(now) },
            release = { conversations.releaseRetention(it) },
            evaluator = evaluator,
            leaseExpiry = { files.attachmentLeases.leaseExpiry(it) },
            canonicalUrl = { "file://" + File(files.filesDir(), it).absolutePath },
            createdAt = { files.getByRelativePath(it)?.createdAt },
            deleteFile = { files.deleteRetainedUpload(it) },
        )
    }

    private fun resumeMemoryConsolidation() {
        // 应用被切走后进程随时可能被冻结，等不到空闲计时结束，这时直接开始整理
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) get<MemoryConsolidationScheduler>().onAppHidden()
            }
        )
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<MemoryConsolidationScheduler>().resumePending()
            }.onFailure {
                Log.e(TAG, "resumeMemoryConsolidation failed", it)
            }
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                // Reuse the materialized settings flow instead of collecting settingsFlowRaw;
                // skip the initial dummy emission before DataStore has loaded.
                val settings = get<SettingsStore>().settingsFlow.first { !it.init }
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@RikkihubApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    if (Build.VERSION.SDK_INT >= 37 &&
                        !settings.webServerLocalhostOnly &&
                        ContextCompat.checkSelfPermission(
                            this@RikkihubApp,
                            android.Manifest.permission.ACCESS_LOCAL_NETWORK
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: local network permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@RikkihubApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)

        val mediaCreationChannel = NotificationChannelCompat
            .Builder(MEDIA_CREATION_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(getString(R.string.media_creation_title))
            .build()
        notificationManager.createNotificationChannel(mediaCreationChannel)

        val memoryDreamChannel = NotificationChannelCompat
            .Builder(MEMORY_DREAM_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.memory_dream_title))
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(memoryDreamChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
