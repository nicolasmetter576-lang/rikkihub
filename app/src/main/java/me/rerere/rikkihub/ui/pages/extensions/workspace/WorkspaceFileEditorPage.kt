package me.rerere.rikkihub.ui.pages.extensions.workspace

import me.rerere.hugeicons.stroke.TextFont
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.Layout2Column
import me.rerere.hugeicons.HugeIcons
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import android.content.ClipData
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.ai.tuiqiao.Tuiqiao
import me.rerere.rikkihub.data.ai.zaizao.Zaizao
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoCandidate
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoException
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoException
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.ui.context.LocalSettings
import me.rerere.rikkihub.ui.components.webview.WebView
import me.rerere.rikkihub.ui.components.webview.rememberWebViewState
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.workspace.WorkspaceFileRevisionConflictException
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/**
 * The editor page: the file being written, and a reference side the user swipes to (or opens with the
 * 对照 button) to read or edit a second file of the workspace next to it. The reference side starts as
 * the workspace file list at the main file's folder; a text file picked there opens on that side.
 */
@Composable
fun WorkspaceFileEditorPage(id: String, area: WorkspaceStorageArea, path: String) {
    val nav = LocalNavController.current
    val toaster = LocalToaster.current
    val sameFileMessage = stringResource(R.string.workspace_reference_same_file)
    // "AREA\u0000path" of the reference file; null = its side shows the file list.
    var referenceTarget by rememberSaveable(id, area, path) { mutableStateOf<String?>(null) }
    var showReference by rememberSaveable(id, area, path) { mutableStateOf(false) }
    val referenceHandle = remember(id, area, path) { WorkspaceEditorPaneHandle() }
    val reference = referenceTarget?.let { target ->
        val (targetArea, targetPath) = target.split('\u0000', limit = 2)
        WorkspaceStorageArea.valueOf(targetArea) to targetPath
    }
    WorkspaceEditorPanes(
        showSecond = showReference,
        onShowSecondChange = { showReference = it },
        first = { active ->
            WorkspaceFileEditorPane(
                id, area, path, WorkspaceEditorPaneRole.MAIN, active,
                onExit = { nav.popBackStack() },
                onSwitch = { showReference = true },
                // Unsaved reference edits keep the page open: that side asks first.
                beforeLeave = {
                    if (!referenceHandle.blocksLeave()) true else {
                        showReference = true
                        referenceHandle.requestLeave()
                        false
                    }
                },
            )
        },
        second = { active ->
            if (reference == null) {
                WorkspaceReferenceBrowser(
                    workspaceId = id,
                    startArea = area,
                    startDirectory = path.substringBeforeLast('/', missingDelimiterValue = ""),
                    active = active,
                    onOpen = { openedArea, openedPath ->
                        if (openedArea == area && openedPath == path) {
                            showReference = false
                            toaster.show(sameFileMessage, type = ToastType.Info)
                        } else referenceTarget = "${openedArea.name}\u0000$openedPath"
                    },
                    onShowMain = { showReference = false },
                )
            } else {
                WorkspaceFileEditorPane(
                    id, reference.first, reference.second, WorkspaceEditorPaneRole.REFERENCE, active,
                    onExit = { referenceTarget = null },
                    onSwitch = { showReference = false },
                    handle = referenceHandle,
                )
            }
        },
    )
}

internal enum class WorkspaceEditorPaneRole { MAIN, REFERENCE }

/** What the page needs from the reference editor before leaving: whether it still holds unsaved text, and its leave flow. */
internal class WorkspaceEditorPaneHandle {
    var blocksLeave: () -> Boolean = { false }
    var requestLeave: () -> Unit = {}
}

/** Source is the only editable state. VM retains state across configuration changes; Bundle
 * holds only a UUID, with source, selection, CAS baseline AND opened text in the private noBackup draft.
 * Autosaves (CAS) once input pauses; a failure pauses autosave, never retries. Revert restores the
 * text the file had when opened, as one undoable edit that autosave then writes back.
 *
 * One side of [WorkspaceFileEditorPage]. [active] = the side on screen: it owns the back key, and the
 * side that slides away saves at once. [onExit] runs once the file can be left (saved, or discarded):
 * MAIN leaves the page, REFERENCE goes back to the file list. [onSwitch] slides to the other side.
 */
@Composable
internal fun WorkspaceFileEditorPane(
    id: String,
    area: WorkspaceStorageArea,
    path: String,
    role: WorkspaceEditorPaneRole,
    active: Boolean,
    onExit: () -> Unit,
    onSwitch: () -> Unit,
    beforeLeave: () -> Boolean = { true },
    handle: WorkspaceEditorPaneHandle? = null,
) {
    val repository = koinInject<WorkspaceRepository>()
    val tuiqiaoCache = koinInject<WorkspaceTuiqiaoCache>()
    val zaizaoCache = koinInject<WorkspaceZaizaoCache>()
    val settingsStore = koinInject<SettingsStore>()
    val toaster = LocalToaster.current
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val draftStore = remember {
        WorkspaceEditorDraftStore(File(context.noBackupFilesDir, "workspace_editor_drafts"),
            onError = { Log.w(DRAFT_LOG_TAG, "workspace editor draft io failure", it) })
    }
    val editable = area == WorkspaceStorageArea.FILES
    val fileName = path.substringAfterLast('/').ifBlank { path }
    val previewKind = workspaceFilePreviewKind(fileName)
    var showPreview by rememberSaveable(id, area, path) { mutableStateOf(false) }
    val fileKey = "$id\u0000$area\u0000$path"
    val draftVm: WorkspaceEditorDraftViewModel = viewModel(key = "workspace-editor:$fileKey") {
        WorkspaceEditorDraftViewModel()
    }.also { it.draftStore = draftStore }
    val textState = draftVm.textState
    rememberSaveable(fileKey, saver = Saver<WorkspaceEditorDraftViewModel, String>(
        save = { current ->
            if (!current.hasLiveDraft(fileKey)) null else {
                val text = current.textState.text
                val selection = current.textState.selection
                val fingerprint = workspaceEditorDraftFingerprint(text)
                if (current.persistedDraftFingerprint == fingerprint && current.persistedSelection == selection &&
                    current.persistedBaselineRevision == current.baselineRevision && draftStore.hasDraft(current.draftToken)) current.draftToken
                else {
                    val token = draftStore.save(fileKey, text, selection.start, selection.end, current.draftToken,
                        current.originalText, current.baselineRevision, current.openedText)
                    if (token != null) {
                        current.bindDraftToken(token, fingerprint, selection)
                        token
                    } else current.draftToken?.takeIf { draftStore.hasDraft(it) }
                }
            }
        },
        restore = { token ->
            val draft = draftStore.restore(token)
            if (draft != null && draft.fileKey == fileKey) draftVm.adoptRestoredDraft(
                draft.text, draft.selStart, draft.selEnd, token, fileKey, draft.originalText, draft.revision, draft.openedText)
            draftVm
        },
    )) { draftVm }
    var loading by remember(fileKey) { mutableStateOf(true) }
    var loadError by remember(fileKey) { mutableStateOf(false) }
    var saving by remember(fileKey) { mutableStateOf(false) }
    var leaveConfirm by rememberSaveable(fileKey) { mutableStateOf(false) }
    var reloadConfirm by rememberSaveable(fileKey) { mutableStateOf(false) }
    var loadRetry by rememberSaveable(fileKey) { mutableStateOf(0) }
    var closeWhenSaved by remember(fileKey) { mutableStateOf(false) }
    // Read fresh by save/leave: they also run from effects, after the composition that built them.
    fun isDirty() = draftVm.originalText == null || textState.text.toString() != draftVm.originalText
    fun hasValidBaseline() = draftVm.originalText != null && draftVm.baselineRevision?.matches(Regex("[0-9a-f]{64}")) == true
    fun canAutoSave() = editable && !loading && !loadError && hasValidBaseline() && draftVm.autoSavePause == null
    val dirty = isDirty()
    val validBaseline = hasValidBaseline()
    val savedMessage = stringResource(R.string.workspace_editor_saved)
    val failedMessage = stringResource(R.string.workspace_editor_save_failed)
    val conflictMessage = stringResource(R.string.workspace_editor_conflict)
    val revertedMessage = stringResource(R.string.workspace_editor_reverted)
    val tuiqiaoReplacedMessage = stringResource(R.string.tuiqiao_replaced)
    val tuiqiaoChangedMessage = stringResource(R.string.tuiqiao_source_changed)
    val tuiqiaoCopiedMessage = stringResource(R.string.tuiqiao_copied)
    val tuiqiaoEmptyMessage = stringResource(R.string.tuiqiao_error_empty)
    val tuiqiaoTooLongMessage = stringResource(R.string.tuiqiao_error_too_long, Tuiqiao.MAX_SELECTION_CHARS)
    val tuiqiaoVm: WorkspaceTuiqiaoViewModel = viewModel(key = "workspace-tuiqiao:$fileKey") {
        WorkspaceTuiqiaoViewModel(tuiqiaoCache, settingsStore, fileKey)
    }
    val zaizaoVm: WorkspaceZaizaoViewModel = viewModel(key = "workspace-zaizao:$fileKey") {
        WorkspaceZaizaoViewModel(zaizaoCache, settingsStore, fileKey)
    }
    val zaizaoTooLongMessage = stringResource(R.string.zaizao_error_too_long, Zaizao.MAX_SELECTION_CHARS)
    // 推敲 replaces source text, so it needs what autosave needs; only file types enabled in its settings.
    val tuiqiaoConfig = LocalSettings.current.tuiqiao
    val canTuiqiao = editable && tuiqiaoConfig.enabledFor(fileName) && !loading && !loadError &&
        validBaseline && draftVm.autoSavePause == null
    // A sheet left open while 推敲 stopped being available (file type turned off in its settings,
    // autosave paused) could no longer replace anything: close it. Not keyed on `loading`, which is
    // briefly true after a configuration change while the sheet's request keeps running.
    val tuiqiaoTurnedOff = !tuiqiaoConfig.enabledFor(fileName) || draftVm.autoSavePause != null
    LaunchedEffect(tuiqiaoTurnedOff) {
        if (tuiqiaoTurnedOff && tuiqiaoVm.session != null) tuiqiaoVm.close()
        if (tuiqiaoTurnedOff && zaizaoVm.shown != null) zaizaoVm.close()
    }
    val onTuiqiao: ((Int, Int) -> Unit)? = if (!canTuiqiao) null else { start, end ->
        when (tuiqiaoVm.open(fileName, textState.text.toString(), start, end)) {
            null -> Unit
            TuiqiaoException.Reason.SELECTION_TOO_LONG -> toaster.show(tuiqiaoTooLongMessage, type = ToastType.Info)
            else -> toaster.show(tuiqiaoEmptyMessage, type = ToastType.Info)
        }
    }
    // 再造 works wherever 推敲 does (same file types and the same need for a working autosave).
    val onZaizao: ((Int, Int) -> Unit)? = if (!canTuiqiao) null else { start, end ->
        when (zaizaoVm.open(fileName, textState.text.toString(), start, end)) {
            null -> Unit
            ZaizaoException.Reason.SELECTION_TOO_LONG -> toaster.show(zaizaoTooLongMessage, type = ToastType.Info)
            else -> toaster.show(tuiqiaoEmptyMessage, type = ToastType.Info)
        }
    }
    val clipboard = LocalClipboard.current
    fun copyTuiqiao(candidate: TuiqiaoCandidate) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("tuiqiao", candidate.text)))
            toaster.show(tuiqiaoCopiedMessage, type = ToastType.Success)
        }
    }
    /** Replaces the session's range if it still holds the original text: one undoable edit, autosaved. */
    fun applyTuiqiao(candidate: TuiqiaoCandidate) {
        val session = tuiqiaoVm.session ?: return
        val text = textState.text
        if (!canTuiqiao || session.end > text.length || text.substring(session.start, session.end) != session.original) {
            toaster.show(tuiqiaoChangedMessage, type = ToastType.Error)
            return
        }
        textState.edit {
            replace(session.start, session.end, candidate.text)
            selection = TextRange(session.start + candidate.text.length)
        }
        tuiqiaoVm.consumed()
        toaster.show(tuiqiaoReplacedMessage, type = ToastType.Success)
    }
    fun copyZaizao(draft: WorkspaceZaizaoDraft) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("zaizao", draft.result)))
            toaster.show(tuiqiaoCopiedMessage, type = ToastType.Success)
        }
    }
    /** Replaces the session's original if it is still where the session last saw it; the session stays kept. */
    fun applyZaizao(draft: WorkspaceZaizaoDraft) {
        val entry = zaizaoVm.shown ?: return
        if (!canTuiqiao || !entry.matches(textState.text.toString())) {
            toaster.show(tuiqiaoChangedMessage, type = ToastType.Error)
            return
        }
        val start = entry.liveStart
        textState.edit {
            replace(start, entry.liveEnd, draft.result)
            selection = TextRange(start + draft.result.length)
        }
        zaizaoVm.close()
        toaster.show(tuiqiaoReplacedMessage, type = ToastType.Success)
    }

    fun requestLeave() {
        if (!beforeLeave()) return
        when {
            !saving && !(editable && draftVm.loadedFileKey == fileKey && isDirty()) -> onExit()
            canAutoSave() -> closeWhenSaved = true // flush autosave, then close without asking
            else -> leaveConfirm = true
        }
    }
    /** [auto]: background save — silent on success, skipped when clean or paused; failure pauses it. */
    fun save(closeAfter: Boolean = false, auto: Boolean = false) {
        if (saving || loading || loadError || !editable || !hasValidBaseline()) return
        if (auto && (draftVm.autoSavePause != null || !isDirty())) return
        val frozenText = textState.text.toString()
        val expectedRevision = draftVm.baselineRevision ?: return
        saving = true
        scope.launch {
            try {
                val result = repository.writeTextWithRevision(id, path, frozenText, expectedRevision)
                // Update ONLY the submitted snapshot baseline. Later keystrokes remain dirty.
                draftVm.originalText = frozenText
                draftVm.baselineRevision = result.revision
                draftVm.autoSavePause = null
                if (!auto) {
                    toaster.show(savedMessage, type = ToastType.Success)
                    leaveConfirm = false
                }
                if (closeAfter && textState.text.toString() == frozenText) {
                    draftVm.discardBoundDraft()
                    onExit()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val conflict = error is WorkspaceFileRevisionConflictException
                draftVm.autoSavePause = if (conflict) WorkspaceEditorAutoSavePause.CONFLICT else WorkspaceEditorAutoSavePause.FAILED
                toaster.show(if (conflict) conflictMessage else failedMessage, type = ToastType.Error)
                if (closeAfter && auto) leaveConfirm = true
            } finally { saving = false }
        }
    }
    val latestSave by rememberUpdatedState(::save)
    LaunchedEffect(fileKey) {
        runWorkspaceEditorAutoSave(textState, awaitIdle = { snapshotFlow { saving }.first { !it } }) {
            latestSave(false, true)
        }
    }
    LaunchedEffect(closeWhenSaved) {
        if (!closeWhenSaved) return@LaunchedEffect
        snapshotFlow { saving }.first { !it }
        closeWhenSaved = false
        when {
            !isDirty() -> {
                draftVm.discardBoundDraft()
                onExit()
            }
            canAutoSave() -> save(closeAfter = true, auto = true)
            else -> leaveConfirm = true
        }
    }
    // The reference side's back key slides home; leaving it (for the file list) is its folder button.
    BackHandler(enabled = active) { if (role == WorkspaceEditorPaneRole.MAIN) requestLeave() else onSwitch() }
    // The side that slides away writes its text at once instead of after the usual pause.
    LaunchedEffect(active) { if (!active) latestSave(false, true) }
    if (handle != null) {
        SideEffect {
            handle.blocksLeave = { saving || editable && draftVm.loadedFileKey == fileKey && isDirty() }
            handle.requestLeave = ::requestLeave
        }
        DisposableEffect(handle) {
            onDispose {
                handle.blocksLeave = { false }
                handle.requestLeave = {}
            }
        }
    }
    LaunchedEffect(fileKey, loadRetry) {
        if (draftVm.loadedFileKey == fileKey) {
            loading = false
            loadError = false
            return@LaunchedEffect
        }
        loading = true
        loadError = false
        try {
            val snapshot = repository.readTextSnapshotForPreview(id, area, path)
            textState.setTextAndPlaceCursorAtEnd(snapshot.content)
            textState.undoState.clearHistory() // disk load is not an undoable source command
            draftVm.originalText = snapshot.content
            draftVm.baselineRevision = snapshot.revision
            draftVm.openedText = snapshot.content
            draftVm.autoSavePause = null
            draftVm.loadedFileKey = fileKey
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadError = true // Binary / invalid UTF-8 / permission / I/O: generic unavailable, no conversion.
        } finally { loading = false }
    }
    if (leaveConfirm || reloadConfirm) WorkspaceEditorLeaveDialog(
        saving = saving,
        canSave = validBaseline && !reloadConfirm,
        onSave = { save(closeAfter = true) },
        onDiscard = {
            draftVm.discardBoundDraft()
            leaveConfirm = false
            if (reloadConfirm) {
                reloadConfirm = false
                draftVm.loadedFileKey = null
                loadRetry++
            } else onExit()
        },
        onContinue = { leaveConfirm = false; reloadConfirm = false },
    )
    val tuiqiaoSession = tuiqiaoVm.session
    if (tuiqiaoSession != null) {
        WorkspaceTuiqiaoSheet(
            session = tuiqiaoSession,
            status = tuiqiaoVm.status,
            slotStatus = tuiqiaoVm.slotStatus,
            items = tuiqiaoVm.items,
            config = tuiqiaoConfig,
            onModeChange = { tuiqiaoVm.regenerate(textState.text.toString(), it) },
            onExpandSentence = { tuiqiaoVm.expandToSentence(textState.text.toString()) },
            onExpandParagraph = { tuiqiaoVm.expandToParagraph(textState.text.toString()) },
            onRegenerate = { tuiqiaoVm.regenerate(textState.text.toString()) },
            onRetrySlot = tuiqiaoVm::retrySlot,
            onConfigChange = tuiqiaoVm::updateConfig,
            onOpenSettings = { nav.navigate(Screen.SettingTuiqiao) },
            onCopy = ::copyTuiqiao,
            onReplace = ::applyTuiqiao,
            onDismiss = tuiqiaoVm::close,
        )
    }
    val zaizaoEntry = zaizaoVm.shown
    if (zaizaoEntry != null) {
        val zaizaoErrorMessages = ZaizaoException.Reason.entries.associateWith { zaizaoErrorText(it, null) }
        fun reportZaizao(reason: ZaizaoException.Reason?) {
            reason?.let { toaster.show(zaizaoErrorMessages.getValue(it), type = ToastType.Info) }
        }
        WorkspaceZaizaoSheet(
            entry = zaizaoEntry,
            recent = LocalSettings.current.zaizao.recentRequirements,
            onExpandSentence = { reportZaizao(zaizaoVm.expandToSentence(textState.text.toString())) },
            onExpandParagraph = { reportZaizao(zaizaoVm.expandToParagraph(textState.text.toString())) },
            onSend = { reportZaizao(zaizaoVm.send()) },
            onAnother = zaizaoVm::another,
            onRetry = zaizaoVm::retry,
            onStop = zaizaoVm::stop,
            onContinueFrom = zaizaoVm::continueFrom,
            onForgetRecent = zaizaoVm::forgetRecent,
            onOpenSettings = { nav.navigate(Screen.SettingZaizao) },
            onCopy = ::copyZaizao,
            onReplace = ::applyZaizao,
            onDismiss = zaizaoVm::close,
        )
    }
    var typographyOpen by remember { mutableStateOf(false) }
    if (typographyOpen) WorkspaceEditorTypographySheet(onDismiss = { typographyOpen = false })
    Scaffold(
        topBar = {
            WorkspaceEditorTopBar(
                navigation = {
                    if (role == WorkspaceEditorPaneRole.MAIN) TextButton(onClick = ::requestLeave) { Text(stringResource(R.string.back)) }
                    else IconButton(onClick = onSwitch) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.workspace_reference_back))
                    }
                },
                actions = {
                    if (role == WorkspaceEditorPaneRole.MAIN) IconButton(onClick = onSwitch) {
                        Icon(HugeIcons.Layout2Column, contentDescription = stringResource(R.string.workspace_reference_open))
                    } else IconButton(onClick = ::requestLeave) {
                        Icon(HugeIcons.FolderOpen, contentDescription = stringResource(R.string.workspace_reference_files))
                    }
                    if (!loading && !loadError) {
                        IconButton(onClick = { typographyOpen = true }) {
                            Icon(HugeIcons.TextFont, contentDescription = stringResource(R.string.editor_typography_title))
                        }
                    }
                    if (previewKind != WorkspaceFilePreviewKind.NONE && !loading && !loadError) {
                        TextButton(onClick = { showPreview = !showPreview }) {
                            Text(stringResource(if (showPreview) R.string.workspace_editor_source else R.string.workspace_editor_preview))
                        }
                    }
                    val opened = draftVm.openedText
                    if (editable && !loading && !loadError && validBaseline && opened != null &&
                        workspaceEditorCanRevert(opened, textState.text)) {
                        TextButton(onClick = {
                            textState.revertWorkspaceEditorTo(opened)
                            toaster.show(revertedMessage, type = ToastType.Info)
                        }) { Text(stringResource(R.string.workspace_editor_revert)) }
                    }
                    if (editable && !loading && !loadError) {
                        TextButton(onClick = { save() }, enabled = !saving && validBaseline && dirty) {
                            Text(stringResource(if (saving) R.string.workspace_editor_saving else R.string.common_save))
                        }
                    }
                },
            )
        }, containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        val webPreview = showPreview && previewKind == WorkspaceFilePreviewKind.WEB
        // innerPadding already holds the navigation bar; consumed, imePadding adds only the keyboard above it.
        Column(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).imePadding()) {
            when {
                loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                loadError -> Column(Modifier.fillMaxWidth().weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.workspace_editor_unavailable), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { loadRetry++ }) { Text(stringResource(R.string.workspace_editor_retry)) }
                }
                else -> {
                    if (editable && !validBaseline) {
                        Text(stringResource(R.string.workspace_editor_unknown_baseline), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                        TextButton(onClick = { reloadConfirm = true }) { Text(stringResource(R.string.workspace_editor_reload_discard)) }
                    }
                    val pause = draftVm.autoSavePause
                    if (editable && validBaseline && pause != null) {
                        Text(stringResource(pause.messageRes), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                        if (pause == WorkspaceEditorAutoSavePause.CONFLICT) {
                            TextButton(onClick = { reloadConfirm = true }) { Text(stringResource(R.string.workspace_editor_reload_discard)) }
                        }
                    }
                    if (showPreview && previewKind == WorkspaceFilePreviewKind.MARKDOWN) {
                        // The source editor has Undo/Redo in its toolbar; the preview gets the same pair (the way
                        // back from a 推敲 replacement), on the same native history, at the toolbar's size and place.
                        val display = LocalSettings.current.displaySetting
                        val undoRedo: @Composable () -> Unit = {
                            if (canTuiqiao) Row {
                                val size = WorkspaceEditorToolbarSize.of(display.workspaceEditorToolbarSize)
                                WorkspaceEditorToolbarTextButton(onClick = { textState.undoState.undo() }, size, enabled = textState.undoState.canUndo) {
                                    Text(stringResource(R.string.workspace_editor_undo))
                                }
                                WorkspaceEditorToolbarTextButton(onClick = { textState.undoState.redo() }, size, enabled = textState.undoState.canRedo) {
                                    Text(stringResource(R.string.workspace_editor_redo))
                                }
                            }
                        }
                        if (!display.workspaceEditorToolbarAtBottom) undoRedo()
                        WorkspaceMarkdownPreview(textState.text.toString(), Modifier.weight(1f), onTuiqiao = onTuiqiao, onZaizao = onZaizao)
                        if (display.workspaceEditorToolbarAtBottom) undoRedo()
                    } else if (webPreview) {
                        WorkspaceWebPreview(textState.text.toString(), fileName.substringAfterLast('.', "").lowercase() == "svg", Modifier.weight(1f))
                    } else {
                        WorkspaceMarkdownEditor(textState, previewKind == WorkspaceFilePreviewKind.MARKDOWN,
                            Modifier.weight(1f), readOnly = !editable || !validBaseline, formatKind = workspaceFormatKind(fileName),
                            onTuiqiao = onTuiqiao, onZaizao = onZaizao, active = active)
                    }
                }
            }
            WorkspaceEditorStatusBar(
                fileName = fileName,
                path = workspaceGuestPath(area, path),
                state = textState.takeIf { !loading && !loadError && !webPreview },
                withSelection = !showPreview,
            )
        }
    }
}

internal class WorkspaceEditorDraftViewModel : ViewModel() {
    val textState: TextFieldState by lazy { TextFieldState() }
    var loadedFileKey: String? = null
        internal set
    var originalText: String? by mutableStateOf(null)
    var baselineRevision: String? by mutableStateOf(null)
    /** Disk text when this session opened the file: the Revert target. Null after a pre-v3 draft. */
    var openedText: String? by mutableStateOf(null)
    var autoSavePause: WorkspaceEditorAutoSavePause? by mutableStateOf(null)
    var draftToken: String? = null
        private set
    var persistedDraftFingerprint: String? = null
        private set
    var persistedSelection: TextRange? = null
        private set
    var persistedBaselineRevision: String? = null
        private set
    internal var draftStore: WorkspaceEditorDraftStore? = null
    fun hasLiveDraft(fileKey: String): Boolean = loadedFileKey == fileKey
    fun bindDraftToken(token: String, fingerprint: String, selection: TextRange) {
        val old = draftToken
        if (old != null && old != token) draftStore?.delete(old)
        draftToken = token
        persistedDraftFingerprint = fingerprint
        persistedSelection = selection
        persistedBaselineRevision = baselineRevision
    }
    fun adoptRestoredDraft(text: String, selStart: Int, selEnd: Int, token: String, fileKey: String,
        originalText: String? = null, revision: String? = null, openedText: String? = null) {
        if (hasLiveDraft(fileKey)) {
            if (token != draftToken) draftStore?.delete(token)
            return
        }
        textState.setTextAndPlaceCursorAtEnd(text)
        val selection = TextRange(selStart, selEnd)
        textState.edit { this.selection = selection }
        textState.undoState.clearHistory()
        this.originalText = originalText
        baselineRevision = revision
        this.openedText = openedText
        loadedFileKey = fileKey
        draftToken = token
        persistedDraftFingerprint = workspaceEditorDraftFingerprint(text)
        persistedSelection = selection
        persistedBaselineRevision = revision
    }
    internal fun discardBoundDraft() {
        draftToken?.let { draftStore?.delete(it) }
        draftToken = null
        persistedDraftFingerprint = null
        persistedSelection = null
        persistedBaselineRevision = null
    }
    // Do not delete onCleared: the system may still restore its saved-state UUID.
}

private const val DRAFT_LOG_TAG = "WorkspaceFileEditor"

@Composable
private fun WorkspaceWebPreview(content: String, isSvg: Boolean, modifier: Modifier = Modifier) {
    val state = rememberWebViewState(data = content, baseUrl = "https://workspace-preview.invalid/",
        mimeType = if (isSvg) "image/svg+xml" else "text/html", settings = {
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        })
    WebView(state = state, modifier = modifier)
}
