package me.rerere.rikkihub.ui.pages.chat

import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.data.creator.definition.CreatorToolNamespace
import me.rerere.rikkihub.ui.components.richtext.LocalChatWorkspaceId
import me.rerere.rikkihub.ui.components.creator.CreatorProposalsButton
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.findProvider
import me.rerere.rikkihub.data.datastore.getCurrentAssistant
import me.rerere.rikkihub.data.datastore.getCurrentChatModel
import me.rerere.rikkihub.data.datastore.getSelectedASRProvider
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.service.ChatError
import me.rerere.rikkihub.service.CompressState
import me.rerere.rikkihub.service.CompressStatus
import me.rerere.rikkihub.ui.components.ai.ChatAttachmentPickerActions
import me.rerere.rikkihub.ui.components.ai.ChatInput
import me.rerere.rikkihub.ui.components.ai.FilesPicker
import me.rerere.rikkihub.ui.components.ai.InjectionQuickConfigSheet
import me.rerere.rikkihub.ui.components.ai.SearchMode
import me.rerere.rikkihub.ui.components.ai.SearchPickerSheet
import me.rerere.rikkihub.ui.components.ai.currentSearchMode
import me.rerere.rikkihub.ui.components.ai.nextSearchModeOnToggle
import me.rerere.rikkihub.ui.components.ai.supportsBuiltInSearch
import me.rerere.rikkihub.ui.hooks.rememberSharedPreferenceString
import me.rerere.rikkihub.data.ai.polish.PromptPolishException
import me.rerere.rikkihub.data.datastore.mergeObjectEdits
import me.rerere.rikkihub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkihub.ui.components.ai.rememberChatAttachmentPickerActions
import me.rerere.rikkihub.claudelife.rememberCacheWarmPlaceholder
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.context.Navigator
import me.rerere.rikkihub.ui.hooks.ChatInputState
import me.rerere.rikkihub.ui.hooks.EditStateContent
import me.rerere.rikkihub.ui.hooks.useEditState
import me.rerere.rikkihub.utils.base64Decode
import me.rerere.rikkihub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    // 新会话发出第一条消息前，跟随抽屉当前选中的文件夹
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity)
    val selectedFolderId by drawerVm.selectedFolderId.collectAsStateWithLifecycle()
    LaunchedEffect(selectedFolderId) {
        vm.updateDraftFolder(selectedFolderId)
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val assistant = remember(setting.assistants, setting.assistantId) {
        setting.getCurrentAssistant()
    }
    val workspaceId = assistant.workspaceId?.toString()
    var filesOpen by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen && !filesOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    LaunchedEffect(workspaceId) {
        filesOpen = false
    }

    // Hide keyboard when either drawer/overlay is open; clear focus so popup transitions cannot reopen it.
    LaunchedEffect(drawerState.isOpen, filesOpen) {
        if (drawerState.isOpen || filesOpen) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }

    LaunchedEffect(filesOpen) {
        if (filesOpen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val startVoiceMode = rememberVoiceModeStarter(vm, setting)

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val parts = withContext(Dispatchers.IO) {
                val localFiles = filesManager.createChatFilesByContents(files)
                val contentTypes = files.mapNotNull { file ->
                    filesManager.getFileMimeType(file)
                }
                buildList {
                    localFiles.forEachIndexed { index, file ->
                        val type = contentTypes.getOrNull(index)
                        if (type?.startsWith("image/") == true) {
                            add(UIMessagePart.Image(url = file.toString()))
                        } else if (type?.startsWith("video/") == true) {
                            add(UIMessagePart.Video(url = file.toString()))
                        } else if (type?.startsWith("audio/") == true) {
                            add(UIMessagePart.Audio(url = file.toString()))
                        }
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    val filesGesturesEnabled = ChatWorkspaceSwipe.filesGesturesEnabled(
        workspaceBound = workspaceId != null,
        conversationDrawerOpen = drawerState.isOpen,
    )
    val conversationGesturesEnabled = ChatWorkspaceSwipe.conversationGesturesEnabled(filesOpen)

    ChatWorkspaceFilesHost(
        workspaceId = workspaceId,
        isOpen = filesOpen,
        onOpenChange = { filesOpen = it },
        gesturesEnabled = filesGesturesEnabled,
    ) {
        CompositionLocalProvider(LocalChatWorkspaceId provides workspaceId) {
            when {
                isBigScreen -> {
                    PermanentNavigationDrawer(
                        drawerContent = {
                            ChatDrawerContent(
                                navController = navController,
                                currentId = conversation.id,
                                vm = vm,
                                settings = setting
                            )
                        }
                    ) {
                        ChatPageContent(
                            onStartVoiceMode = startVoiceMode,
                            inputState = inputState,
                            loadingJob = loadingJob,
                            processingStatus = processingStatus,
                            setting = setting,
                            conversation = conversation,
                            drawerState = drawerState,
                            navController = navController,
                            vm = vm,
                            chatListState = chatListState,
                            enableWebSearch = enableWebSearch,
                            currentChatModel = currentChatModel,
                            bigScreen = true,
                            workspaceBound = workspaceId != null,
                            onOpenWorkspaceFiles = { filesOpen = true },
                            errors = errors,
                            onDismissError = { vm.dismissError(it) },
                            onClearAllErrors = { vm.clearAllErrors() },
                        )
                    }
                }

                else -> {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        gesturesEnabled = conversationGesturesEnabled,
                        drawerContent = {
                            ChatDrawerContent(
                                navController = navController,
                                currentId = conversation.id,
                                vm = vm,
                                settings = setting
                            )
                        }
                    ) {
                        ChatPageContent(
                            onStartVoiceMode = startVoiceMode,
                            inputState = inputState,
                            loadingJob = loadingJob,
                            processingStatus = processingStatus,
                            setting = setting,
                            conversation = conversation,
                            drawerState = drawerState,
                            navController = navController,
                            vm = vm,
                            chatListState = chatListState,
                            enableWebSearch = enableWebSearch,
                            currentChatModel = currentChatModel,
                            bigScreen = false,
                            workspaceBound = workspaceId != null,
                            onOpenWorkspaceFiles = { filesOpen = true },
                            errors = errors,
                            onDismissError = { vm.dismissError(it) },
                            onClearAllErrors = { vm.clearAllErrors() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatPageContent(
    onStartVoiceMode: () -> Unit,
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    workspaceBound: Boolean,
    onOpenWorkspaceFiles: () -> Unit,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    val assistant = remember(setting.assistants, setting.assistantId) {
        setting.getCurrentAssistant()
    }
    var showFilesSheet by remember { mutableStateOf(false) }
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showSearchSheet by remember { mutableStateOf(false) }
    // 上次开着的搜索方式，「网络搜索」按钮点开时恢复它
    var lastSearchMode by rememberSharedPreferenceString("lastWebSearchMode")
    val onUpdateSearchMode: (SearchMode) -> Unit = { mode ->
        if (mode != SearchMode.OFF) lastSearchMode = mode.name
        // 助手和模型都从最新的设置里取，不用界面手里的旧快照覆盖
        vm.updateSettings { latest ->
            val current = latest.getCurrentAssistant()
            val model = latest.getCurrentChatModel()
            latest.copy(
                assistants = latest.assistants.map { assistant ->
                    if (assistant.id == current.id) {
                        assistant.copy(enableWebSearch = mode == SearchMode.LOCAL)
                    } else {
                        assistant
                    }
                },
                providers = if (model == null) {
                    latest.providers
                } else {
                    latest.providers.map { provider ->
                        provider.editModel(
                            model.copy(
                                tools = if (mode == SearchMode.BUILT_IN) {
                                    model.tools + BuiltInTools.Search
                                } else {
                                    model.tools - BuiltInTools.Search
                                }
                            )
                        )
                    }
                },
            )
        }
    }
    val onUpdateSearchService: (Int) -> Unit = { index ->
        vm.updateSettings { latest -> latest.copy(searchServiceSelected = index) }
    }
    val searchEnabledMsg = stringResource(R.string.web_search_enabled)
    val searchDisabledMsg = stringResource(R.string.web_search_disabled)
    val onToggleSearch: () -> Unit = {
        val model = setting.getCurrentChatModel()
        val next = nextSearchModeOnToggle(
            current = currentSearchMode(enableWebSearch, model),
            lastMode = SearchMode.entries.find { it.name == lastSearchMode },
            builtInSupported = supportsBuiltInSearch(model?.findProvider(setting.providers)),
        )
        onUpdateSearchMode(next)
        toaster.show(
            message = if (next != SearchMode.OFF) searchEnabledMsg else searchDisabledMsg,
            duration = 1.seconds,
            type = if (next != SearchMode.OFF) ToastType.Success else ToastType.Normal,
        )
    }
    val polishErrorMessages = mapOf(
        PromptPolishException.Reason.NO_MODEL to stringResource(R.string.chat_input_polish_error_no_model),
        PromptPolishException.Reason.EMPTY_RESULT to stringResource(R.string.chat_input_polish_error_empty),
        PromptPolishException.Reason.INPUT_TOO_LONG to stringResource(R.string.chat_input_polish_error_too_long),
    )
    val polishFailed = stringResource(R.string.chat_input_polish_failed)
    LaunchedEffect(vm) {
        vm.promptPolishErrors.collect { error ->
            val message = (error as? PromptPolishException)?.let { polishErrorMessages[it.reason] }
                ?: "$polishFailed: ${error.message ?: error.javaClass.simpleName}"
            toaster.show(message, type = ToastType.Error)
        }
    }
    val attachmentPickerActions = rememberChatAttachmentPickerActions(
        inputState = inputState,
        setting = setting,
        onAttachmentAdded = { showFilesSheet = false },
    )
    val onUpdateAssistant: (Assistant) -> Unit = { updated ->
        // 面板改的是界面上的这份助手：只把改到的字段写到最新的助手上
        val base = assistant
        vm.updateSettings { latest ->
            latest.copy(
                assistants = latest.assistants.map { current ->
                    if (current.id == updated.id) mergeObjectEdits(Assistant.serializer(), base, updated, current)
                    else current
                }
            )
        }
    }
    val onUpdateConversation: (Conversation) -> Unit = { updated ->
        vm.updateConversation(updated)
        vm.saveConversationAsync()
    }

    val completionProviders = remember(
        assistant.workspaceId, conversation.workspaceCwd, conversation.workspaceCwdScoped, workspaceRepository,
    ) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                    scoped = conversation.workspaceCwdScoped,
                )
            )
        }.orEmpty()
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(setting = setting, modifier = Modifier.hazeSource(hazeState))
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    workspaceBound = workspaceBound,
                    onOpenWorkspaceFiles = onOpenWorkspaceFiles,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    }
                )
            },
            bottomBar = {
                val messageQueue by vm.messageQueue.collectAsStateWithLifecycle()
                val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
                val cacheWarmStatus by vm.cacheWarmStatus.collectAsStateWithLifecycle()
                val cacheWarmPlaceholder = rememberCacheWarmPlaceholder(cacheWarmStatus, vm::cacheWarmNowMs)
                val compressState by vm.compressState.collectAsStateWithLifecycle()
                val polishState by vm.promptPolisher.state.collectAsStateWithLifecycle()
                Column {
                    CompressionStatusBar(state = compressState, onCancel = vm::cancelCompression)
                    ChatInput(
                        placeholderText = cacheWarmPlaceholder,
                        onStartVoiceMode = onStartVoiceMode,
                        voiceState = voiceState,
                        onStopVoiceMode = vm.voiceSession::stop,
                        polishState = polishState,
                        onPolish = vm.promptPolisher::start,
                        onCancelPolish = vm.promptPolisher::cancel,
                        onUndoPolish = vm.promptPolisher::undo,
                        onPolishTextChanged = vm.promptPolisher::onTextChanged,
                        onOpenPolishSettings = { navController.navigate(Screen.SettingPromptPolish) },
                        state = inputState,
                        messageQueue = messageQueue,
                        onRemoveQueuedMessage = vm::removeQueuedMessage,
                        onBeginEditQueuedMessage = vm::beginEditQueuedMessage,
                        onFinishEditQueuedMessage = vm::finishEditQueuedMessage,
                        onResumeMessageQueue = vm::resumeMessageQueue,
                        loading = loadingJob != null,
                        settings = setting,
                        hazeState = hazeState,
                        completionProviders = completionProviders,
                        onCancelClick = {
                            vm.stopGeneration()
                        },
                        enableSearch = enableWebSearch,
                        onUpdateSearchMode = onUpdateSearchMode,
                        onToggleSearch = onToggleSearch,
                        onSendClick = {
                            if (currentChatModel == null) {
                                toaster.show("请先选择模型", type = ToastType.Error)
                                return@ChatInput
                            }
                            if (inputState.isEditing()) {
                                vm.handleMessageEdit(
                                    parts = inputState.getContents(),
                                    messageId = inputState.editingMessage!!,
                                )
                            } else {
                                vm.handleMessageSend(inputState.getContents())
                                scope.launch {
                                    delay(100.milliseconds)
                                    chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                                }
                            }
                            inputState.clearInput()
                        },
                        onLongSendClick = {
                            if (inputState.isEditing()) {
                                vm.handleMessageEdit(
                                    parts = inputState.getContents(),
                                    messageId = inputState.editingMessage!!,
                                )
                            } else {
                                vm.handleMessageSend(content = inputState.getContents(), answer = false)
                                scope.launch {
                                    chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                                }
                            }
                            inputState.clearInput()
                        },
                        onUpdateChatModel = {
                            vm.setChatModel(assistant = assistant, model = it)
                        },
                        onUpdateAssistant = onUpdateAssistant,
                        onUpdateSearchService = onUpdateSearchService,
                        onMoreClick = {
                            showFilesSheet = true
                        },
                        onUploadFileClick = {
                            attachmentPickerActions.onPickFile()
                        },
                        onExtensionsClick = {
                            showInjectionSheet = true
                        },
                    )
                }
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.updateConversation(
                        conversation.copy(
                            messageNodes = conversation.messageNodes.map { node ->
                                if (node.id == newNode.id) {
                                    newNode
                                } else {
                                    node
                                }
                            }
                        ))
                    vm.saveConversationAsync()
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.requestScrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
            )
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                vm = vm,
                attachmentPickerActions = attachmentPickerActions,
                onUpdateAssistant = onUpdateAssistant,
                onShowInjectionSheetChange = { showInjectionSheet = it },
                onStartVoiceMode = onStartVoiceMode,
                onToggleSearch = onToggleSearch,
                onOpenSearch = {
                    showFilesSheet = false
                    showSearchSheet = true
                },
                onDismiss = { showFilesSheet = false },
            )
        }

        if (showSearchSheet) {
            SearchPickerSheet(
                enableSearch = enableWebSearch,
                settings = setting,
                model = setting.getCurrentChatModel(),
                onUpdateSearchMode = onUpdateSearchMode,
                onUpdateSearchService = onUpdateSearchService,
                onDismiss = { showSearchSheet = false },
            )
        }

        if (showInjectionSheet) {
            InjectionQuickConfigSheet(
                conversation = conversation,
                assistant = assistant,
                settings = setting,
                onUpdateAssistant = onUpdateAssistant,
                onUpdateConversation = onUpdateConversation,
                onDismiss = { showInjectionSheet = false },
                onDismissAll = {
                    showInjectionSheet = false
                    showFilesSheet = false
                },
            )
        }
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    vm: ChatVM,
    attachmentPickerActions: ChatAttachmentPickerActions,
    onUpdateAssistant: (Assistant) -> Unit,
    onShowInjectionSheetChange: (Boolean) -> Unit,
    onStartVoiceMode: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        onShowInjectionSheetChange(false)
        showCompressDialog = false
        onDismiss()
    }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            mcpManager = vm.mcpManager,
            compressState = vm.compressState.collectAsStateWithLifecycle().value,
            onCompressContext = { additionalPrompt, keepRecent -> vm.startCompression(additionalPrompt, keepRecent) },
            onCancelCompression = { vm.cancelCompression() },
            onUpdateAssistant = onUpdateAssistant,
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            onShowInjectionSheetChange = onShowInjectionSheetChange,
            onToggleCacheKeepAlive = { enabled -> vm.setCacheKeepAliveEnabled(enabled) },
            onToggleSearch = onToggleSearch,
            onOpenSearch = onOpenSearch,
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = attachmentPickerActions.onTakePicture,
            onPickImage = attachmentPickerActions.onPickImage,
            onPickVideo = attachmentPickerActions.onPickVideo,
            onPickAudio = attachmentPickerActions.onPickAudio,
            onSketch = attachmentPickerActions.onSketch,
            onStartVoiceMode = if (
                setting.getSelectedASRProvider()?.supportsServerVadVoiceMode == true &&
                voiceState.phase == VoicePhase.Off
            ) {
                {
                    dismissAll()
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onStartVoiceMode()
                }
            } else null,
        )
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    workspaceBound: Boolean,
    onOpenWorkspaceFiles: () -> Unit,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    }
                ) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val assistant = remember(settings.assistants, settings.assistantId) {
                        settings.getCurrentAssistant()
                    }
                    val model = remember(
                        settings.providers, assistant.chatModelId, settings.chatModelId
                    ) {
                        settings.getCurrentChatModel()
                    }
                    val provider = remember(model, settings.providers) {
                        model?.findProvider(providers = settings.providers, checkOverwrite = false)
                    }
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${model.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        actions = {
            if (CreatorAssistantDefinition.isCreator(conversation.assistantId)) {
                // 刷新键：已执行的 creator_* 调用数（新提案只会由它们产生）
                val creatorCalls = remember(conversation) {
                    conversation.currentMessages.sumOf { message ->
                        message.parts.count { part ->
                            part is UIMessagePart.Tool && part.isExecuted &&
                                CreatorToolNamespace.isCreatorWireName(part.toolName)
                        }
                    }
                }
                CreatorProposalsButton(
                    conversationId = conversation.id.toString(),
                    refreshKey = creatorCalls,
                )
            }

            if (workspaceBound) {
                IconButton(onClick = onOpenWorkspaceFiles) {
                    Icon(
                        HugeIcons.File02,
                        contentDescription = stringResource(R.string.workspace_detail_tab_files),
                    )
                }
            }

            IconButton(
                onClick = {
                    onClickMenu()
                }
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            IconButton(
                onClick = {
                    onNewChat()
                }
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}

/** Background compression in progress, also when started from the web. */
@Composable
private fun CompressionStatusBar(state: CompressState?, onCancel: () -> Unit) {
    if (state?.status != CompressStatus.RUNNING) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.chat_page_compress_running),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.chat_page_compress_cancel))
            }
        }
    }
}
