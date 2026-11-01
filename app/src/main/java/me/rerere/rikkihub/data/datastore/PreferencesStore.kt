package me.rerere.rikkihub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ccdisguise.ClaudeCodeDisguiseConfig
import me.rerere.ai.claudelife.CacheKeepAliveConfig
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.data.ai.mcp.McpServerConfig
import me.rerere.rikkihub.data.ai.tools.ToolOverride
import me.rerere.rikkihub.data.ai.tools.ToolExecutionConfig
import me.rerere.rikkihub.data.ai.MemoryDreamConfig
import me.rerere.rikkihub.data.ai.polish.PromptPolishConfig
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoConfig
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoConfig
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.data.model.normalizedSystemPrompt
import me.rerere.rikkihub.data.model.reconcileSystemPrompt
import me.rerere.rikkihub.data.mutation.ExpectedVersions
import me.rerere.rikkihub.data.mutation.MutationActor
import me.rerere.rikkihub.data.mutation.MutationConflictException
import me.rerere.rikkihub.data.mutation.MutationDomain
import me.rerere.rikkihub.data.mutation.MutationOutcome
import me.rerere.rikkihub.data.mutation.MutationRuntime
import me.rerere.rikkihub.data.mutation.MutationFencedException
import me.rerere.rikkihub.data.mutation.VersionToken
import me.rerere.rikkihub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkihub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkihub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkihub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkihub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkihub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkihub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkihub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkihub.data.datastore.migration.PreferenceStoreV4Migration
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Avatar
import me.rerere.rikkihub.data.model.InjectionPosition
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.PromptInjection
import me.rerere.rikkihub.data.model.QuickMessage
import me.rerere.rikkihub.data.model.Tag
import me.rerere.rikkihub.data.sync.s3.S3Config
import me.rerere.rikkihub.ui.theme.CustomTheme
import me.rerere.rikkihub.ui.theme.PresetThemes
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid
import me.rerere.mediagen.provider.MediaGenerationProviderSetting

private const val TAG = "PreferencesStore"

private const val SETTINGS_STORE_NAME = "settings"

// 读取失败时的最大重试次数（重试不含首次读取，共最多 1+N 次）
internal const val SETTINGS_READ_MAX_RETRIES = 3

// 重试退避基数（毫秒）：第 i 次重试等待 base shl i，即 100/200/400ms
internal const val SETTINGS_READ_RETRY_BASE_DELAY_MS = 100L

@Volatile
private var settingsStoreFile: java.io.File? = null

private val Context.settingsStore by preferencesDataStore(
    name = SETTINGS_STORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { exception ->
        // 文件已损坏无法解析，先留一份原文件用于排查/抢救，再交给 DataStore 重建为空
        Log.e(TAG, "Settings datastore corrupted, resetting", exception)
        val file = settingsStoreFile
        if (file == null || backupCorruptedSettingsFile(file) == null) {
            Log.e(TAG, "Failed to backup corrupted settings file")
        }
        emptyPreferences()
    },
    produceMigrations = { context ->
        // 委托在 getValue 内先调 produceMigrations 再创建 DataStore，这里拿到的
        // 是 applicationContext；corruptionHandler 构造时还没有文件路径，只能
        // 借这里记录，损坏处理（发生在首次读取之后）时再使用。
        settingsStoreFile = context.preferencesDataStoreFile(SETTINGS_STORE_NAME)
        listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration(),
            PreferenceStoreV4Migration()
        )
    }
)

/**
 * 设置读取重试：仅对临时 IO 错误重试（有界，退避基数 [SETTINGS_READ_RETRY_BASE_DELAY_MS]
 * 的指数退避），重试耗尽后原异常继续向上抛出；绝不回退为空配置，否则默认值会被当成
 * 用户数据写回、覆盖全部设置。CorruptionException 走 corruptionHandler 不走重试；
 * 取消异常由 retryWhen 直接传播且不算一次失败。
 *
 * [delayMillis]/[report] 为可注入参数：生产调用方用默认实现（真实 delay + logcat），
 * 单元测试注入 no-op 以离线运行。
 */
internal fun Flow<Preferences>.withSettingsReadRetry(
    maxRetries: Int = SETTINGS_READ_MAX_RETRIES,
    baseDelayMs: Long = SETTINGS_READ_RETRY_BASE_DELAY_MS,
    delayMillis: suspend (Long) -> Unit = { delay(it) },
    report: (attempt: Long, cause: Throwable) -> Unit = { attempt, cause ->
        Log.w(TAG, "Failed to read settings, retrying (${attempt + 1}/$maxRetries)", cause)
    },
): Flow<Preferences> = retryWhen { cause, attempt ->
    val shouldRetry =
        cause is IOException && cause !is CorruptionException && attempt < maxRetries
    if (shouldRetry) {
        report(attempt, cause)
        delayMillis(baseDelayMs shl attempt.toInt())
    }
    shouldRetry
}

/**
 * DataStore 损坏重建前的抢救备份：把损坏文件原样复制为同目录
 * `<name>.corrupt-<timestampMillis>`。返回备份文件；失败返回 null（不抛异常，
 * 保证 handler 仍然返回空配置让 DataStore 重建）。
 */
internal fun backupCorruptedSettingsFile(
    file: java.io.File,
    timestampMillis: () -> Long = System::currentTimeMillis,
): java.io.File? {
    val parent = file.parentFile ?: return null
    val backup = java.io.File(parent, "${file.name}.corrupt-${timestampMillis()}")
    return runCatching {
        file.copyTo(backup)
        backup
    }.getOrNull()
}

class SettingsStore private constructor(
    private val dataStore: DataStore<Preferences>,
    private val mutationRuntime: MutationRuntime,
    scope: CoroutineScope,
    private val templateInvalidator: (() -> Unit)? = null,
) : KoinComponent {
    constructor(context: Context, scope: AppScope) : this(context.settingsStore, MutationRuntime.forContext(context), scope)
    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val NETWORK_SETTING = stringPreferencesKey("network_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val FAST_MODEL_REASONING_LEVEL = stringPreferencesKey("fast_model_reasoning_level")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val ENABLE_AUTO_TITLE = booleanPreferencesKey("enable_auto_title")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val TRANSLATE_TARGET_LANGUAGE = stringPreferencesKey("translate_target_language")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        // 2.22.0 起不再使用，只在写入时清掉旧值
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")
        val TOOL_OVERRIDES = stringPreferencesKey("tool_overrides")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val DELETED_ASSISTANT_IDS = stringPreferencesKey("deleted_assistant_ids")

        internal fun forTesting(dataStore: DataStore<Preferences>, runtime: MutationRuntime, scope: CoroutineScope) =
            SettingsStore(dataStore, runtime, scope, {})
        private val SETTINGS_MUTATION_LEDGER = stringPreferencesKey("settings_mutation_ledger")
        private const val RECEIPT_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_RETAINED_RECEIPTS = 4096
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")
        val UPLOAD_S3_CONFIG = stringPreferencesKey("upload_s3_config")

        // 媒体生成
        val MEDIA_GENERATION_PROVIDERS = stringPreferencesKey("media_generation_providers")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val DEFAULT_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")
        val WEB_SERVER_HTTPS_ENABLED = booleanPreferencesKey("web_server_https_enabled")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // CC伪装配置
        val CC_DISGUISE_CONFIG = stringPreferencesKey("cc_disguise_config")

        // 全局缓存保活配置（claudelife）
        val CACHE_KEEP_ALIVE_CONFIG = stringPreferencesKey("cache_keep_alive_config")
        val TOOL_EXECUTION_CONFIG = stringPreferencesKey("tool_execution_config")
        val TUIQIAO_CONFIG = stringPreferencesKey("tuiqiao_config")
        val ZAIZAO_CONFIG = stringPreferencesKey("zaizao_config")
        val PROMPT_POLISH_CONFIG = stringPreferencesKey("prompt_polish_config")
        val MEMORY_DREAM_CONFIG = stringPreferencesKey("memory_dream_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 赞助提醒
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")

        // Uses the same DataStore singleton without starting settings flows or requiring Koin.
        internal suspend fun restoreBeforeInitialization(context: Context, settings: Settings) {
            require(!settings.init) { "Cannot restore uninitialized settings" }
            persistSettings(context.settingsStore, settings)
        }

        private suspend fun persistSettings(
            dataStore: DataStore<Preferences>,
            settings: Settings,
            old: Settings? = null,
        ) {
            // 与上一份 Settings 比较，只写发生变化的 key；old == null 时全量写入
            // （首次写入 / restoreBeforeInitialization 全量恢复）。
            dataStore.edit { preferences -> persistSettingsIn(preferences, settings, old) }
        }

        private fun persistSettingsIn(
            preferences: MutablePreferences,
            settings: Settings,
            old: Settings? = null,
        ) {
                if (old == null || old.dynamicColor != settings.dynamicColor)
                    preferences[DYNAMIC_COLOR] = settings.dynamicColor
                if (old == null || old.themeId != settings.themeId)
                    preferences[THEME_ID] = settings.themeId
                if (old == null || old.customThemes != settings.customThemes)
                    preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
                if (old == null || old.developerMode != settings.developerMode)
                    preferences[DEVELOPER_MODE] = settings.developerMode
                if (old == null || old.displaySetting != settings.displaySetting)
                    preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)
                if (old == null || old.networkSetting != settings.networkSetting)
                    preferences[NETWORK_SETTING] = JsonInstant.encodeToString(settings.networkSetting)

                if (old == null || old.favoriteModels != settings.favoriteModels)
                    preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
                if (old == null || old.chatModelId != settings.chatModelId)
                    preferences[SELECT_MODEL] = settings.chatModelId.toString()
                if (old == null || old.fastModelId != settings.fastModelId)
                    preferences[FAST_MODEL] = settings.fastModelId.toString()
                if (old == null || old.fastModelReasoningLevel != settings.fastModelReasoningLevel)
                    preferences[FAST_MODEL_REASONING_LEVEL] = settings.fastModelReasoningLevel.name
                if (old == null || old.translateModeId != settings.translateModeId)
                    preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
                if (old == null || old.enableSuggestion != settings.enableSuggestion)
                    preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
                if (old == null || old.imageGenerationModelId != settings.imageGenerationModelId)
                    preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
                if (old == null || old.enableAutoTitle != settings.enableAutoTitle)
                    preferences[ENABLE_AUTO_TITLE] = settings.enableAutoTitle
                if (old == null || old.titlePrompt != settings.titlePrompt)
                    preferences[TITLE_PROMPT] = settings.titlePrompt
                if (old == null || old.translatePrompt != settings.translatePrompt)
                    preferences[TRANSLATION_PROMPT] = settings.translatePrompt
                if (old == null || old.translateThinkingBudget != settings.translateThinkingBudget)
                    preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
                if (old == null || old.translateTargetLanguage != settings.translateTargetLanguage)
                    preferences[TRANSLATE_TARGET_LANGUAGE] = settings.translateTargetLanguage
                if (old == null || old.suggestionPrompt != settings.suggestionPrompt)
                    preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
                if (old == null || old.ocrModelId != settings.ocrModelId)
                    preferences[OCR_MODEL] = settings.ocrModelId.toString()
                if (old == null || old.ocrPrompt != settings.ocrPrompt)
                    preferences[OCR_PROMPT] = settings.ocrPrompt
                // 压缩改为复用会话的聊天模型和固定的结构化指令，旧版的压缩模型 / 压缩提示词不再保存
                preferences.remove(COMPRESS_MODEL)
                preferences.remove(COMPRESS_PROMPT)
                if (old == null || old.toolOverrides != settings.toolOverrides)
                    preferences[TOOL_OVERRIDES] = JsonInstant.encodeToString(settings.toolOverrides)

                if (old == null || old.providers != settings.providers)
                    preferences[PROVIDERS] = JsonInstant.encodeToString(settings.providers)

                if (old == null || old.assistants != settings.assistants)
                    preferences[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
                if (old == null || old.deletedAssistantIds != settings.deletedAssistantIds)
                    preferences[DELETED_ASSISTANT_IDS] = JsonInstant.encodeToString(settings.deletedAssistantIds)
                if (old == null || old.assistantId != settings.assistantId)
                    preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
                if (old == null || old.assistantTags != settings.assistantTags)
                    preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)

                if (old == null || old.searchServices != settings.searchServices)
                    preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
                if (old == null || old.searchCommonOptions != settings.searchCommonOptions)
                    preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
                val searchSelected = settings.searchServiceSelected
                    .coerceIn(0, (settings.searchServices.size - 1).coerceAtLeast(0))
                val oldSearchSelected = old?.searchServiceSelected
                    ?.coerceIn(0, (old.searchServices.size - 1).coerceAtLeast(0))
                if (old == null || oldSearchSelected != searchSelected)
                    preferences[SEARCH_SELECTED] = searchSelected

                if (old == null || old.mcpServers != settings.mcpServers)
                    preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
                if (old == null || old.webDavConfig != settings.webDavConfig)
                    preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
                if (old == null || old.s3Config != settings.s3Config)
                    preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
                if (old == null || old.uploadS3Config != settings.uploadS3Config)
                    preferences[UPLOAD_S3_CONFIG] = JsonInstant.encodeToString(settings.uploadS3Config)
                if (old == null || old.mediaGenerationProviders != settings.mediaGenerationProviders)
                    preferences[MEDIA_GENERATION_PROVIDERS] = JsonInstant.encodeToString(settings.mediaGenerationProviders)
                if (old == null || old.ttsProviders != settings.ttsProviders)
                    preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
                if (old == null || old.selectedTTSProviderId != settings.selectedTTSProviderId)
                    settings.selectedTTSProviderId?.let {
                        preferences[SELECTED_TTS_PROVIDER] = it.toString()
                    } ?: preferences.remove(SELECTED_TTS_PROVIDER)
                val ttsSpeed = settings.defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f)
                val oldTtsSpeed = old?.defaultTTSPlaybackSpeed?.coerceIn(0.5f, 2.0f)
                if (old == null || oldTtsSpeed != ttsSpeed)
                    preferences[DEFAULT_TTS_PLAYBACK_SPEED] = ttsSpeed
                if (old == null || old.asrProviders != settings.asrProviders)
                    preferences[ASR_PROVIDERS] = JsonInstant.encodeToString(settings.asrProviders)
                if (old == null || old.selectedASRProviderId != settings.selectedASRProviderId)
                    settings.selectedASRProviderId?.let {
                        preferences[SELECTED_ASR_PROVIDER] = it.toString()
                    } ?: preferences.remove(SELECTED_ASR_PROVIDER)
                if (old == null || old.modeInjections != settings.modeInjections)
                    preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
                if (old == null || old.lorebooks != settings.lorebooks)
                    preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
                if (old == null || old.quickMessages != settings.quickMessages)
                    preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
                if (old == null || old.webServerEnabled != settings.webServerEnabled)
                    preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
                if (old == null || old.webServerPort != settings.webServerPort)
                    preferences[WEB_SERVER_PORT] = settings.webServerPort
                if (old == null || old.webServerJwtEnabled != settings.webServerJwtEnabled)
                    preferences[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
                if (old == null || old.webServerAccessPassword != settings.webServerAccessPassword)
                    preferences[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
                if (old == null || old.webServerLocalhostOnly != settings.webServerLocalhostOnly)
                    preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
                if (old == null || old.webServerHttpsEnabled != settings.webServerHttpsEnabled)
                    preferences[WEB_SERVER_HTTPS_ENABLED] = settings.webServerHttpsEnabled
                if (old == null || old.backupReminderConfig != settings.backupReminderConfig)
                    preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
                if (old == null || old.ccDisguiseConfig != settings.ccDisguiseConfig)
                    preferences[CC_DISGUISE_CONFIG] = JsonInstant.encodeToString(settings.ccDisguiseConfig)
                if (old == null || old.cacheKeepAliveConfig != settings.cacheKeepAliveConfig)
                    preferences[CACHE_KEEP_ALIVE_CONFIG] = JsonInstant.encodeToString(settings.cacheKeepAliveConfig)
                if (old == null || old.toolExecution != settings.toolExecution)
                    preferences[TOOL_EXECUTION_CONFIG] = JsonInstant.encodeToString(settings.toolExecution)
                if (old == null || old.tuiqiao != settings.tuiqiao)
                    preferences[TUIQIAO_CONFIG] = JsonInstant.encodeToString(settings.tuiqiao)
                if (old == null || old.zaizao != settings.zaizao)
                    preferences[ZAIZAO_CONFIG] = JsonInstant.encodeToString(settings.zaizao)
                if (old == null || old.promptPolish != settings.promptPolish)
                    preferences[PROMPT_POLISH_CONFIG] = JsonInstant.encodeToString(settings.promptPolish)
                if (old == null || old.memoryDream != settings.memoryDream)
                    preferences[MEMORY_DREAM_CONFIG] = JsonInstant.encodeToString(settings.memoryDream)
                if (old == null || old.sponsorAlertDismissedAt != settings.sponsorAlertDismissedAt)
                    preferences[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
        }
    }

    private val updateMutex = Mutex()
    private val initializeMutex = Mutex()
    @Volatile private var initialized = false

    // No consumer observes an unnormalized/unversioned legacy snapshot.
    val settingsFlowRaw = flow {
        ensureInitialized()
        emitAll(
            dataStore.data.withSettingsReadRetry()
                // 启动次数不属于 Settings，只有它变化时不重新解码整份设置
                .distinctUntilChanged { old, new -> old.asMap() - LAUNCH_COUNT == new.asMap() - LAUNCH_COUNT }
                .map { stamped(it) }
        )
    }.flowOn(Dispatchers.Default)

    // 启动次数每次启动都变，单独成流：放在 Settings 里会让刚启动时所有读设置的界面整页重组一遍
    val launchCountFlow: Flow<Int> = dataStore.data
        .map { preferences -> preferences[LAUNCH_COUNT] ?: 0 }
        .distinctUntilChanged()

    val settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(Settings.dummy())

    // Exact published snapshots by (epoch, revision): legacy whole-snapshot callers
    // are merged against the snapshot they actually read, never the latest guess.
    private val publishedSnapshots = object : LinkedHashMap<Pair<Long, Long>, Settings>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Long, Long>, Settings>?) = size > 64
    }

    private fun remember(settings: Settings): Settings {
        if (!settings.init && settings.readRevision >= 0) synchronized(publishedSnapshots) {
            publishedSnapshots[settings.readEpoch to settings.readRevision] = settings
        }
        return settings
    }

    private fun publishedBase(settings: Settings): Settings? = synchronized(publishedSnapshots) {
        publishedSnapshots[settings.readEpoch to settings.readRevision]
    }

    /** Fire-and-forget UI writes report conflicts/fences here instead of crashing the caller. */
    val writeFailures = kotlinx.coroutines.flow.MutableSharedFlow<SettingsWriteFailure>(extraBufferCapacity = 16)

    init {
        // 整份设置的比较（StateFlow 判等）和模板缓存的失效判断都不放在主线程
        scope.launch(Dispatchers.Default) {
            settingsFlowRaw.collect { next ->
                updateMutex.withLock {
                    val current = settingsFlow.value
                    if (current.init || next.readEpoch != current.readEpoch || next.readRevision >= current.readRevision) {
                        settingsFlow.value = remember(next)
                    }
                }
            }
        }
        // Pebble 模板缓存只在模板相关字段变化时失效：
        // 助手 messageTemplate / modeInjections / lorebooks
        scope.launch(Dispatchers.Default) {
            settingsFlow
                .distinctUntilChangedBy { settings ->
                    Triple(
                        settings.assistants.map { it.id to it.messageTemplate },
                        settings.modeInjections,
                        settings.lorebooks,
                    )
                }
                .collect {
                    if (templateInvalidator != null) templateInvalidator.invoke()
                    else get<PebbleEngine>().templateCache.invalidateAll()
                }
        }
    }

    /** Normalization and legacy baseline publication happen before any consumer. */
    private suspend fun ensureInitialized() {
        if (initialized) { mutationRuntime.ensureReady(); return }
        initializeMutex.withLock {
            if (initialized) return@withLock
            mutationRuntime.ensureReady()
            dataStore.edit { preferences ->
                // A corruption handler may have created a restore marker during this read.
                mutationRuntime.ensureReady()
                val raw = preferences.decodeSettings()
                val normalized = raw.withDefaultBackfill().normalizedForPersistence()
                val ledger = readLedger(preferences).seed(raw)
                val next = if (raw != normalized) ledger.changed(raw, normalized) else ledger
                // Full write once: decode defaults for absent keys include random
                // model-slot UUIDs, which must become durable so every later read of
                // the same revision is identical (field CAS compares exact snapshots).
                persistSettingsIn(preferences, normalized, null)
                preferences[SETTINGS_MUTATION_LEDGER] = JsonInstant.encodeToString(next.seed(normalized))
            }
            initialized = true
        }
    }

    private fun readLedger(preferences: Preferences): SettingsLedger =
        preferences[SETTINGS_MUTATION_LEDGER]?.let { JsonInstant.decodeFromString<SettingsLedger>(it) }
            ?: SettingsLedger()

    private suspend fun stamped(preferences: Preferences): Settings = preferences.decodeSettings()
        .withDefaultBackfill().normalizedForPersistence().copy(
            readRevision = readLedger(preferences).commitRevision,
            readEpoch = mutationRuntime.epochs.epoch(MutationDomain.SETTINGS),
        )

    suspend fun settingsSnapshot(): Settings {
        ensureInitialized()
        return remember(stamped(dataStore.data.first()))
    }

    internal suspend fun atomicReadSnapshot(): SettingsReadSnapshot {
        ensureInitialized()
        return updateMutex.withLock {
            val preferences = dataStore.data.first()
            val ledger = readLedger(preferences)
            val epoch = mutationRuntime.epochs.epoch(MutationDomain.SETTINGS)
            SettingsReadSnapshot(stamped(preferences), epoch,
                ledger.objects.keys.mapNotNull { key -> ledger.token(key, epoch)?.let { key to it } }.toMap(),
                ledger.collections)
        }
    }

    /**
     * The receipt is committed INSIDE the DataStore edit. A lost reply or a crash
     * after the edit cannot re-apply the operation. Fingerprints bind stable IDs
     * to their exact typed operations; ordinary edits use a fresh operation ID.
     */
    suspend fun mutate(
        operationId: String = Uuid.random().toString(),
        actor: MutationActor = MutationActor.APP,
        expected: ExpectedVersions = ExpectedVersions.EMPTY,
        requestFingerprint: String = "",
        block: (Settings) -> Settings,
    ): MutationOutcome {
        require(operationId.isNotBlank() && operationId.length <= 512)
        ensureInitialized()
        var outcome = MutationOutcome.APPLIED
        updateMutex.withLock {
            var publication: Settings? = null
            dataStore.edit { preferences ->
                mutationRuntime.ensureReady()
                val epoch = mutationRuntime.epochs.epoch(MutationDomain.SETTINGS)
                val ledger = readLedger(preferences)
                ledger.receipts[operationId]?.let { previous ->
                    if (previous.epoch != epoch || previous.requestFingerprint != requestFingerprint) {
                        throw MutationConflictException("operation identity or storage epoch changed")
                    }
                    outcome = MutationOutcome.REPLAYED
                    return@edit
                }
                ledger.assertExpected(epoch, expected)
                val base = preferences.decodeSettings().withDefaultBackfill().normalizedForPersistence()
                    .copy(readRevision = ledger.commitRevision, readEpoch = epoch)
                val proposed = block(base).let { next ->
                    // 整块与分块是同一份提示词：按写入前的同一助手把两者对齐（App/Web/创造助手/导入共用）
                    val before = base.assistants.associateBy { it.id }
                    next.copy(assistants = next.assistants.map { it.reconcileSystemPrompt(before[it.id]) })
                }
                require(!proposed.init) { "Cannot update dummy settings" }
                require(proposed.toolOverrides.values.none { it.name?.startsWith("creator_") == true }) {
                    "Reserved tool namespace is unavailable"
                }
                val beforeIds = base.assistants.map { it.id }.toSet()
                val afterIds = proposed.assistants.map { it.id }.toSet()
                val deleted = (base.deletedAssistantIds + (beforeIds - afterIds) + proposed.deletedAssistantIds) - afterIds
                val next = proposed.copy(deletedAssistantIds = deleted - CreatorAssistantDefinition.ID)
                    .withCreatorAssistant().normalizedForPersistence().copy(readRevision = -1, readEpoch = -1)
                val cleanBase = base.copy(readRevision = -1, readEpoch = -1)
                val changedKeys = (settingsObjects(cleanBase).keys + settingsObjects(next).keys)
                    .filter { settingsObjects(cleanBase)[it] != settingsObjects(next)[it] }.toSet()
                mutationRuntime.checkWriteAdmission(MutationDomain.SETTINGS, changedKeys, operationId, actor)
                val nextLedger = ledger.changed(cleanBase, next)
                val afterTokens = changedKeys.mapNotNull { key ->
                    nextLedger.token(key, epoch)?.let { key to it.encode() }
                }.toMap()
                val oldCollections = settingsCollections(cleanBase)
                val changedCollections = settingsCollections(next).keys.filter { key ->
                    oldCollections[key] != settingsCollections(next)[key]
                }.associateWith { nextLedger.collections[it] ?: 0L }
                val committed = SettingsOperationReceipt(operationId, requestFingerprint,
                    System.currentTimeMillis(), epoch, afterTokens, changedCollections)
                persistSettingsIn(preferences, next, cleanBase)
                preferences[SETTINGS_MUTATION_LEDGER] = JsonInstant.encodeToString(
                    nextLedger.copy(receipts = retainedReceipts(ledger.receipts, committed, actor, requestFingerprint)))
                publication = next.copy(readRevision = nextLedger.commitRevision, readEpoch = epoch)
            }
            // Publish before releasing the writer lock. No older writer can
            // overtake a newer commit and install its obsolete snapshot.
            publication?.let { settingsFlow.value = remember(it) }
        }
        return outcome
    }

    suspend fun currentSettingsToken(objectKey: String): VersionToken? {
        ensureInitialized()
        return readLedger(dataStore.data.first()).token(objectKey,
            mutationRuntime.epochs.epoch(MutationDomain.SETTINGS))
    }

    suspend fun currentCollectionRevision(key: String): Long {
        ensureInitialized()
        return readLedger(dataStore.data.first()).collections[key] ?: 0L
    }

    suspend fun settingsEpoch(): Long {
        ensureInitialized()
        return mutationRuntime.epochs.epoch(MutationDomain.SETTINGS)
    }

    internal suspend fun receiptFor(operationId: String): SettingsOperationReceipt? {
        ensureInitialized()
        return readLedger(dataStore.data.first()).receipts[operationId]
    }

    /**
     * Compatibility snapshot writes: field-scoped CAS against the exact snapshot the
     * caller read ([SettingsFieldMerge]). An unknown/evicted base is a conflict.
     */
    suspend fun update(settings: Settings) {
        if (settings.init) return
        val base = publishedBase(settings)
            ?: throw MutationConflictException("Settings changed; reload before saving")
        mutate { current ->
            if (current.readEpoch != base.readEpoch) throw MutationConflictException("Settings storage was replaced")
            SettingsFieldMerge.merge(base, settings, current)
        }
    }

    /** Non-throwing variant for UI callers; failures are published on [writeFailures]. */
    suspend fun tryUpdate(settings: Settings): Boolean = reportFailures { update(settings) }

    /** Non-throwing functional variant for UI callers. */
    suspend fun tryUpdate(fn: (Settings) -> Settings): Boolean = reportFailures { update(fn) }

    private suspend fun reportFailures(block: suspend () -> Unit): Boolean = try {
        block(); true
    } catch (e: CancellationException) {
        // CancellationException is an IllegalStateException: not a write failure, propagate it
        throw e
    } catch (e: MutationConflictException) {
        writeFailures.tryEmit(SettingsWriteFailure.CONFLICT); false
    } catch (e: MutationFencedException) {
        writeFailures.tryEmit(SettingsWriteFailure.BUSY); false
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Settings write rejected", e)
        writeFailures.tryEmit(SettingsWriteFailure.REJECTED); false
    } catch (e: IllegalStateException) {
        Log.w(TAG, "Settings write unavailable", e)
        writeFailures.tryEmit(SettingsWriteFailure.UNAVAILABLE); false
    }

    /** Functional edits run against the latest durable state, including before Flow startup. */
    suspend fun update(fn: (Settings) -> Settings) { mutate(block = fn) }

    /** Closed import/restore path: replace the storage epoch, invalidate all old baselines. */
    suspend fun replaceForImport(settings: Settings) {
        require(!settings.init)
        mutationRuntime.ensureReady()
        mutationRuntime.epochs.bumpEpoch(MutationDomain.SETTINGS)
        mutate(actor = MutationActor.MAINTENANCE) { settings }
    }

    /**
     * Only idempotent writers (management steps or callers that bind a request
     * fingerprint) need a durable replay receipt. Ordinary UI edits use a fresh
     * random operation ID and never replay, so they are not retained; otherwise the
     * ledger would grow with every edit inside one Preferences value.
     */
    private fun retainedReceipts(
        receipts: Map<String, SettingsOperationReceipt>,
        committed: SettingsOperationReceipt,
        actor: MutationActor,
        requestFingerprint: String,
    ): Map<String, SettingsOperationReceipt> {
        val retained = receipts.filterValues { committed.appliedAtMillis - it.appliedAtMillis < RECEIPT_RETENTION_MILLIS }
        if (actor != MutationActor.MANAGEMENT && requestFingerprint.isEmpty()) return retained
        check(retained.size < MAX_RETAINED_RECEIPTS) { "Settings receipt capacity reached; maintenance required" }
        return retained + (committed.operationId to committed)
    }

    private fun Preferences.decodeSettings(): Settings {
        val preferences = this
        return Settings(
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelId = preferences[FAST_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelReasoningLevel = preferences[FAST_MODEL_REASONING_LEVEL]
                    ?.let { value -> ReasoningLevel.entries.find { it.name == value } }
                    ?: ReasoningLevel.AUTO,
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                enableAutoTitle = preferences[ENABLE_AUTO_TITLE] != false,
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                translateTargetLanguage = preferences[TRANSLATE_TARGET_LANGUAGE] ?: DEFAULT_TRANSLATE_TARGET_LANGUAGE,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                toolOverrides = preferences[TOOL_OVERRIDES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyMap(),
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                deletedAssistantIds = preferences[DELETED_ASSISTANT_IDS]?.let {
                    JsonInstant.decodeFromString<Set<Uuid>>(it)
                } ?: emptySet(),
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providers = JsonInstant.decodeFromString(preferences[PROVIDERS] ?: "[]"),
                assistants = JsonInstant.decodeFromString(preferences[ASSISTANTS] ?: "[]"),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = JsonInstant.decodeFromString(preferences[DISPLAY_SETTING] ?: "{}"),
                networkSetting = JsonInstant.decodeFromString(preferences[NETWORK_SETTING] ?: "{}"),
                searchServices = preferences[SEARCH_SERVICES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: S3Config(),
                uploadS3Config = preferences[UPLOAD_S3_CONFIG]?.let {
                    runCatching { JsonInstant.decodeFromString<S3Config>(it) }.getOrNull()
                } ?: S3Config(),
                mediaGenerationProviders = preferences[MEDIA_GENERATION_PROVIDERS]?.let {
                    runCatching { JsonInstant.decodeFromString<List<MediaGenerationProviderSetting>>(it) }.getOrNull()
                } ?: emptyList(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                defaultTTSPlaybackSpeed = preferences[DEFAULT_TTS_PLAYBACK_SPEED]?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                asrProviders = preferences[ASR_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { Uuid.parse(it) },
                // 新安装时键还不存在：退回内置默认（Learning Mode），不是空列表
                modeInjections = preferences[MODE_INJECTIONS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: DEFAULT_MODE_INJECTIONS,
                lorebooks = preferences[LOREBOOKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                quickMessages = preferences[QUICK_MESSAGES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
                webServerHttpsEnabled = preferences[WEB_SERVER_HTTPS_ENABLED] == true,
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
                ccDisguiseConfig = preferences[CC_DISGUISE_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: ClaudeCodeDisguiseConfig(),
                cacheKeepAliveConfig = preferences[CACHE_KEEP_ALIVE_CONFIG]?.let {
                    runCatching { JsonInstant.decodeFromString<CacheKeepAliveConfig>(it) }.getOrNull()
                } ?: CacheKeepAliveConfig(),
                toolExecution = preferences[TOOL_EXECUTION_CONFIG]?.let {
                    runCatching { JsonInstant.decodeFromString<ToolExecutionConfig>(it) }.getOrNull()
                } ?: ToolExecutionConfig(),
                tuiqiao = preferences[TUIQIAO_CONFIG]?.let {
                    runCatching { JsonInstant.decodeFromString<TuiqiaoConfig>(it) }.getOrNull()
                } ?: TuiqiaoConfig(),
                zaizao = preferences[ZAIZAO_CONFIG]?.let {
                    runCatching { JsonInstant.decodeFromString<ZaizaoConfig>(it) }.getOrNull()
                } ?: ZaizaoConfig(),
                promptPolish = preferences[PROMPT_POLISH_CONFIG]?.let {
                    runCatching { JsonInstant.decodeFromString<PromptPolishConfig>(it) }.getOrNull()
                } ?: PromptPolishConfig(),
                memoryDream = preferences[MEMORY_DREAM_CONFIG]?.let {
                    runCatching { JsonInstant.decodeFromString<MemoryDreamConfig>(it) }.getOrNull()
                } ?: MemoryDreamConfig(),
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
            )
    }

    private fun Settings.withDefaultBackfill(): Settings {
        val it = this
            var providers = it.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
            DEFAULT_PROVIDERS.forEach { defaultProvider ->
                if (providers.none { it.id == defaultProvider.id }) {
                    providers.add(defaultProvider.copyProvider())
                }
            }
            providers = providers.map { provider ->
                val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                if (defaultProvider != null) {
                    provider.copyProvider(
                        builtIn = defaultProvider.builtIn,
                        description = defaultProvider.description,
                        shortDescription = defaultProvider.shortDescription,
                    )
                } else provider
            }.toMutableList()
            val assistants = it.assistants.toMutableList()
            DEFAULT_ASSISTANTS.filterNot { default -> default.id in this.deletedAssistantIds }.forEach { defaultAssistant ->
                if (assistants.none { it.id == defaultAssistant.id }) {
                    assistants.add(defaultAssistant.copy())
                }
            }
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            return it.copy(
                providers = providers,
                assistants = assistants,
                ttsProviders = ttsProviders,
            ).withCreatorAssistant()
    }

    /**
     * 创造助手不可删除：缺失即补回列表首位（不看 deletedAssistantIds）。
     * 系统提示词仍是某个旧版默认文本（用户没改过）时换成当前默认；改过的保持不动。
     */
    private fun Settings.withCreatorAssistant(): Settings =
        if (assistants.any { CreatorAssistantDefinition.isCreator(it.id) }) {
            val upgraded = assistants.map { assistant ->
                // 拆成分块后整块由分块合并而来，不再按旧默认文本整体替换
                if (!CreatorAssistantDefinition.isCreator(assistant.id) || assistant.promptSections.isNotEmpty()) assistant
                else assistant.copy(systemPrompt = CreatorAssistantDefinition.upgradedSystemPrompt(assistant.systemPrompt))
            }
            if (upgraded == assistants) this else copy(assistants = upgraded)
        }
        else copy(assistants = listOf(CreatorAssistantDefinition.defaultAssistant()) + assistants)

    private fun Settings.normalizedForPersistence(): Settings {
        val settings = this
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            val asrProviders = settings.asrProviders.distinctBy { it.id }
            return settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Codex -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Devin -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Antigravity -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }
                },
                assistants = settings.assistants
                    .distinctBy { it.id }.map { it.normalizedSystemPrompt() }.map { assistant ->
                    assistant.copy(
                        // 创造助手不能绑定工作区（App/Web/导入/恢复统一兜底）
                        workspaceId = if (CreatorAssistantDefinition.isCreator(assistant.id)) null else assistant.workspaceId,
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                        // 过滤掉不存在的快捷消息 ID
                        quickMessageIds = assistant.quickMessageIds.filter { id ->
                            id in validQuickMessageIds
                        }.toSet()
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                asrProviders = asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId
                    ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                    ?: asrProviders.firstOrNull()?.id,
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                modeInjections = settings.modeInjections.distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
            )
        }
    /** 启动次数直写单个 key，不经过 Settings 的字段级 CAS，也不升设置 revision */
    suspend fun incrementLaunchCount() {
        dataStore.edit { preferences -> preferences[LAUNCH_COUNT] = (preferences[LAUNCH_COUNT] ?: 0) + 1 }
    }

    suspend fun setLaunchCount(count: Int) {
        dataStore.edit { preferences -> preferences[LAUNCH_COUNT] = count }
    }

    suspend fun updateSponsorAlertDismissedAt(value: Int) { update { it.copy(sponsorAlertDismissedAt = value) } }

    suspend fun updateAssistant(assistantId: Uuid) {
        update { current ->
            require(current.assistants.any { it.id == assistantId }) { "Unknown assistant" }
            current.copy(assistantId = assistantId)
        }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableWebSearch = enabled)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                            quickMessageIds = quickMessageIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }
}

enum class SettingsWriteFailure { CONFLICT, BUSY, REJECTED, UNAVAILABLE }

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val networkSetting: NetworkSetting = NetworkSetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val fastModelReasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val enableAutoTitle: Boolean = true,
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    // App 翻译页的目标语言（BCP 47 语言标签）
    val translateTargetLanguage: String = DEFAULT_TRANSLATE_TARGET_LANGUAGE,
    val enableSuggestion: Boolean = true,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val toolOverrides: Map<String, ToolOverride> = emptyMap(),
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val deletedAssistantIds: Set<Uuid> = emptySet(),
    @Transient val readRevision: Long = -1,
    @Transient val readEpoch: Long = -1,
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    // 媒体创作上传临时素材用的桶，和备份的 s3Config 互不影响；items 字段在这里不使用
    val uploadS3Config: S3Config = S3Config(),
    val mediaGenerationProviders: List<MediaGenerationProviderSetting> = emptyList(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    /** Off by default (intranet use): plain HTTP. On: TLS with the app's local CA. */
    val webServerHttpsEnabled: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val ccDisguiseConfig: ClaudeCodeDisguiseConfig = ClaudeCodeDisguiseConfig(),
    // 全局缓存保活配置（claudelife）：默认关闭、270 秒、30 分钟
    val cacheKeepAliveConfig: CacheKeepAliveConfig = CacheKeepAliveConfig(),
    /** 工具调用并行执行（总开关、上限、逐工具覆盖）；独立于 toolOverrides。 */
    val toolExecution: ToolExecutionConfig = ToolExecutionConfig(),
    /** 推敲：候选数量、解释长度、三档提示词。 */
    val tuiqiao: TuiqiaoConfig = TuiqiaoConfig(),
    /** 再造：模型与推理参数、后文开关、默认类型、三种类型的提示词、最近用过的要求。 */
    val zaizao: ZaizaoConfig = ZaizaoConfig(),
    /** 聊天框润色：模型、提示词、是否带对话上下文及条数。 */
    val promptPolish: PromptPolishConfig = PromptPolishConfig(),
    /** 记忆库「做梦」（后台整理）：自动开关、触发时机、模型、步数与提示词。 */
    val memoryDream: MemoryDreamConfig = MemoryDreamConfig(),
    val sponsorAlertDismissedAt: Int = 0,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
data class NetworkSetting(
    val userAgent: String = "",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val enableAutoRetry: Boolean = true,
)

const val DEFAULT_TRANSLATE_TARGET_LANGUAGE = "zh-CN"

@Serializable
enum class ConversationSortOrder {
    @SerialName("update_time")
    UPDATE_TIME,

    @SerialName("create_time")
    CREATE_TIME,
}

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    // Downloadable fonts (FontDownloadService); the system font until downloaded.
    @SerialName("harmonyos_sans_sc")
    HARMONYOS_SANS_SC,
    @SerialName("lxgw_wenkai_screen")
    LXGW_WENKAI_SCREEN,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val updateCheckDisabledUntilEpochMillis: Long = 0L,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    // Workspace Markdown source stays visible; changing semantic font sizes is opt-in.
    val workspaceMarkdownHeadingSizes: Boolean = false,
    // Workspace editor top toolbar: one scrolling row, or rows of workspaceEditorToolbarPerRow equal-width
    // buttons (the name is from when that meant two rows), and the button order by stable id
    // (WorkspaceEditorToolbarItem.id; empty = default order, missing ids appended).
    val workspaceEditorToolbarDoubleRow: Boolean = false,
    // Buttons per row in multi-row mode; read through WorkspaceEditorToolbar's clamp (3..15).
    val workspaceEditorToolbarPerRow: Int = 8,
    // The toolbar (with the find bar) below the text, next to the keyboard, instead of above it.
    val workspaceEditorToolbarAtBottom: Boolean = false,
    val workspaceEditorToolbarOrder: List<String> = emptyList(),
    // Toolbar row height by WorkspaceEditorToolbarSize.id ("standard" / "compact" / "mini"); unknown ids read as mini.
    val workspaceEditorToolbarSize: String = "mini",
    // Workspace editor: typing an opening symbol adds its closer and a fill box. One switch per editor, like
    // the typography: phone and desktop input methods differ (some close brackets themselves).
    val workspaceEditorAutoPair: Boolean = true,
    val webWorkspaceEditorAutoPair: Boolean = true,
    // Workspace editor font/size/spacing: one for the App (sp), one for the Web (px).
    // Read them through appEditorTypography / webEditorTypography (clamped).
    val workspaceEditorTypography: EditorTypography = EditorTypography.APP_DEFAULT,
    val webWorkspaceEditorTypography: EditorTypography = EditorTypography.WEB_DEFAULT,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    // 语音模式：模型回复期间麦克风是否继续开着（关闭后一问一答）
    val voiceModeListenWhileReplying: Boolean = true,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    // 用主题色标出正文里引号括起来的文字（App 聊天消息）
    val enableQuoteHighlight: Boolean = false,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
    // 抽屉会话列表的排序依据，只影响 App 抽屉；Web 与历史页仍按更新时间
    val conversationSortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkihub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId }
        // 选中的助手不存在时优先回落到普通助手，而不是排在首位的创造助手
        ?: this.assistants.firstOrNull { !CreatorAssistantDefinition.isCreator(it.id) }
        ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return selectedTTSProviderId?.let { id ->
        ttsProviders.find { it.id == id }
    } ?: ttsProviders.firstOrNull()
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = ""
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Date: {{cur_date}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent()
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
