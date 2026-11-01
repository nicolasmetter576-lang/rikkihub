package me.rerere.rikkihub.ui.pages.extensions.workspace

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.R
import me.rerere.rikkihub.ui.components.table.DataTable
import me.rerere.rikkihub.ui.context.LocalSettings

/** Extension classification only. No global chat renderer or preprocessing is reused here. */
enum class WorkspaceFilePreviewKind { NONE, MARKDOWN, WEB }
fun workspaceFilePreviewKind(fileName: String): WorkspaceFilePreviewKind = when (fileName.substringAfterLast('.', "").lowercase()) {
    "md", "markdown" -> WorkspaceFilePreviewKind.MARKDOWN
    "html", "htm", "svg" -> WorkspaceFilePreviewKind.WEB
    else -> WorkspaceFilePreviewKind.NONE
}

/** Workspace-only safe semantic-source preview subset. All original syntax remains visible,
 * except GFM tables, which render as a grid (Obsidian-style) with their cells' inline markup applied.
 * No HTML renderer, WebView, image loader, relative file resolving or automatic network request.
 * Only parser-recognized inline HTTP(S) link LABEL clicks open a browser. Images stay literal (in
 * table cells only the alt text shows) and HTML / unsupported syntax stay source. This intentionally
 * does not use MarkdownBlock (its chat image loading and ACTION_VIEW behavior are not safe for
 * untrusted workspace documents).
 */
@Composable
fun WorkspaceMarkdownPreview(
    content: String,
    modifier: Modifier = Modifier,
    /** Source offsets of a selection to refine; null hides 推敲 from the selection menu. */
    onTuiqiao: ((start: Int, end: Int) -> Unit)? = null,
    /** Source offsets of a selection to recast; null hides 再造 from the selection menu. */
    onZaizao: ((start: Int, end: Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val headingSizes = LocalSettings.current.displaySetting.workspaceMarkdownHeadingSizes
    val palette = WorkspaceMarkdownPalette(colors.tertiary, colors.primary, colors.onSurfaceVariant, colors.surfaceContainerHighest)
    // Not keyed by content: the previous analysis stays on screen until the new one is ready, so an
    // edit (a 推敲 replacement) does not flash plain source, collapse tables or move the scroll position.
    var prepared by remember { mutableStateOf<Pair<WorkspaceMarkdownAnalysis, List<WorkspacePreviewBlock>>?>(null) }
    LaunchedEffect(content) {
        prepared = withContext(Dispatchers.Default) {
            val job = currentCoroutineContext()
            val deadline = System.nanoTime() + 600_000_000L
            val check = {
                job.ensureActive()
                if (System.nanoTime() > deadline) error("Markdown preview analysis budget exceeded")
            }
            try {
                val analysis = WorkspaceMarkdownAnalyzer.analyze(content, checkCancelled = check)
                analysis to WorkspaceMarkdownPreviewModel.blocks(analysis, check)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                WorkspaceMarkdownAnalysis(content, emptyList(), emptyList(), true) to
                    listOf(WorkspacePreviewBlock.Source(0, content.length))
            }
        }
    }
    val openLink: (String) -> Unit = { url ->
        safeWorkspaceMarkdownUrl(url)?.let { safe ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe))) }
        }
    }
    val textStyle = rememberWorkspaceEditorTextStyle(MaterialTheme.typography.bodySmall.copy(color = colors.onSurface))
    val baseSize = textStyle.fontSize
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (content.isEmpty()) Text(stringResource(R.string.workspace_editor_empty), color = colors.onSurfaceVariant)
        else {
            val (analysis, blocks) = prepared
                ?: (WorkspaceMarkdownAnalysis(content, emptyList(), emptyList()) to listOf(WorkspacePreviewBlock.Source(0, content.length)))
            if (analysis.plainFallback) Text(stringResource(R.string.workspace_editor_plain_fallback), style = MaterialTheme.typography.labelSmall)
            blocks.forEach { block ->
                when (block) {
                    is WorkspacePreviewBlock.Source -> {
                        val annotated = remember(analysis, block, palette, headingSizes, baseSize) {
                            sourceSegment(analysis, block, palette, headingSizes, baseSize)
                        }
                        // Offsets map 1:1 to the analysed source; a stale analysis (new text still being
                        // analysed) offers no 推敲, since its offsets no longer match the document.
                        val tuiqiao = onTuiqiao?.takeIf { analysis.source == content }
                        val zaizao = onZaizao?.takeIf { analysis.source == content }
                        SelectableSourceText(annotated, textStyle, openLink, Modifier.fillMaxWidth(),
                            tuiqiao?.let { callback -> { a: Int, b: Int -> callback(block.start + a, block.start + b) } },
                            zaizao?.let { callback -> { a: Int, b: Int -> callback(block.start + a, block.start + b) } })
                    }
                    is WorkspacePreviewBlock.Table -> WorkspacePreviewTable(block, palette, textStyle, openLink)
                }
            }
        }
    }
}

private fun sourceSegment(
    analysis: WorkspaceMarkdownAnalysis,
    block: WorkspacePreviewBlock.Source,
    palette: WorkspaceMarkdownPalette,
    headingSizes: Boolean,
    baseSize: TextUnit,
): AnnotatedString {
    val builder = AnnotatedString.Builder(analysis.source.substring(block.start, block.end))
    fun clip(start: Int, end: Int, apply: (Int, Int) -> Unit) {
        val a = maxOf(start, block.start)
        val b = minOf(end, block.end)
        if (a < b) apply(a - block.start, b - block.start)
    }
    analysis.spans.forEach { span ->
        clip(span.start, span.end) { a, b -> builder.addStyle(workspaceMarkdownSpanStyle(span, palette, headingSizes, baseSize), a, b) }
    }
    analysis.links.forEach { link -> clip(link.start, link.end) { a, b -> builder.addStringAnnotation(LINK_TAG, link.url, a, b) } }
    return builder.toAnnotatedString()
}

private fun cellText(cell: WorkspaceCellText, palette: WorkspaceMarkdownPalette): AnnotatedString {
    val builder = AnnotatedString.Builder(cell.text)
    cell.spans.forEach { builder.addStyle(workspaceMarkdownSpanStyle(it, palette, false, TextUnit.Unspecified), it.start, it.end) }
    cell.links.forEach { builder.addStringAnnotation(LINK_TAG, it.url, it.start, it.end) }
    return builder.toAnnotatedString()
}

/**
 * A read-only text field, so the system selection (handles, magnifier, Copy) works and its range is
 * readable: the selection menu gets 推敲. Link labels open on a single tap (the tap lands a collapsed
 * cursor inside the label; the cursor is then parked at the end so tapping the same link works again).
 */
@Composable
private fun SelectableSourceText(
    text: AnnotatedString,
    style: TextStyle,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    onTuiqiao: ((start: Int, end: Int) -> Unit)?,
    onZaizao: ((start: Int, end: Int) -> Unit)? = null,
) {
    val state = remember(text.text) { TextFieldState(text.text, TextRange(text.length)) }
    val transformation = remember(text) {
        OutputTransformation {
            if (length == text.length) text.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
        }
    }
    val links = remember(text) { text.getStringAnnotations(LINK_TAG, 0, text.length) }
    val currentOnLink by rememberUpdatedState(onLink)
    LaunchedEffect(state, links) {
        if (links.isEmpty()) return@LaunchedEffect
        snapshotFlow { state.selection }.drop(1).collect { selection ->
            if (!selection.collapsed) return@collect
            links.firstOrNull { selection.start >= it.start && selection.start < it.end }?.let { link ->
                currentOnLink(link.item)
                state.edit { this.selection = TextRange(length) }
            }
        }
    }
    val label = stringResource(R.string.tuiqiao_title)
    val zaizaoLabel = stringResource(R.string.zaizao_title)
    val currentOnTuiqiao by rememberUpdatedState(onTuiqiao)
    val currentOnZaizao by rememberUpdatedState(onZaizao)
    BasicTextField(
        state = state,
        readOnly = true,
        textStyle = style,
        outputTransformation = transformation,
        cursorBrush = SolidColor(Color.Transparent),
        modifier = modifier.appendTextContextMenuComponents {
            val tuiqiao = currentOnTuiqiao
            val zaizao = currentOnZaizao
            val selection = state.selection
            if ((tuiqiao == null && zaizao == null) || selection.collapsed) return@appendTextContextMenuComponents
            separator()
            if (tuiqiao != null) item(key = TuiqiaoMenuKey, label = label) {
                tuiqiao(selection.min, selection.max)
                state.edit { this.selection = TextRange(selection.max) }
                close()
            }
            if (zaizao != null) item(key = ZaizaoMenuKey, label = zaizaoLabel) {
                zaizao(selection.min, selection.max)
                state.edit { this.selection = TextRange(selection.max) }
                close()
            }
        },
    )
}

/** Key of the 推敲 entry in a text selection menu (preview and source editor). */
internal object TuiqiaoMenuKey

/** Key of the 再造 entry in a text selection menu (preview and source editor). */
internal object ZaizaoMenuKey

@Composable
private fun LinkText(text: AnnotatedString, style: TextStyle, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    ClickableText(text = text, modifier = modifier, style = style, onClick = { offset ->
        text.getStringAnnotations(LINK_TAG, offset, offset).firstOrNull()?.let { onLink(it.item) }
    })
}

@Composable
private fun WorkspacePreviewTable(
    table: WorkspacePreviewBlock.Table,
    palette: WorkspaceMarkdownPalette,
    textStyle: TextStyle,
    onLink: (String) -> Unit,
) {
    fun align(column: Int) = when (table.aligns.getOrNull(column)) {
        WorkspaceTableAlign.CENTER -> Alignment.Center
        WorkspaceTableAlign.RIGHT -> Alignment.CenterEnd
        else -> Alignment.CenterStart
    }
    val columns = table.header.size
    DataTable(
        headers = table.header.map { cell ->
            @Composable {
                val text = remember(cell, palette) { cellText(cell, palette) }
                LinkText(text, textStyle.copy(fontWeight = FontWeight.Bold), onLink, Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
        },
        rows = table.rows.map { row ->
            row.map { cell ->
                @Composable {
                    val text = remember(cell, palette) { cellText(cell, palette) }
                    LinkText(text, textStyle, onLink, Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        cellPadding = 6.dp,
        headerBackground = MaterialTheme.colorScheme.secondaryContainer,
        zebraStriping = true,
        columnMinWidths = List(columns) { 56.dp },
        columnMaxWidths = List(columns) { 280.dp },
        columnAlignments = List(columns) { align(it) },
    )
}

private const val LINK_TAG = "workspace-safe-link"
