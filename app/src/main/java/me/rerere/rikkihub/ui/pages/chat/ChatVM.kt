package me.rerere.rikkihub.ui.pages.chat

import me.rerere.rikkihub.data.ai.polish.PromptPolishHandler

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.claudelife.CacheWarmStatus
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.getCurrentAssistant
import me.rerere.rikkihub.data.datastore.getCurrentChatModel
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Avatar
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.MessageNode
import me.rerere.rikkihub.data.model.NodeFavoriteTarget
import me.rerere.rikkihub.data.ai.tavern.ActiveCharacter
import me.rerere.rikkihub.data.repository.CharacterRepository
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.FavoriteRepository
import me.rerere.rikkihub.service.ChatError
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.service.BUSY_REASON_COMPRESSING
import me.rerere.rikkihub.service.CompressOrigin
import me.rerere.rikkihub.service.CompressRequest
import me.rerere.rikkihub.service.CompressState
import me.rerere.rikkihub.service.CompressSuggestion
import me.rerere.rikkihub.data.ai.ConversationCompressionOverride
import me.rerere.rikkihub.data.mutation.ConversationBusyException
import me.rerere.rikkihub.ui.hooks.writeStringPreference
import me.rerere.rikkihub.ui.hooks.ChatInputState
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    /** 新会话要聊的角色；会话已经存在时不起作用。 */
    private val characterId: Uuid? = null,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    private val analytics: FirebaseAnalytics,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
    private val promptPolishHandler: PromptPolishHandler,
    private val characterRepository: CharacterRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    private val _promptPolishErrors = MutableSharedFlow<Throwable>(extraBufferCapacity = 4)
    val promptPolishErrors: SharedFlow<Throwable> = _promptPolishErrors.asSharedFlow()

    /** 输入框润色：用设置里的润色模型改写输入框文字（只是文字部分），可撤销。 */
    val promptPolisher = ChatInputPolisher(
        scope = viewModelScope,
        polish = { text ->
            promptPolishHandler.polish(
                settings = settingsStore.settingsFlow.value,
                input = text,
                context = conversation.value.currentMessages,
                conversationId = _conversationId,
            )
        },
        read = { inputState.textContent.text.toString() },
        write = { inputState.setMessageText(it) },
        onError = { _promptPolishErrors.tryEmit(it) },
    )

    val voiceSession = VoiceSessionController(viewModelScope, context::getString) {
        chatService.enqueueVoiceMessage(_conversationId, it)
    }

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    /** 缓存保活状态：输入框提示区域显示。 */
    val cacheWarmStatus: StateFlow<CacheWarmStatus> = chatService
        .observeCacheWarmStatus(_conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CacheWarmStatus.Inactive())

    /** 与 [CacheWarmStatus.Scheduled.nextWarmAtMs] 同一单调时钟。 */
    fun cacheWarmNowMs(): Long = chatService.cacheWarmNowMs()

    /** 加号抽屉开关：只切换全局缓存保活启停，走定向更新避免整包覆盖竞态。 */
    fun setCacheKeepAliveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.tryUpdate { settings ->
                settings.copy(cacheKeepAliveConfig = settings.cacheKeepAliveConfig.copy(enabled = enabled))
            }
        }
    }

    /** 加号抽屉开关：去八股总开关（所有助手、所有会话），只改这一个字段。 */
    fun setQubaguEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.tryUpdate { settings -> settings.copy(qubagu = settings.qubagu.copy(enabled = enabled)) }
        }
    }

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId, characterId)
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        voiceSession.stop()
        promptPolisher.cancel()
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    /** 这个会话聊的角色（含生效的角色书、卡内正则是否放行）；角色或设置变了就重新解析。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeCharacter: StateFlow<ActiveCharacter?> = combine(
        conversation.map { it.characterId }.distinctUntilChanged(),
        settingsStore.settingsFlow,
        characterRepository.observeSummaries(),
    ) { id, s, _ -> id to s }
        .mapLatest { (id, s) -> ActiveCharacter.resolve(characterRepository, s, id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // 网络搜索(每个助手独立)
    val enableWebSearch = settings.map {
        it.getCurrentAssistant().enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    val messageQueue = chatService.getMessageQueueFlow(_conversationId)

    fun removeQueuedMessage(id: Uuid) = chatService.removeQueuedMessage(_conversationId, id)

    fun beginEditQueuedMessage(id: Uuid) = chatService.beginEditQueuedMessage(_conversationId, id)

    fun finishEditQueuedMessage(id: Uuid, parts: List<UIMessagePart>?) =
        chatService.finishEditQueuedMessage(_conversationId, id, parts)

    fun resumeMessageQueue() = chatService.resumeMessageQueue(_conversationId)

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置：在最新的设置上修改，不整份写回界面手里的快照
    fun updateSettings(transform: (Settings) -> Settings): Job {
        return viewModelScope.launch {
            var change: Pair<Settings, Settings>? = null
            val written = settingsStore.tryUpdate { latest -> transform(latest).also { change = latest to it } }
            // 检查用户头像是否有变化，如果有则删除旧头像（写入成功后才删）
            change?.takeIf { written }?.let { (oldSettings, newSettings) -> checkUserAvatarDelete(oldSettings, newSettings) }
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 新会话开始前归入抽屉里选中的文件夹
    suspend fun updateDraftFolder(folderId: Uuid?) {
        chatService.updateDraftConversationFolder(_conversationId, folderId)
    }

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.tryUpdate { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        analytics.logEvent("ai_send_message", null)

        chatService.sendMessage(_conversationId, content, answer)
    }

    fun handleMessageEdit(
        parts: List<UIMessagePart>,
        messageId: Uuid,
        revokeAffectedCheckpoints: Boolean = false,
    ) {
        if (parts.isEmptyInputMessage()) return
        analytics.logEvent("ai_edit_message", null)

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts, revokeAffectedCheckpoints)
        }
    }

    /** Background compression shared with the web; survives leaving this page. */
    val compressState: StateFlow<CompressState?> = chatService.compressStateFlow(_conversationId)

    val compressSuggestion: StateFlow<CompressSuggestion?> = chatService.compressSuggestionFlow(_conversationId)

    fun startCompression(request: CompressRequest) {
        // 「先看再采用」的开关记住上次的选择
        if (settings.value.compression.reviewBeforeApply != request.review) {
            updateSettings { it.copy(compression = it.compression.copy(reviewBeforeApply = request.review)) }
        }
        launchCompression { chatService.startCompression(_conversationId, request, CompressOrigin.APP) }
    }

    fun compressAndRetry(conversationId: Uuid) {
        launchCompression { chatService.compressAndRetry(conversationId, CompressOrigin.APP) }
    }

    private fun launchCompression(start: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chatService.addError(
                    compressionStartError(e),
                    conversationId = _conversationId,
                    title = context.getString(R.string.error_title_compress_conversation),
                )
            }
        }
    }

    fun resolveCompressionReview(summary: String?) {
        chatService.resolveCompressionReview(_conversationId, summary)
    }

    fun dismissCompressSuggestion() {
        chatService.dismissCompressSuggestion(_conversationId)
    }

    fun updateCompressionOverride(override: ConversationCompressionOverride?) {
        viewModelScope.launch { chatService.updateConversationCompression(_conversationId, override) }
    }

    fun cancelCompression() {
        chatService.cancelCompression(_conversationId)
    }

    private fun compressionStartError(error: Exception): Throwable = when (error) {
        is ConversationBusyException -> IllegalStateException(context.getString(R.string.chat_page_compress_busy))
        else -> error
    }

    /** Regenerate/approval are refused while compressing; show it instead of crashing the caller. */
    private inline fun runUnlessCompressing(block: () -> Unit) {
        try {
            block()
        } catch (e: ConversationBusyException) {
            chatService.addError(
                IllegalStateException(
                    if (BUSY_REASON_COMPRESSING in e.reasons) context.getString(R.string.chat_page_compress_wait)
                    else context.getString(R.string.chat_page_compress_busy)
                ),
                conversationId = _conversationId,
                title = context.getString(R.string.error_title_operation),
            )
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage, revokeAffectedCheckpoints: Boolean = false) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message, revokeAffectedCheckpoints)
        }
    }

    fun clearContext(onSuccess: () -> Unit = {}) {
        launchCompression {
            chatService.clearContext(_conversationId)
            onSuccess()
        }
    }

    fun revokeCheckpoints(
        checkpointNodeId: Uuid,
        cascadeLater: Boolean,
        onSuccess: () -> Unit = {},
    ) {
        launchCompression {
            chatService.revokeCheckpoints(_conversationId, checkpointNodeId, cascadeLater)
            onSuccess()
        }
    }

    suspend fun loadContextUsageBreakdown(draftParts: List<UIMessagePart>) =
        chatService.contextUsageBreakdown(_conversationId, draftParts)

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException(context.getString(R.string.chat_delete_blocked_while_generating)),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        analytics.logEvent("ai_regenerate_at_message", null)
        runUnlessCompressing { chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg) }
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        analytics.logEvent("ai_tool_approval", null)
        runUnlessCompressing { chatService.handleToolApproval(_conversationId, toolCallId, approved, reason) }
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        analytics.logEvent("ai_tool_answer", null)
        runUnlessCompressing { chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer) }
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    // ---- 工作区 CLI ----

    val cliProcessState get() = chatService.cliProcessState

    /**
     * Picks the conversation's CLI (null = ordinary chat). An empty chat switches in place; a chat
     * with messages keeps its history as it is and the choice opens a new chat, whose id is returned.
     */
    fun selectCli(agentId: String?): Uuid? {
        val current = conversation.value
        if (current.messageNodes.isEmpty()) {
            viewModelScope.launch { chatService.setConversationCli(_conversationId, agentId) }
            return null
        }
        val newId = Uuid.random()
        chatService.prepareNewCliConversation(newId, agentId?.let {
            me.rerere.rikkihub.data.cli.ConversationCliSession(
                agentId = it,
                permissionMode = current.cliSession?.permissionMode ?: me.rerere.rikkihub.data.cli.CliPermissionMode.ASK,
            )
        })
        return newId
    }

    fun setCliPermissionMode(mode: me.rerere.rikkihub.data.cli.CliPermissionMode) {
        viewModelScope.launch { chatService.setCliPermissionMode(_conversationId, mode) }
    }

    fun exitCliToOrdinaryChat() {
        viewModelScope.launch { chatService.exitCliToOrdinaryChat(_conversationId) }
    }

    fun setCliConfigOption(configId: String, value: String) {
        viewModelScope.launch {
            runCatching { chatService.setCliConfigOption(_conversationId, configId, value) }
                .onFailure { chatService.addError(it, _conversationId) }
        }
    }

    fun setCliModel(modelId: String) {
        viewModelScope.launch {
            runCatching { chatService.setCliModel(_conversationId, modelId) }.onFailure { chatService.addError(it, _conversationId) }
        }
    }

    fun setCliMode(modeId: String) {
        viewModelScope.launch {
            runCatching { chatService.setCliMode(_conversationId, modeId) }.onFailure { chatService.addError(it, _conversationId) }
        }
    }

    fun restartCliSession() {
        viewModelScope.launch { chatService.restartCliSession(_conversationId) }
    }

    fun cliStderrTail(): String? = chatService.cliStderrTail(_conversationId)

    fun runCliShellCommand(command: String) {
        viewModelScope.launch {
            runCatching { chatService.runCliShellCommand(_conversationId, command) }
                .onFailure { chatService.addError(it, _conversationId, title = context.getString(me.rerere.rikkihub.R.string.error_title_cli)) }
        }
    }

    /** Makes the conversation's CLI setting the default for new chats of its assistant (null agent = off). */
    fun setCliAssistantDefault(enabled: Boolean) {
        val cli = conversation.value.cliSession
        val assistantId = conversation.value.assistantId
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(assistants = settings.assistants.map { assistant ->
                    if (assistant.id != assistantId) assistant
                    else assistant.copy(
                        defaultCliAgentId = if (enabled) cli?.agentId else null,
                        defaultCliPermissionMode = cli?.permissionMode ?: assistant.defaultCliPermissionMode,
                    )
                })
            }
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            chatService.updateConversationMetadata(
                _conversationId,
                me.rerere.rikkihub.web.dto.ConversationMetadataPatch(patchedTitle = true, title = title),
            )
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            chatService.toggleConversationPin(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            chatService.moveConversationToAssistant(conversation.id, targetAssistantId)
            if (conversation.id == _conversationId) {
                settingsStore.updateAssistant(targetAssistantId)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(conversation.id, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    val qubaguRefining: StateFlow<Set<Uuid>> get() = chatService.qubaguRefining

    fun setQubaguApplied(messageId: Uuid, part: Int?, edit: Int?, applied: Boolean) {
        viewModelScope.launch { chatService.setQubaguApplied(_conversationId, messageId, part, edit, applied) }
    }

    fun scanWithQubagu(messageId: Uuid) {
        viewModelScope.launch { chatService.cleanMessageWithQubagu(_conversationId, messageId) }
    }

    fun refineWithQubagu(messageId: Uuid) {
        chatService.launchQubaguRefine(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

}
