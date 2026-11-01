package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.launch
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalToaster
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.R
import me.rerere.rikkihub.ui.context.LocalSettings
import me.rerere.rikkihub.ui.theme.JetbrainsMono

internal data class WorkspaceMarkdownPalette(val marker: Color, val link: Color, val quote: Color, val code: Color, val tableHeader: Color = Color.Unspecified)
internal fun workspaceMarkdownSpanStyle(span: WorkspaceMarkdownSpan, palette: WorkspaceMarkdownPalette, headingSizes: Boolean, baseSize: TextUnit): SpanStyle = when (span.style) {
    WorkspaceMarkdownStyle.HEADING -> SpanStyle(fontWeight = FontWeight.Bold,
        fontSize = if (headingSizes) baseSize * when (span.level) { 1 -> 1.6f; 2 -> 1.4f; 3 -> 1.2f; else -> 1.1f } else TextUnit.Unspecified)
    WorkspaceMarkdownStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    WorkspaceMarkdownStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    WorkspaceMarkdownStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    WorkspaceMarkdownStyle.CODE -> SpanStyle(fontFamily = JetbrainsMono, background = palette.code)
    WorkspaceMarkdownStyle.LINK -> SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline)
    WorkspaceMarkdownStyle.QUOTE -> SpanStyle(color = palette.quote, fontStyle = FontStyle.Italic)
    WorkspaceMarkdownStyle.LIST -> SpanStyle(fontWeight = FontWeight.Medium)
    WorkspaceMarkdownStyle.TASK -> SpanStyle(fontWeight = FontWeight.Bold)
    WorkspaceMarkdownStyle.TABLE -> SpanStyle(fontFamily = JetbrainsMono)
    WorkspaceMarkdownStyle.TABLE_HEADER -> SpanStyle(background = palette.tableHeader)
    WorkspaceMarkdownStyle.TABLE_DELIMITER -> SpanStyle(color = palette.marker.copy(alpha = 0.6f))
    WorkspaceMarkdownStyle.MARKER -> SpanStyle(color = palette.marker)
}

/** Highlight inputs held in snapshot state. The [OutputTransformation] is a SINGLE remembered
 * instance: BasicTextField keys `remember(state, codepointTransformation, outputTransformation)`
 * for its TransformedTextFieldState and restarts the whole IME session (keyboard retract + popup)
 * whenever the instance changes. Foundation computes the transformed text via derivedStateOf, so
 * snapshot reads inside transformOutput already drive refresh — a stable instance loses nothing.
 * Styles ONLY: no buffer insert/delete/replace; source and display UTF-16 offsets are identical.
 */
private class WorkspaceMarkdownHighlightState {
    var analysis by mutableStateOf<WorkspaceMarkdownAnalysis?>(null)
    var markdown by mutableStateOf(false)
    var match by mutableStateOf(SpanStyle())
    var currentMatch by mutableStateOf(SpanStyle())
    var palette by mutableStateOf(WorkspaceMarkdownPalette(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified))
    var headingSizes by mutableStateOf(false)
    var baseSize by mutableStateOf(13.sp)
}

/** One state per loaded document, supplied by VM (never rememberTextFieldState / full Bundle).
 * This fork's public edit -> commitEdit calls recordEditForUndo(NeverMerge). Toolbar and IME use
 * ONE native undoState, not a parallel custom stack. See task-two-android-decisions.md for bytecode
 * evidence. Device IME/compose-region behavior still needs instrumentation / actual device checks.
 *
 * Also hosts the find bar (matches highlighted through the same OutputTransformation, the current one
 * scrolled into view) and auto-closing pairs (an InputTransformation, so only user input is affected)
 * with their fill box drawn over the text from the text layout.
 */
@Composable
internal fun WorkspaceMarkdownEditor(
    state: TextFieldState,
    markdown: Boolean,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    /** Non-null offers 排版 in the toolbar (Markdown and plain text files). */
    formatKind: WorkspaceFormatKind? = null,
    onSourceChange: (String) -> Unit = {},
    /** Source offsets of a selection to refine; null hides 推敲 from the selection menu and toolbar. */
    onTuiqiao: ((start: Int, end: Int) -> Unit)? = null,
    onZaizao: ((start: Int, end: Int) -> Unit)? = null,
    /** False while the editor is the off-screen side of the editor page: its find bar leaves the back key alone. */
    active: Boolean = true,
) {
    val headingSizes = LocalSettings.current.displaySetting.workspaceMarkdownHeadingSizes
    val autoPair = LocalSettings.current.displaySetting.workspaceEditorAutoPair
    val colors = MaterialTheme.colorScheme
    val palette = WorkspaceMarkdownPalette(colors.tertiary, colors.primary, colors.onSurfaceVariant, colors.surfaceContainerHighest, colors.secondaryContainer)
    val sourceChanged by rememberUpdatedState(onSourceChange)
    val highlight = remember(state) { WorkspaceMarkdownHighlightState() }
    val find = remember(state) { WorkspaceFindState() }
    val pairs = remember(state) { WorkspacePairBoxState() }
    val layout = remember(state) { WorkspaceEditorLayout() }
    val scroll = remember(state) { ScrollState(0) }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val resources = LocalResources.current
    // contentEquals dedups selection-only changes: every TextFieldState edit, even a pure cursor
    // move, replaces the text object (buffer.toString()), so identity checks would fire needlessly.
    LaunchedEffect(state) {
        snapshotFlow { state.text }
            .distinctUntilChanged { a, b -> a === b || (a.length == b.length && a.contentEquals(b)) }
            .collect { sourceChanged(it.toString()) }
    }
    // The last analysis stays on screen while typing (re-anchored by the transformation below);
    // a fresh one replaces it once input pauses. The first analysis of a document is not delayed.
    LaunchedEffect(state, markdown) {
        if (!markdown) return@LaunchedEffect
        snapshotFlow { state.text }
            .distinctUntilChanged { a, b -> a === b || (a.length == b.length && a.contentEquals(b)) }
            .collectLatest { source ->
                if (source.length > WorkspaceMarkdownAnalyzer.MAX_SOURCE_CHARS) {
                    highlight.analysis = null
                    return@collectLatest
                }
                if (highlight.analysis != null) delay(120)
                highlight.analysis = withContext(Dispatchers.Default) {
                    val analyzed = source.toString()
                    val job = currentCoroutineContext()
                    val deadline = System.nanoTime() + 300_000_000L
                    try {
                        WorkspaceMarkdownAnalyzer.analyze(analyzed) {
                            job.ensureActive()
                            if (System.nanoTime() > deadline) throw MarkdownAnalysisBudgetExceeded()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        WorkspaceMarkdownAnalysis(analyzed, emptyList(), emptyList(), true)
                    }
                }
            }
    }

    /** Selects the current match in the editor (no undo step) and scrolls it into view. */
    fun revealMatch() {
        val start = find.currentStart ?: return
        val end = start + find.matchLength
        if (end > state.text.length) return
        state.edit { selection = TextRange(start, end) }
        scope.launch { layout.scrollIntoView(scroll, start) }
    }
    // Matches follow the text, the query and the case option. Only a new query / option or a replacement
    // moves the editor to a match: typing in the editor with the bar open never jumps the caret.
    LaunchedEffect(state, find) {
        var lastQuery: String? = null
        var lastCase = false
        snapshotFlow { if (!find.open) null else Triple(state.text, find.query.text.toString(), find.caseSensitive) }
            // Selecting a match replaces the text object too: compare contents, not identity.
            .distinctUntilChanged { a, b ->
                a === b || a != null && b != null && a.second == b.second && a.third == b.third &&
                    (a.first === b.first || a.first.length == b.first.length && a.first.contentEquals(b.first))
            }
            .collectLatest { key ->
                if (key == null) {
                    find.matches = IntArray(0)
                    find.matchedFor = null
                    find.current = -1
                    lastQuery = null
                    return@collectLatest
                }
                val (text, query, caseSensitive) = key
                if (lastQuery != null) delay(80)
                val matches = withContext(Dispatchers.Default) { workspaceFindMatches(text, query, caseSensitive) }
                val queryChanged = query != lastQuery || caseSensitive != lastCase
                val anchor = find.pendingAnchor ?: if (queryChanged) (find.currentStart ?: state.selection.min) else (find.currentStart ?: 0)
                val reveal = queryChanged || find.pendingAnchor != null
                find.pendingAnchor = null
                lastQuery = query
                lastCase = caseSensitive
                find.matchLength = query.length
                find.matches = matches
                find.matchedFor = query to caseSensitive
                find.current = workspaceFindCurrent(matches, anchor)
                if (reveal) revealMatch()
            }
    }
    fun step(delta: Int) {
        val count = find.matches.size
        if (count == 0) return
        find.current = (find.current + delta).mod(count)
        revealMatch()
    }
    fun replaceCurrent() {
        val start = find.currentStart ?: return
        val length = find.matchLength
        val query = find.query.text
        if (length == 0 || start + length > state.text.length ||
            !state.text.regionMatches(start, query, 0, length, ignoreCase = !find.caseSensitive)) return
        val replacement = find.replacement.text.toString()
        find.pendingAnchor = start + replacement.length
        state.edit {
            replace(start, start + length, replacement)
            selection = TextRange(start, start + replacement.length)
        }
        pairs.clear()
    }
    fun replaceAll() {
        val query = find.query.text.toString()
        if (query.isEmpty()) return
        val matches = workspaceFindMatches(state.text, query, find.caseSensitive, limit = Int.MAX_VALUE)
        if (matches.isEmpty()) return
        val replacement = find.replacement.text.toString()
        // One edit: one undo step for the whole replacement.
        state.edit { for (start in matches.reversed()) replace(start, start + query.length, replacement) }
        pairs.clear()
        toaster.show(resources.getString(R.string.workspace_find_replaced, matches.size), type = ToastType.Success)
    }
    fun openFind() {
        val selection = state.selection
        val prefill = if (selection.collapsed || selection.length > 200) null
        else state.text.substring(selection.min, selection.max).takeIf { '\n' !in it }
        // A query taken from the selection starts at that occurrence.
        if (prefill != null && find.open && prefill == find.query.text.toString()) {
            find.current = workspaceFindCurrent(find.matches, selection.min)
            revealMatch()
        } else if (prefill != null) find.pendingAnchor = selection.min
        find.show(prefill)
    }
    BackHandler(enabled = find.open && active, onBack = find::close)

    // Pairs: the box stack follows user input here; a caret leaving a box (tap, arrow keys, an edit
    // elsewhere) is caught by the validation below.
    pairs.enabled = autoPair && !readOnly
    val pairTransformation = remember(state) {
        InputTransformation {
            if (!pairs.enabled || changes.changeCount == 0) return@InputTransformation
            if (changes.changeCount > 1) {
                pairs.boxes = emptyList()
                return@InputTransformation
            }
            val range = changes.getRange(0)
            val original = changes.getOriginalRange(0)
            val outcome = WorkspacePairs.onInput(
                originalText, originalSelection.min..originalSelection.max,
                asCharSequence(), selection.min..selection.max,
                WorkspacePairs.Change(range.min, range.max, original.min, original.max), pairs.boxes,
            )
            outcome.fix?.let {
                replace(it.start, it.end, it.replacement)
                selection = TextRange(it.caret)
            }
            pairs.boxes = outcome.boxes
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text to state.selection }.collect { (text, selection) ->
            val boxes = pairs.boxes
            if (boxes.isNotEmpty()) {
                val valid = WorkspacePairs.validate(text, selection.min..selection.max, boxes)
                if (valid != boxes) pairs.boxes = valid
            }
        }
    }
    LaunchedEffect(autoPair) { if (!autoPair) pairs.clear() }

    // Single remembered instance: see WorkspaceMarkdownHighlightState doc. Recreating this on
    // recomposition restarts the IME session and makes the keyboard retract and pop back up.
    val transformation = remember(state) {
        OutputTransformation {
            val result = highlight.analysis
            if (highlight.markdown && result != null) {
                val currentPalette = highlight.palette
                for (span in result.spans.rebased(result.source, asCharSequence())) addStyle(
                    workspaceMarkdownSpanStyle(span, currentPalette, highlight.headingSizes, highlight.baseSize), span.start, span.end)
            }
            if (find.open) {
                val length = find.matchLength
                val query = find.query.text
                val matches = find.matches
                val current = find.current
                if (length > 0 && query.length == length) {
                    // Far-away matches of a huge result set are left plain: thousands of spans would slow every frame.
                    val shown = if (matches.size <= MAX_HIGHLIGHTED_MATCHES) matches.indices
                    else (current - MAX_HIGHLIGHTED_MATCHES / 2).coerceAtLeast(0).let { it until minOf(matches.size, it + MAX_HIGHLIGHTED_MATCHES) }
                    val text = asCharSequence()
                    for (i in shown) {
                        val start = matches[i]
                        if (start + length > text.length || !text.regionMatches(start, query, 0, length, ignoreCase = !find.caseSensitive)) continue
                        addStyle(if (i == current) highlight.currentMatch else highlight.match, start, start + length)
                    }
                }
            }
        }
    }
    highlight.markdown = markdown
    highlight.palette = palette
    highlight.headingSizes = headingSizes
    highlight.match = SpanStyle(background = colors.tertiaryContainer)
    highlight.currentMatch = SpanStyle(background = colors.tertiary, color = colors.onTertiary)
    val textStyle = rememberWorkspaceEditorTextStyle(MaterialTheme.typography.bodySmall)
    highlight.baseSize = textStyle.fontSize
    val tuiqiaoLabel = stringResource(R.string.tuiqiao_title)
    val zaizaoLabel = stringResource(R.string.zaizao_title)
    val currentOnTuiqiao by rememberUpdatedState(onTuiqiao)
    val currentOnZaizao by rememberUpdatedState(onZaizao)
    val oversized by remember(state) { derivedStateOf { state.text.length > WorkspaceMarkdownAnalyzer.MAX_SOURCE_CHARS } }
    // Half the default top/bottom padding: the toolbar and the status bar already frame the text.
    val contentPadding = TextFieldDefaults.contentPaddingWithoutLabel(top = 8.dp, bottom = 8.dp)
    val layoutDirection = LocalLayoutDirection.current
    val verticalPaddingPx = with(LocalDensity.current) { (contentPadding.calculateTopPadding() + contentPadding.calculateBottomPadding()).toPx() }
    val boxColors = workspacePairBoxColors()
    // The toolbar and find bar go above the text, or below it next to the keyboard (closest to the thumb).
    val controlsAtBottom = LocalSettings.current.displaySetting.workspaceEditorToolbarAtBottom
    val toolbar: @Composable () -> Unit = {
        if (!readOnly) WorkspaceEditorToolbar(
            state,
            WorkspaceEditorToolbarActions(markdown, formatKind, onTuiqiao, onZaizao, onFind = ::openFind),
            onEdited = pairs::clear,
        )
    }
    val findBar: @Composable () -> Unit = {
        if (find.open) WorkspaceEditorFindBar(
            find,
            canReplace = !readOnly,
            onPrevious = { step(-1) },
            onNext = { step(1) },
            onReplace = ::replaceCurrent,
            onReplaceAll = ::replaceAll,
        )
    }
    Column(modifier) {
        if (!controlsAtBottom) {
            toolbar()
            findBar()
        }
        if (markdown && (oversized || highlight.analysis?.plainFallback == true)) {
            Text(stringResource(R.string.workspace_editor_plain_fallback), style = MaterialTheme.typography.labelSmall)
        }
        TextField(
            state = state,
            modifier = Modifier.fillMaxWidth().weight(1f).onSizeChanged { size ->
                layout.viewportHeight = size.height - verticalPaddingPx
            }.drawWithContent {
                drawContent()
                val box = pairs.boxes.lastOrNull() ?: return@drawWithContent
                val result = layout.get() ?: return@drawWithContent
                val left = contentPadding.calculateLeftPadding(layoutDirection).toPx()
                val top = contentPadding.calculateTopPadding().toPx()
                clipRect(left, top, size.width - contentPadding.calculateRightPadding(layoutDirection).toPx(),
                    size.height - contentPadding.calculateBottomPadding().toPx()) {
                    translate(left, top - scroll.value) {
                        drawPairBox(result, box, boxColors, textStyle.fontSize.toPx())
                    }
                }
            }.appendTextContextMenuComponents {
                val tuiqiao = currentOnTuiqiao
                val zaizao = currentOnZaizao
                val selection = state.selection
                if ((tuiqiao == null && zaizao == null) || selection.collapsed) return@appendTextContextMenuComponents
                separator()
                if (tuiqiao != null) item(key = TuiqiaoMenuKey, label = tuiqiaoLabel) {
                    tuiqiao(selection.min, selection.max)
                    close()
                }
                if (zaizao != null) item(key = ZaizaoMenuKey, label = zaizaoLabel) {
                    zaizao(selection.min, selection.max)
                    close()
                }
            }.onPreviewKeyEvent { event ->
                if (readOnly || event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Z, Key.Y -> {
                        if (event.key == Key.Y || event.isShiftPressed) state.undoState.redo() else state.undoState.undo()
                        pairs.clear()
                        true
                    }
                    Key.F -> {
                        openFind()
                        true
                    }
                    else -> false
                }
            },
            readOnly = readOnly,
            lineLimits = TextFieldLineLimits.MultiLine(),
            inputTransformation = pairTransformation,
            outputTransformation = transformation,
            textStyle = textStyle,
            onTextLayout = { getResult -> layout.get = getResult },
            scrollState = scroll,
            contentPadding = contentPadding,
            // No focus/underline indicator: it drew a glowing line between the text and the status row.
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
            ),
        )
        if (controlsAtBottom) {
            findBar()
            toolbar()
        }
    }
}

private const val MAX_HIGHLIGHTED_MATCHES = 1_000

/** The open fill boxes (outermost first; only the innermost is drawn) and whether pairing is on. */
internal class WorkspacePairBoxState {
    var boxes by mutableStateOf<List<WorkspacePairBox>>(emptyList())
    @Volatile var enabled = true
    fun clear() {
        if (boxes.isNotEmpty()) boxes = emptyList()
    }
}

/** The text field's latest layout and visible height, for scrolling to a match and drawing the fill box. */
private class WorkspaceEditorLayout {
    var get: () -> TextLayoutResult? = { null }
    var viewportHeight = 0f

    suspend fun scrollIntoView(scroll: ScrollState, offset: Int) {
        val result = get() ?: return
        if (offset > result.layoutInput.text.length) return
        val line = result.getLineForOffset(offset)
        val top = result.getLineTop(line)
        val bottom = result.getLineBottom(line)
        if (viewportHeight <= 0f || top >= scroll.value && bottom <= scroll.value + viewportHeight) return
        scroll.animateScrollTo((top - viewportHeight / 3f).roundToInt().coerceIn(0, scroll.maxValue))
    }
}

internal data class WorkspacePairBoxColors(val stroke: Color, val fill: Color)

/** Dark blue outline over a light blue tint (user decision); a brighter outline on dark themes so it stays visible. */
@Composable
private fun workspacePairBoxColors(): WorkspacePairBoxColors =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) WorkspacePairBoxColors(Color(0xFF60A5FA), Color(0x661E3A8A))
    else WorkspacePairBoxColors(Color(0xFF1E3A8A), Color(0x241E40AF))

/** The innermost box: one rounded rectangle per line it spans; an empty box is a narrow slot between the symbols. */
private fun DrawScope.drawPairBox(layout: TextLayoutResult, box: WorkspacePairBox, colors: WorkspacePairBoxColors, fontSize: Float) {
    val length = layout.layoutInput.text.length
    if (box.close > length || box.open >= box.close) return
    val start = box.open + 1
    val end = box.close
    val inset = 1.dp.toPx()
    val pad = 1.dp.toPx()
    val rects = if (start >= end) {
        // A slot on the caret, narrow enough to sit in the symbols' side bearings (wide in CJK fonts, slim for ASCII).
        val line = layout.getLineForOffset(end)
        val x = layout.getHorizontalPosition(end, usePrimaryDirection = true)
        val advance = minOf(layout.getBoundingBox(box.open).width, layout.getBoundingBox(box.close).width).takeIf { it > 0f } ?: fontSize
        val half = (advance * 0.2f).coerceIn(1.5.dp.toPx(), 3.dp.toPx())
        listOf(Rect(x - half, layout.getLineTop(line) + inset, x + half, layout.getLineBottom(line) - inset))
    } else {
        val firstLine = layout.getLineForOffset(start)
        val lastLine = layout.getLineForOffset(end - 1)
        (firstLine..lastLine).map { line ->
            val left = if (line == firstLine) layout.getBoundingBox(start).left else layout.getLineLeft(line)
            val right = if (line == lastLine) layout.getBoundingBox(end - 1).right else layout.getLineRight(line)
            Rect(left - pad, layout.getLineTop(line) + inset, maxOf(right, left) + pad, layout.getLineBottom(line) - inset)
        }
    }
    val radius = CornerRadius(4.dp.toPx())
    val stroke = Stroke(1.5.dp.toPx())
    for (rect in rects) {
        drawRoundRect(colors.fill, rect.topLeft, rect.size, radius)
        drawRoundRect(colors.stroke, rect.topLeft, rect.size, radius, style = stroke)
    }
}

private class MarkdownAnalysisBudgetExceeded : RuntimeException()
