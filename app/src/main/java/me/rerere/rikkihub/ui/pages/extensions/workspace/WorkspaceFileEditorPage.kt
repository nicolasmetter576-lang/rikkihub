package me.rerere.rikkihub.ui.pages.extensions.workspace

import me.rerere.hugeicons.stroke.TextFont
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.Layout2Column
import me.rerere.hugeicons.HugeIcons
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
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
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.BookChapterRef
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.BeatBoardSheet
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.BeatBoardEditor
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.BookChapterNav
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.bookChapterNav
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.rememberWorkspaceBook
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import androidx.compose.ui.platform.testTag
import me.rerere.hugeicons.stroke.BookOpen01
import me.rerere.workspace.WorkspaceManager
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
import me.rerere.workspace.WorkspaceTextSnapshot
import me.rerere.workspace.WorkspaceTextWriteResult
import org.koin.compose.koinInject
import me.rerere.rikkihub.data.ai.UsageRecorder
import me.rerere.rikkihub.data.book.QuoteLocator
import me.rerere.rikkihub.data.book.history.BookHistory
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.rememberLockedRanges
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.normalizedOffset
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.LockedDraftBar
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.LockUndoBar
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.LockUndo
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.EditorLockIcon
import me.rerere.rikkihub.ui.pages.extensions.workspace.book.ChapterLockSheet
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import me.rerere.rikkihub.data.book.history.FileLocks
import me.rerere.rikkihub.data.book.history.LockRefusedException
import me.rerere.rikkihub.data.book.history.BookLockedException
import me.rerere.rikkihub.data.book.history.BookHistoryActions
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkihub.data.ai.tavern.TavernRuntime
import me.rerere.rikkihub.data.ai.qubagu.QubaguRefiner
import me.rerere.rikkihub.data.ai.qubagu.Qubagu

/**
 * The editor page: the file being written, and a reference side the user swipes to (or opens with the
 * 对照 button) to read or edit a second file of the workspace next to it. The reference side starts as
 * the workspace file list at the main file's folder; a text file picked there opens on that side.
 */
@Composable
fun WorkspaceFileEditorPage(id: String, area: WorkspaceStorageArea, path: String, reveal: String = "") {
    val nav = LocalNavController.current
    val toaster = LocalToaster.current
    val sameFileMessage = stringResource(R.string.workspace_reference_same_file)
    // "AREA\u0000path" of the reference file; null = its side shows the file list.
    var referenceTarget by rememberSaveable(id, area, path) { mutableStateOf<String?>(null) }
    var showReference by rememberSaveable(id, area, path) { mutableStateOf(false) }
    val referenceHandle = remember(id, area, path) { WorkspaceEditorPaneHandle() }
    val book = rememberWorkspaceBook(id, area)
    val reference = referenceTarget?.let { target ->
        val (targetArea, targetPath) = target.split('\u0000', limit = 2)
        WorkspaceStorageArea.valueOf(targetArea) to targetPath
    }
    WorkspaceEditorPanes(
        showSecond = showReference,
        onShowSecondChange = { showReference = it },
        first = { active ->
            WorkspaceFileEditorPane(
                rememberWorkspaceEditorDocument(id, area, path, inBook = book != null), WorkspaceEditorPaneRole.MAIN, active,
                onExit = { nav.popBackStack() },
                onSwitch = { showReference = true },
                reveal = reveal,
                chapter = book?.entryByPath(path)?.let { BookChapterRef(id, it.id) },
                chapterNav = bookChapterNav(book, path) { target ->
                    nav.popBackStack()
                    nav.navigate(Screen.WorkspaceFileEditor(id, area.name, target))
                },
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
                    rememberWorkspaceEditorDocument(id, reference.first, reference.second), WorkspaceEditorPaneRole.REFERENCE, active,
                    onExit = { referenceTarget = null },
                    onSwitch = { showReference = false },
                    handle = referenceHandle,
                )
            }
        },
    )
}

internal enum class WorkspaceEditorPaneRole { MAIN, REFERENCE }

/** The text file an editor pane shows: where it is read from and saved to. */
internal class WorkspaceEditorDocument(
    /** Unique per file: keys the pane's drafts and view models. */
    val key: String,
    val fileName: String,
    /** Shown in the status bar. */
    val displayPath: String,
    val editable: Boolean,
    val read: suspend () -> WorkspaceTextSnapshot,
    val write: suspend (text: String, expectedRevision: String) -> WorkspaceTextWriteResult,
    /** Where an HTML preview reads its relative URLs from; null = the page stands alone. */
    val webResources: WorkspaceWebResources? = null,
    /** Opens the paged reading mode on this file (workspace Markdown / text files); null = not offered. */
    val openReader: (() -> Unit)? = null,
    /** Keeps the given text as a version of a book chapter before an assistant edit lands; a no-op outside books. */
    val keepVersion: (suspend (text: String) -> Unit)? = null,
    /** Opens this file's version history (book workspaces); null = not offered. */
    val openHistory: (() -> Unit)? = null,
    /** (workspace id, path) when the file is in a book: the editor shows and follows its final locks. */
    val lockTarget: Pair<String, String>? = null,
)

/** Relative URLs (stylesheets, scripts, images) of an HTML/SVG preview, served from local files. */
internal class WorkspaceWebResources(
    /** URL path of the previewed file, e.g. `/workspace/site/index.html`; its folder is the base. */
    val filePath: String,
    /** File for a requested URL path, or null when it is missing or outside what this preview may read. */
    val resolve: suspend (urlPath: String) -> File?,
)

@Composable
private fun rememberWorkspaceEditorDocument(id: String, area: WorkspaceStorageArea, path: String, inBook: Boolean = false): WorkspaceEditorDocument {
    val repository = koinInject<WorkspaceRepository>()
    val history = koinInject<BookHistory>()
    val nav = LocalNavController.current
    return remember(id, area, path, inBook) {
        WorkspaceEditorDocument(
            key = "$id\u0000$area\u0000$path",
            fileName = path.substringAfterLast('/').ifBlank { path },
            displayPath = workspaceGuestPath(area, path),
            editable = area == WorkspaceStorageArea.FILES,
            read = { repository.readTextSnapshotForPreview(id, area, path) },
            // 编辑器保存是你自己写的：段落锁跟着走（整章锁仍然挡住）
            write = { text, revision ->
                kotlinx.coroutines.withContext(BookHistory.sourceElement(me.rerere.rikkihub.data.book.history.HistorySource.Manual)) {
                    repository.writeTextWithRevision(id, path, text, revision)
                }
            },
            webResources = workspaceGuestPath(area, path).let { guestPath ->
                // A FILES page may use anything in the files area; a rootfs page only its own folder tree.
                val readable = if (area == WorkspaceStorageArea.FILES) "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/"
                else guestPath.substringBeforeLast('/') + "/"
                WorkspaceWebResources(guestPath) { urlPath ->
                    if (urlPath.startsWith(readable)) repository.resolveRootfsFile(id, urlPath) else null
                }
            },
            openReader = { nav.navigate(Screen.WorkspaceReader(id, area.name, path)) }.takeIf { isReadableName(path) },
            keepVersion = if (area == WorkspaceStorageArea.FILES) ({ text -> history.checkpoint(id, path, text) }) else null,
            openHistory = { nav.navigate(Screen.BookHistory(id, path)) }.takeIf { inBook && area == WorkspaceStorageArea.FILES },
            lockTarget = (id to path).takeIf { area == WorkspaceStorageArea.FILES && history.isBook(id) },
        )
    }
}

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
 * MAIN leaves the page, REFERENCE goes back to the file list. [onSwitch] slides to the other side
 * (null: there is no other side, as in the file manager).
 */
@Composable
internal fun WorkspaceFileEditorPane(
    document: WorkspaceEditorDocument,
    role: WorkspaceEditorPaneRole,
    active: Boolean,
    onExit: () -> Unit,
    onSwitch: (() -> Unit)?,
    beforeLeave: () -> Boolean = { true },
    handle: WorkspaceEditorPaneHandle? = null,
    /** Set when the file is a chapter of a book workspace: the status bar offers the neighbouring chapters. */
    chapterNav: BookChapterNav? = null,
    /** Set when the file is a chapter of a book workspace: the toolbar offers 节拍. */
    chapter: BookChapterRef? = null,
    /** Text to select and scroll to once, when the file first opens; empty = none. */
    reveal: String = "",
) {
    val tuiqiaoCache = koinInject<WorkspaceTuiqiaoCache>()
    val zaizaoCache = koinInject<WorkspaceZaizaoCache>()
    val settingsStore = koinInject<SettingsStore>()
    val tavernRuntime = koinInject<TavernRuntime>()
    val providerManager = koinInject<ProviderManager>()
    val usageRecorder = koinInject<UsageRecorder>()
    val toaster = LocalToaster.current
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val draftStore = remember {
        WorkspaceEditorDraftStore(File(context.noBackupFilesDir, "workspace_editor_drafts"),
            onError = { Log.w(DRAFT_LOG_TAG, "workspace editor draft io failure", it) })
    }
    // The chapter to open instead of closing the page, once this file can be left.
    var leaveTarget by remember(document.key) { mutableStateOf<String?>(null) }
    fun exit() {
        val target = leaveTarget
        if (target != null && chapterNav != null) chapterNav.open(target) else onExit()
    }
    // 定稿锁：整章锁定时只读；段落锁画竖条、挡住会改到它的推敲、再造、节拍换稿
    val bookHistory = koinInject<BookHistory>()
    val historyActions = koinInject<BookHistoryActions>()
    val lockTarget = document.lockTarget
    val fileLocks by remember(lockTarget) { lockTarget?.let { (ws, p) -> bookHistory.lockFlow(ws, p) } ?: flowOf(null) }
        .collectAsStateWithLifecycle(null)
    val chapterLocked = fileLocks?.chapter != null
    val editable = document.editable
    val fileName = document.fileName
    val previewKind = workspaceFilePreviewKind(fileName)
    val fileKey = document.key
    // HTML/SVG open as the rendered page; the source is one tap away.
    var showPreview by rememberSaveable(fileKey) { mutableStateOf(previewKind == WorkspaceFilePreviewKind.WEB) }
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
    fun canAutoSave() = editable && !chapterLocked && !loading && !loadError && hasValidBaseline() && draftVm.autoSavePause == null
    val dirty = isDirty()
    val validBaseline = hasValidBaseline()
    val savedMessage = stringResource(R.string.workspace_editor_saved)
    val lockedSaveMessage = stringResource(R.string.book_lock_save_refused)
    val failedMessage = stringResource(R.string.workspace_editor_save_failed)
    val conflictMessage = stringResource(R.string.workspace_editor_conflict)
    val revertedMessage = stringResource(R.string.workspace_editor_reverted)
    val beatsInsertedMessage = stringResource(R.string.book_beats_inserted_done)
    val revealMissingMessage = stringResource(R.string.workspace_editor_reveal_missing)
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
    val canTuiqiao = editable && !chapterLocked && tuiqiaoConfig.enabledFor(fileName) && !loading && !loadError &&
        validBaseline && draftVm.autoSavePause == null
    val lockedRanges = rememberLockedRanges(textState, fileLocks.takeUnless { chapterLocked })
    val lockBlockedMessage = stringResource(R.string.book_lock_apply_blocked)
    /** 推敲、再造、节拍换稿、八股：改动范围碰到锁定段落就不应用。 */
    fun touchesLock(start: Int, end: Int): Boolean =
        lockedRanges.values.any { start <= it.last && it.first < maxOf(end, start + 1) }
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
    // 八股 marks need what autosave needs too: applying a suggestion edits the file.
    val canQubagu = editable && !chapterLocked && workspaceFormatKind(fileName) != null && !loading && !loadError && validBaseline && draftVm.autoSavePause == null
    val qubaguVm: WorkspaceQubaguViewModel = viewModel(key = "workspace-qubagu:$fileKey") {
        val engine = Qubagu(tavernRuntime.regexFactory)
        WorkspaceQubaguViewModel(engine, QubaguRefiner(engine, providerManager, usageRecorder), settingsStore)
    }
    LaunchedEffect(canQubagu, showPreview) {
        if ((!canQubagu || showPreview) && qubaguVm.active) qubaguVm.stop()
    }
    val qubaguMessages = mapOf(
        WorkspaceQubaguScan.NO_RULES to stringResource(R.string.qubagu_scan_no_rules),
        WorkspaceQubaguScan.NOTHING_FOUND to stringResource(R.string.qubagu_test_none),
        WorkspaceQubaguScan.TOO_LARGE to stringResource(R.string.qubagu_scan_too_large, WorkspaceQubaguViewModel.MAX_CHARS),
    )
    val qubaguChangedMessage = stringResource(R.string.qubagu_scan_changed)
    /** Book chapters: keep the text as it is now as a version, then run [apply] (an assistant edit). */
    fun keepVersionThen(apply: () -> Unit) {
        val keep = document.keepVersion ?: return apply()
        val before = textState.text.toString()
        scope.launch {
            runCatching { keep(before) }.onFailure { if (it is CancellationException) throw it }
            apply()
        }
    }
    val qubagu = if (!canQubagu) null else WorkspaceEditorQubagu(
        active = qubaguVm.active,
        scanning = qubaguVm.scanning,
        marks = qubaguVm.marks?.marks.orEmpty(),
        onToggle = {
            if (qubaguVm.active) qubaguVm.stop()
            else keepVersionThen {
                qubaguVm.start(textState.text.toString()) { result -> qubaguMessages[result]?.let { toaster.show(it, type = ToastType.Info) } }
            }
        },
        onOpen = qubaguVm::open,
    )
    // Anything else that changes the text under the marks (Revert, a restored draft) closes them.
    LaunchedEffect(qubaguVm) {
        snapshotFlow { textState.text to qubaguVm.marks }.collect { (text, marks) ->
            if (marks != null && !marks.matches(text)) {
                qubaguVm.stop()
                toaster.show(qubaguChangedMessage, type = ToastType.Info)
            }
        }
    }
    /** Applies one mark's replacement to the text (one undoable, autosaved edit) if the marks still match the text. */
    fun applyQubagu(change: Pair<WorkspaceQubaguEdit, WorkspaceQubaguMarks>) {
        val marks = qubaguVm.marks ?: return
        if (!marks.matches(textState.text)) {
            qubaguVm.stop()
            toaster.show(qubaguChangedMessage, type = ToastType.Error)
            return
        }
        val (edit, next) = change
        if (touchesLock(edit.start, edit.end)) {
            toaster.show(lockBlockedMessage, type = ToastType.Info)
            return
        }
        textState.edit {
            replace(edit.start, edit.end, edit.text)
            selection = TextRange(edit.start + edit.text.length)
        }
        qubaguVm.update(next)
    }
    val clipboard = LocalClipboard.current
    fun copyTuiqiao(candidate: TuiqiaoCandidate) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("tuiqiao", candidate.text)))
            toaster.show(tuiqiaoCopiedMessage, type = ToastType.Success)
        }
    }
    /** Replaces the session's range if it still holds the original text: one undoable edit, autosaved. */
    fun applyTuiqiao(candidate: TuiqiaoCandidate) = keepVersionThen {
        val session = tuiqiaoVm.session ?: return@keepVersionThen
        val text = textState.text
        if (!canTuiqiao || session.end > text.length || text.substring(session.start, session.end) != session.original) {
            toaster.show(tuiqiaoChangedMessage, type = ToastType.Error)
            return@keepVersionThen
        }
        if (touchesLock(session.start, session.end)) {
            toaster.show(lockBlockedMessage, type = ToastType.Info)
            return@keepVersionThen
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
    fun applyZaizao(draft: WorkspaceZaizaoDraft) = keepVersionThen {
        val entry = zaizaoVm.shown ?: return@keepVersionThen
        if (!canTuiqiao || !entry.matches(textState.text.toString())) {
            toaster.show(tuiqiaoChangedMessage, type = ToastType.Error)
            return@keepVersionThen
        }
        if (touchesLock(entry.liveStart, entry.liveEnd)) {
            toaster.show(lockBlockedMessage, type = ToastType.Info)
            return@keepVersionThen
        }
        val start = entry.liveStart
        textState.edit {
            replace(start, entry.liveEnd, draft.result)
            selection = TextRange(start + draft.result.length)
        }
        zaizaoVm.close()
        toaster.show(tuiqiaoReplacedMessage, type = ToastType.Success)
    }

    fun requestLeave(target: String? = null) {
        if (!beforeLeave()) return
        leaveTarget = target
        when {
            !saving && !(editable && draftVm.loadedFileKey == fileKey && isDirty()) -> exit()
            canAutoSave() -> closeWhenSaved = true // flush autosave, then close without asking
            else -> leaveConfirm = true
        }
    }
    var revealed by rememberSaveable(fileKey) { mutableStateOf(reveal.isEmpty()) }
    var revealRange by remember(fileKey) { mutableStateOf<TextRange?>(null) }
    LaunchedEffect(loading, loadError) {
        if (loading || loadError || revealed) return@LaunchedEffect
        revealed = true
        val found = QuoteLocator.locate(textState.text.toString(), reveal)
        if (found == null) toaster.show(revealMissingMessage, type = ToastType.Info)
        else {
            showPreview = false
            revealRange = TextRange(found.first, found.last + 1)
        }
    }
    var beatsOpen by rememberSaveable(fileKey) { mutableStateOf(false) }
    // Writing drafts into the text needs what autosave needs, like 推敲.
    val canEditText = editable && !chapterLocked && !loading && !loadError && validBaseline && draftVm.autoSavePause == null && !qubaguVm.active
    val onBeats: (() -> Unit)? = if (chapter == null || !editable || chapterLocked || loading || loadError) null else ({ beatsOpen = true })
    if (beatsOpen && chapter != null) BeatBoardSheet(
        workspaceId = chapter.workspaceId,
        chapterId = chapter.chapterId,
        editor = BeatBoardEditor(
            path = document.displayPath,
            text = textState.text.toString(),
            cursor = textState.selection.max,
            editable = canEditText,
            insert = { insertions -> keepVersionThen {
                textState.edit {
                    insertions.forEach { replace(it.offset, it.offset, it.text) }
                    val last = insertions.last()
                    selection = TextRange(last.offset + last.text.trimEnd('\n').length)
                }
                toaster.show(beatsInsertedMessage, type = ToastType.Success)
            } },
            replace = { range, text -> keepVersionThen {
                if (touchesLock(range.first, range.last + 1)) {
                    toaster.show(lockBlockedMessage, type = ToastType.Info)
                    return@keepVersionThen
                }
                textState.edit {
                    replace(range.first, range.last + 1, text)
                    selection = TextRange(range.first + text.length)
                }
                toaster.show(tuiqiaoReplacedMessage, type = ToastType.Success)
            } },
            copy = { text ->
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("beat", text)))
                    toaster.show(tuiqiaoCopiedMessage, type = ToastType.Success)
                }
            },
        ),
        onOpenSettings = { nav.navigate(Screen.SettingBook) },
        onDismiss = { beatsOpen = false },
    )
    /** [auto]: background save — silent on success, skipped when clean or paused; failure pauses it. */
    fun save(closeAfter: Boolean = false, auto: Boolean = false) {
        if (saving || loading || loadError || !editable || chapterLocked || !hasValidBaseline()) return
        if (auto && (draftVm.autoSavePause != null || !isDirty())) return
        val frozenText = textState.text.toString()
        val expectedRevision = draftVm.baselineRevision ?: return
        saving = true
        scope.launch {
            try {
                val result = document.write(frozenText, expectedRevision)
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
                    exit()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (locked: BookLockedException) {
                // 万一锁比界面先到：不写，修改留在草稿里（锁一到编辑器就只读，并给出解锁并保存）
                toaster.show(lockedSaveMessage, type = ToastType.Error)
                if (closeAfter) leaveConfirm = true
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
                exit()
            }
            canAutoSave() -> save(closeAfter = true, auto = true)
            else -> leaveConfirm = true
        }
    }
    // The reference side's back key slides home; leaving it (for the file list) is its folder button.
    BackHandler(enabled = active) { if (role == WorkspaceEditorPaneRole.MAIN) requestLeave() else onSwitch?.invoke() }
    // The side that slides away writes its text at once instead of after the usual pause.
    LaunchedEffect(active) { if (!active) latestSave(false, true) }
    // Back in front with nothing unsaved: show what is on disk now, or the next key would end in a conflict.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, document.key) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (loading || loadError || saving || draftVm.loadedFileKey != fileKey || !hasValidBaseline() || isDirty()) return@repeatOnLifecycle
            val disk = runCatching { document.read() }.getOrNull() ?: return@repeatOnLifecycle
            if (!saving && !isDirty() && disk.revision != draftVm.baselineRevision) draftVm.adoptDiskText(disk.content, disk.revision)
        }
    }
    if (handle != null) {
        SideEffect {
            handle.blocksLeave = { saving || editable && draftVm.loadedFileKey == fileKey && isDirty() }
            handle.requestLeave = { requestLeave() }
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
            val snapshot = document.read()
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
        canSave = validBaseline && !reloadConfirm && !chapterLocked,
        onSave = { save(closeAfter = true) },
        onDiscard = {
            draftVm.discardBoundDraft()
            leaveConfirm = false
            if (reloadConfirm) {
                reloadConfirm = false
                draftVm.loadedFileKey = null
                loadRetry++
            } else exit()
        },
        onContinue = {
            leaveConfirm = false
            reloadConfirm = false
            leaveTarget = null
        },
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
    val qubaguOpened = qubaguVm.opened
    val qubaguMark = qubaguOpened?.let { qubaguVm.marks?.marks?.getOrNull(it) }
    if (qubaguOpened != null && qubaguMark != null) {
        WorkspaceQubaguSheet(
            mark = qubaguMark,
            config = LocalSettings.current.qubagu,
            ai = qubaguVm.ai[qubaguMark.annotation],
            onIgnore = { qubaguVm.ignore(qubaguOpened) },
            onUndo = { qubaguVm.marks?.let { applyQubagu(it.undo(qubaguOpened)) } },
            onApply = { text ->
                qubaguVm.marks?.let { applyQubagu(it.apply(qubaguOpened, text)) }
                qubaguVm.dismiss()
            },
            onRequestAi = { qubaguVm.requestAi(qubaguOpened, textState.text.toString()) },
            onDismiss = qubaguVm::dismiss,
        )
    }
    var typographyOpen by remember { mutableStateOf(false) }
    if (typographyOpen) WorkspaceEditorTypographySheet(onDismiss = { typographyOpen = false })
    // The reader reads the file from disk: unsaved edits are saved first, then it opens.
    var readerAfterSave by remember { mutableStateOf(false) }
    LaunchedEffect(readerAfterSave, saving) {
        if (readerAfterSave && !saving) {
            readerAfterSave = false
            if (!(editable && isDirty())) document.openReader?.invoke()
        }
    }
    // ---- 定稿锁：锁面板、选择菜单、撤销提示条、只读提示、锁定时留下的草稿 ----
    var lockPanelOpen by remember(fileKey) { mutableStateOf(false) }
    var lockBusy by remember(fileKey) { mutableStateOf(false) }
    var lockUndo by remember(fileKey) { mutableStateOf<LockUndo?>(null) }
    val lockTooShort = stringResource(R.string.book_lock_too_short)
    val lockOverlap = stringResource(R.string.book_lock_overlap)
    val lockTip = stringResource(R.string.book_lock_tip)
    val lockUndoFailed = stringResource(R.string.book_lock_undo_failed)
    /** 加锁对着磁盘上的稿子：有没保存的修改先存，存好了再加。 */
    fun lockWork(locked: Boolean, run: suspend (ws: String, path: String, revision: String) -> FileLocks?) {
        val (ws, lockPath) = lockTarget ?: return
        if (lockBusy) return
        lockBusy = true
        scope.launch {
            try {
                if (locked && editable && isDirty()) {
                    save(auto = true)
                    snapshotFlow { saving }.first { !it }
                    if (isDirty()) return@launch
                }
                val revision = draftVm.baselineRevision ?: return@launch
                val previous = run(ws, lockPath, revision)
                lockUndo = LockUndo(locked, 1, mapOf(lockPath to previous))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: LockRefusedException) {
                toaster.show(if (refused.reason == LockRefusedException.Reason.OVERLAP) lockOverlap else lockTooShort, type = ToastType.Info)
            } catch (error: Exception) {
                toaster.show(error.message ?: error.javaClass.simpleName, type = ToastType.Error)
            } finally {
                lockBusy = false
            }
        }
    }
    fun lockParagraphs(start: Int, end: Int) = lockWork(true) { ws, lockPath, revision ->
        val text = textState.text
        historyActions.lockPassage(ws, lockPath, revision, normalizedOffset(text, start), normalizedOffset(text, end)).second
    }
    fun unlockPassages(ids: Set<String>) = lockWork(false) { ws, lockPath, _ ->
        bookHistory.unlock(ws, lockPath, chapter = false, passageIds = ids)
    }
    val lockMenu = if (lockTarget == null || chapterLocked || !editable || loading || loadError) null else WorkspaceEditorLockMenu(
        touches = { start, end -> lockedRanges.values.any { start <= it.last && it.first < maxOf(end, start + 1) } },
        onLock = ::lockParagraphs,
        onUnlock = { start, end ->
            unlockPassages(lockedRanges.filterValues { start <= it.last && it.first < maxOf(end, start + 1) }.keys)
        },
    )
    if (lockPanelOpen && lockTarget != null) {
        ChapterLockSheet(
            locks = fileLocks,
            hasSelection = !textState.selection.collapsed,
            busy = lockBusy || loading || loadError || !validBaseline,
            onJump = { passage ->
                lockedRanges[passage.id]?.let { range ->
                    lockPanelOpen = false
                    showPreview = false
                    revealRange = TextRange(range.first, range.last + 1)
                }
            },
            onLockChapter = {
                lockPanelOpen = false
                lockWork(true) { ws, lockPath, revision -> historyActions.lockChapter(ws, lockPath, revision) }
            },
            onUnlockChapter = {
                lockPanelOpen = false
                lockWork(false) { ws, lockPath, _ -> bookHistory.unlock(ws, lockPath, chapter = true, passageIds = emptySet()) }
            },
            onLockParagraph = {
                lockPanelOpen = false
                val selection = textState.selection
                lockParagraphs(selection.min, selection.max)
            },
            onUnlockPassage = { unlockPassages(setOf(it.id)) },
            onDismiss = { lockPanelOpen = false },
        )
    }
    // The history compares with the file on disk, so unsaved edits are saved first.
    val onHistory: (() -> Unit)? = document.openHistory?.takeIf { role == WorkspaceEditorPaneRole.MAIN && !loading && !loadError }
        ?.let { open -> { if (editable && isDirty()) save(); open() } }
    Scaffold(
        topBar = {
            WorkspaceEditorTopBar(
                navigation = {
                    if (role == WorkspaceEditorPaneRole.MAIN) TextButton(onClick = { requestLeave() }) { Text(stringResource(R.string.back)) }
                    else IconButton(onClick = { onSwitch?.invoke() }) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.workspace_reference_back))
                    }
                },
                actions = {
                    if (role == WorkspaceEditorPaneRole.MAIN) {
                        if (onSwitch != null) IconButton(onClick = onSwitch) {
                            Icon(HugeIcons.Layout2Column, contentDescription = stringResource(R.string.workspace_reference_open))
                        }
                    } else IconButton(onClick = { requestLeave() }) {
                        Icon(HugeIcons.FolderOpen, contentDescription = stringResource(R.string.workspace_reference_files))
                    }
                    if (!loading && !loadError) {
                        document.openReader?.let { openReader ->
                            IconButton(
                                onClick = {
                                    if (editable && isDirty()) {
                                        save()
                                        readerAfterSave = true
                                    } else openReader()
                                },
                                modifier = Modifier.testTag("workspace_editor_reader"),
                            ) {
                                Icon(HugeIcons.BookOpen01, contentDescription = stringResource(R.string.workspace_reader_open))
                            }
                        }
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
                    // 整章锁定后正文只读，“还原”也是一次改动，不给
                    if (editable && !chapterLocked && !loading && !loadError && validBaseline && opened != null &&
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
                        WorkspaceWebPreview(textState.text.toString(), fileName.substringAfterLast('.', "").lowercase() == "svg",
                            document.webResources, Modifier.weight(1f))
                    } else {
                        WorkspaceMarkdownEditor(textState, previewKind == WorkspaceFilePreviewKind.MARKDOWN,
                            Modifier.weight(1f), readOnly = !editable || !validBaseline || chapterLocked, formatKind = workspaceFormatKind(fileName),
                            onTuiqiao = onTuiqiao, onZaizao = onZaizao, active = active, qubagu = qubagu, onBeats = onBeats, onHistory = onHistory,
                            reveal = revealRange, onRevealed = { revealRange = null },
                            chapterLocked = chapterLocked,
                            lockedRanges = lockedRanges.values,
                            lockMenu = lockMenu,
                            findLock = lockTarget?.let { { EditorLockIcon(fileLocks, Modifier.clickable { lockPanelOpen = true }.padding(horizontal = 8.dp)) } },
                            // 只读时第一次点按正文：提示怎么解锁（每章一次）
                            onReadOnlyTap = lockTarget?.takeIf { chapterLocked }?.let { (ws, lockPath) ->
                                { scope.launch { if (!bookHistory.tipShown(ws, lockPath)) toaster.show(lockTip, type = ToastType.Info) } }
                            })
                    }
                }
            }
            if (chapterLocked && editable && !loading && draftVm.loadedFileKey == fileKey && isDirty()) {
                LockedDraftBar(
                    onUnlockAndSave = {
                        val (ws, lockPath) = lockTarget ?: return@LockedDraftBar
                        scope.launch {
                            val previous = bookHistory.unlock(ws, lockPath, chapter = true, passageIds = emptySet())
                            lockUndo = LockUndo(false, 1, mapOf(lockPath to previous))
                            snapshotFlow { fileLocks?.chapter }.first { it == null }
                            save()
                        }
                    },
                    onCopy = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("draft", textState.text.toString())))
                            toaster.show(tuiqiaoCopiedMessage, type = ToastType.Success)
                        }
                    },
                    onDiscard = {
                        draftVm.discardBoundDraft()
                        draftVm.loadedFileKey = null
                        loadRetry++
                    },
                )
            }
            LockUndoBar(
                lockUndo,
                onUndo = { undo ->
                    lockUndo = null
                    val ws = lockTarget?.first ?: return@LockUndoBar
                    scope.launch {
                        val failed = undo.previous.count { (lockPath, previous) -> !bookHistory.restoreLocks(ws, lockPath, previous) }
                        if (failed > 0) toaster.show(lockUndoFailed, type = ToastType.Error)
                    }
                },
                onTimeout = { lockUndo = null },
            )
            WorkspaceEditorStatusBar(
                fileName = fileName,
                path = document.displayPath,
                state = textState.takeIf { !loading && !loadError && !webPreview },
                withSelection = !showPreview,
                chapterNav = chapterNav,
                onChapter = { requestLeave(it) },
                lock = lockTarget?.let { { EditorLockIcon(fileLocks, Modifier.testTag("book-lock-status")) } },
                onLock = { lockPanelOpen = true },
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
    /** The clean file changed on disk: shows that version, keeping the cursor inside it. */
    fun adoptDiskText(text: String, revision: String) {
        val selection = textState.selection
        textState.setTextAndPlaceCursorAtEnd(text)
        textState.edit { this.selection = TextRange(selection.start.coerceAtMost(text.length), selection.end.coerceAtMost(text.length)) }
        textState.undoState.clearHistory()
        originalText = text
        baselineRevision = revision
        openedText = text
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

private const val WEB_PREVIEW_HOST = "workspace-preview.invalid"

@Composable
private fun WorkspaceWebPreview(content: String, isSvg: Boolean, resources: WorkspaceWebResources?, modifier: Modifier = Modifier) {
    val basePath = resources?.filePath?.split('/')?.joinToString("/") { Uri.encode(it) } ?: "/"
    val state = rememberWebViewState(data = content, baseUrl = "https://$WEB_PREVIEW_HOST$basePath",
        mimeType = if (isSvg) "image/svg+xml" else "text/html",
        interceptRequest = resources?.let { { uri -> serveWorkspaceWebResource(uri, it) } },
        settings = {
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        })
    WebView(state = state, modifier = modifier)
}

/** Runs on a WebView IO thread. Requests to the preview host never reach the network: a miss is a 404. */
private fun serveWorkspaceWebResource(uri: Uri, resources: WorkspaceWebResources): WebResourceResponse? {
    if (!uri.host.equals(WEB_PREVIEW_HOST, ignoreCase = true)) return null
    val file = uri.path?.let { path -> runCatching { runBlocking { resources.resolve(path) } }.getOrNull() }
        ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        ?: if (file.extension.equals("js", ignoreCase = true) || file.extension.equals("mjs", ignoreCase = true)) "text/javascript"
        else "application/octet-stream"
    val textual = mimeType.startsWith("text/") || mimeType.endsWith("json") || mimeType.endsWith("xml") || mimeType.endsWith("javascript")
    return WebResourceResponse(mimeType, if (textual) "utf-8" else null, file.inputStream())
}
