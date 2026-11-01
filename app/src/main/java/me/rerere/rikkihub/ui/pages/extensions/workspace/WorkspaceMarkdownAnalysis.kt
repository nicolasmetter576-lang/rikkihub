package me.rerere.rikkihub.ui.pages.extensions.workspace

import java.net.URI
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes as E
import org.intellij.markdown.MarkdownTokenTypes as T
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes as GE
import org.intellij.markdown.flavours.gfm.GFMTokenTypes as GT
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.ExperimentalApi
import org.intellij.markdown.parser.MarkdownParser

internal enum class WorkspaceMarkdownStyle { HEADING, BOLD, ITALIC, STRIKE, CODE, LINK, QUOTE, LIST, TASK, TABLE, TABLE_HEADER, TABLE_DELIMITER, MARKER }
internal data class WorkspaceMarkdownSpan(val start: Int, val end: Int, val style: WorkspaceMarkdownStyle, val level: Int = 0)
/**
 * Carries spans analysed on [old] over an edit that produced [new], so styling stays put while
 * typing and the next analysis catches up. The edit is the region between the common prefix and
 * suffix. A span keeps its offsets before the edit and shifts after it; text typed inside a span
 * joins it (typing at its very end does not); a boundary inside replaced text snaps to the edit.
 */
internal fun List<WorkspaceMarkdownSpan>.rebased(old: CharSequence, new: CharSequence): List<WorkspaceMarkdownSpan> {
    val shortest = minOf(old.length, new.length)
    var prefix = 0
    while (prefix < shortest && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < shortest - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    if (prefix == old.length && prefix == new.length) return this
    val oldEditEnd = old.length - suffix
    val newEditEnd = new.length - suffix
    val delta = new.length - old.length
    fun start(x: Int) = when { x < prefix -> x; x >= oldEditEnd -> x + delta; else -> newEditEnd }
    fun end(x: Int) = when { x <= prefix -> x; x >= oldEditEnd -> x + delta; else -> prefix }
    return mapNotNull { span ->
        val start = start(span.start)
        val end = end(span.end)
        if (start < end) span.copy(start = start, end = end) else null
    }
}

internal data class WorkspaceMarkdownLink(val start: Int, val end: Int, val url: String)
internal data class WorkspaceMarkdownAnalysis(
    val source: String,
    val spans: List<WorkspaceMarkdownSpan>,
    val links: List<WorkspaceMarkdownLink>,
    val plainFallback: Boolean = false,
    /** GFM table blocks as half-open source ranges (`start until end`), in document order. */
    val tables: List<IntRange> = emptyList(),
)

/** Raw parser offsets are half-open UTF-16 indices into the EXACT input (no chat preprocessing).
 * A fresh parser per job avoids sharing mutable parser state across cancelled background jobs.
 * Limits bound parser work as well as output decoration. CancellationToken is the fork's actual
 * public parser cancellation API; caller supplies coroutine ensureActive + time budget checks.
 */
internal object WorkspaceMarkdownAnalyzer {
    const val MAX_SOURCE_CHARS = 100_000
    const val MAX_LINES = 8_000
    const val MAX_NODES = 30_000
    const val MAX_SPANS = 12_000
    const val MAX_DEPTH = 128
    private val headings = listOf(E.ATX_1, E.ATX_2, E.ATX_3, E.ATX_4, E.ATX_5, E.ATX_6)
    private val markers = setOf(T.ATX_HEADER, T.SETEXT_1, T.SETEXT_2, T.EMPH, T.BACKTICK,
        T.BLOCK_QUOTE, T.LIST_BULLET, T.LIST_NUMBER, T.CODE_FENCE_START, T.CODE_FENCE_END,
        T.FENCE_LANG, T.HORIZONTAL_RULE, GT.TILDE, GT.TABLE_SEPARATOR, GT.CHECK_BOX)
    private val linkPunctuation = setOf(T.LBRACKET, T.RBRACKET, T.LPAREN, T.RPAREN, T.EXCLAMATION_MARK)

    @OptIn(ExperimentalApi::class)
    fun analyze(source: String, checkCancelled: () -> Unit = {}): WorkspaceMarkdownAnalysis {
        fun fallback() = WorkspaceMarkdownAnalysis(source, emptyList(), emptyList(), true)
        if (source.length > MAX_SOURCE_CHARS || source.count { it == '\n' } >= MAX_LINES) return fallback()
        val parser = MarkdownParser(GFMFlavourDescriptor(), false, object : CancellationToken {
            @OptIn(ExperimentalApi::class)
            override fun checkCancelled() = checkCancelled.invoke()
        })
        checkCancelled()
        val root = parser.buildMarkdownTreeFromString(source)
        val spans = ArrayList<WorkspaceMarkdownSpan>()
        val links = ArrayList<WorkspaceMarkdownLink>()
        val tables = ArrayList<IntRange>()
        val pending = ArrayDeque<Pair<ASTNode, Int>>()
        pending.add(root to 0)
        var nodes = 0
        fun span(node: ASTNode, kind: WorkspaceMarkdownStyle, level: Int = 0) {
            if (node.startOffset < node.endOffset) spans.add(WorkspaceMarkdownSpan(node.startOffset, node.endOffset, kind, level))
        }
        while (pending.isNotEmpty()) {
            checkCancelled()
            val (node, depth) = pending.removeLast()
            if (++nodes > MAX_NODES || depth > MAX_DEPTH || spans.size > MAX_SPANS) return fallback()
            if (node.startOffset !in 0..source.length || node.endOffset !in node.startOffset..source.length) return fallback()
            val type = node.type
            val heading = headings.indexOf(type)
            when {
                heading >= 0 -> span(node, WorkspaceMarkdownStyle.HEADING, heading + 1)
                type == E.SETEXT_1 || type == E.SETEXT_2 -> span(node, WorkspaceMarkdownStyle.HEADING, if (type == E.SETEXT_1) 1 else 2)
                type == E.STRONG -> span(node, WorkspaceMarkdownStyle.BOLD)
                type == E.EMPH -> span(node, WorkspaceMarkdownStyle.ITALIC)
                type == GE.STRIKETHROUGH -> span(node, WorkspaceMarkdownStyle.STRIKE)
                type == E.CODE_FENCE || type == E.CODE_BLOCK || type == E.CODE_SPAN -> span(node, WorkspaceMarkdownStyle.CODE)
                type == E.BLOCK_QUOTE -> span(node, WorkspaceMarkdownStyle.QUOTE)
                type == E.LIST_ITEM -> span(node, WorkspaceMarkdownStyle.LIST)
                type == GT.CHECK_BOX -> span(node, WorkspaceMarkdownStyle.TASK)
                type == GE.TABLE -> {
                    span(node, WorkspaceMarkdownStyle.TABLE)
                    tables.add(node.startOffset until node.endOffset)
                }
                type == GE.HEADER -> {
                    span(node, WorkspaceMarkdownStyle.BOLD)
                    span(node, WorkspaceMarkdownStyle.TABLE_HEADER)
                }
                type == E.INLINE_LINK || type == E.FULL_REFERENCE_LINK || type == E.SHORT_REFERENCE_LINK || type == E.AUTOLINK -> {
                    span(node, WorkspaceMarkdownStyle.LINK)
                    if (type == E.INLINE_LINK) {
                        val destination = node.children.firstOrNull { it.type == E.LINK_DESTINATION }
                        val label = node.children.firstOrNull { it.type == E.LINK_TEXT }
                        if (destination != null && label != null && node.parent?.type != E.IMAGE) {
                            val raw = source.substring(destination.startOffset, destination.endOffset).removeSurrounding("<", ">")
                            safeWorkspaceMarkdownUrl(raw)?.let { links.add(WorkspaceMarkdownLink(label.startOffset, label.endOffset, it)) }
                        }
                    }
                }
            }
            if (type in markers || (type in linkPunctuation && isInsideLink(node))) span(node, WorkspaceMarkdownStyle.MARKER)
            // The delimiter row (`|---|:-:|`) is one separator token spanning the whole row.
            if (type == GT.TABLE_SEPARATOR && node.endOffset - node.startOffset > 1) span(node, WorkspaceMarkdownStyle.TABLE_DELIMITER)
            // Fenced/indented code contents are opaque. Do not interpret literal stars as emphasis.
            for (child in node.children.asReversed()) pending.add(child to depth + 1)
        }
        if (spans.size > MAX_SPANS) return fallback()
        // Marker color wins over ancestor link/quote colors, without discarding ancestor weights.
        return WorkspaceMarkdownAnalysis(source,
            spans.filter { it.style != WorkspaceMarkdownStyle.MARKER } + spans.filter { it.style == WorkspaceMarkdownStyle.MARKER }, links,
            tables = tables.sortedBy { it.first })
    }

    private fun isInsideLink(node: ASTNode): Boolean {
        var parent = node.parent
        var depth = 0
        while (parent != null && depth++ < MAX_DEPTH) {
            if (parent.type in setOf(E.INLINE_LINK, E.IMAGE, E.FULL_REFERENCE_LINK, E.SHORT_REFERENCE_LINK)) return true
            parent = parent.parent
        }
        return false
    }
}

/** Never dispatch arbitrary ACTION_VIEW schemes or embedded userinfo. No network until a click. */
internal fun safeWorkspaceMarkdownUrl(raw: String): String? = runCatching {
    if (raw.any { it.isWhitespace() || it.code < 0x20 || it == '\\' }) return null
    val uri = URI(raw)
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
    uri.toASCIIString()
}.getOrNull()
