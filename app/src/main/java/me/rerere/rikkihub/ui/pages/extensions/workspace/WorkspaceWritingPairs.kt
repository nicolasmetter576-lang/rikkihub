package me.rerere.rikkihub.ui.pages.extensions.workspace

/** An auto-closed pair: [open] and [close] are the indices of the two symbols; the fill box is the text between them. */
internal data class WorkspacePairBox(val open: Int, val close: Int)

/**
 * Auto-closing pairs with a fill box (`DisplaySetting.workspaceEditorAutoPair`; the web editor has its own switch). Pure rules over one user
 * input change, so the editor's InputTransformation stays a thin adapter and the cases are unit-tested:
 *
 * - typing an opener puts the closer after the caret (not right before a letter, digit or CJK character,
 *   and a straight quote not right after one) and opens a box; with a selection the pair wraps it;
 * - an IME that commits the whole pair itself (caret between) just gets the box;
 * - typing the box's closer right before it steps over it; Backspace in an empty box deletes both;
 * - Enter inside a box moves the caret past its closer instead of breaking the line;
 * - boxes follow edits inside them and disappear when an edit touches a symbol or the caret leaves.
 *
 * Boxes are a stack, outermost first. The web editor's CodeMirror extension follows the same rules.
 */
internal object WorkspacePairs {
    val PAIRS: Map<Char, Char> = linkedMapOf(
        '（' to '）', '“' to '”', '‘' to '’', '「' to '」', '『' to '』', '《' to '》', '〈' to '〉',
        '【' to '】', '〔' to '〕', '〖' to '〗', '(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'',
    )
    private val CLOSERS: Set<Char> = PAIRS.values.toSet()

    /** One change of the user's input: [start]/[end] in the new text, [originalStart]/[originalEnd] in the old. */
    data class Change(val start: Int, val end: Int, val originalStart: Int, val originalEnd: Int)

    /** A correction applied on top of the user's change, in new-text coordinates. */
    data class Fix(val start: Int, val end: Int, val replacement: String, val caret: Int)

    data class Outcome(val fix: Fix?, val boxes: List<WorkspacePairBox>)

    fun onInput(
        original: CharSequence,
        originalSelection: IntRange,
        text: CharSequence,
        selection: IntRange,
        change: Change,
        boxes: List<WorkspacePairBox>,
    ): Outcome {
        val mapped = boxes.mapNotNull { map(it, change) }
        val replaced = original.subSequence(change.originalStart, change.originalEnd)
        val inserted = text.subSequence(change.start, change.end)
        val caret = if (selection.first == selection.last) selection.first else -1

        // A selection replaced by an opener: wrap it.
        if (originalSelection.first != originalSelection.last && change.originalStart == originalSelection.first &&
            change.originalEnd == originalSelection.last && inserted.length == 1 && caret == change.end) {
            val closer = PAIRS[inserted[0]]
            if (closer != null && replaced.isNotEmpty() && '\n' !in replaced) {
                val close = change.start + 1 + replaced.length
                return Outcome(
                    Fix(change.start + 1, change.start + 1, "$replaced$closer", close),
                    shiftFrom(mapped, change.start + 1, replaced.length + 1) + WorkspacePairBox(change.start, close),
                )
            }
        }

        // One character typed at the caret, possibly committing composing text in front of it.
        if (caret == change.end && inserted.length == replaced.length + 1 && inserted.startsWith(replaced)) {
            val at = change.end - 1
            val typed = inserted.last()
            val inner = mapped.lastOrNull { it.open < at && at < it.close }
            if (typed == '\n' && inner != null) {
                // Enter: leave the innermost box instead of breaking the line.
                val rest = shiftFrom(mapped - inner, at + 1, -1)
                return Outcome(Fix(at, at + 1, "", inner.close), rest)
            }
            if (inner != null && inner.close == at + 1 && text.getOrNull(at + 1) == typed && typed in CLOSERS) {
                // The box's own closer typed right before it: step over.
                return Outcome(Fix(at, at + 1, "", at + 1), shiftFrom(mapped - inner, at + 1, -1))
            }
            val closer = PAIRS[typed]
            if (closer != null && opensHere(text, at, typed, closer)) {
                return Outcome(Fix(at + 1, at + 1, closer.toString(), at + 1), shiftFrom(mapped, at + 1, 1) + WorkspacePairBox(at, at + 1))
            }
            return Outcome(null, mapped)
        }

        // An IME that inserts the whole pair with the caret between: box only.
        if (caret == change.end - 1 && inserted.length == replaced.length + 2 && inserted.startsWith(replaced)) {
            val open = change.end - 2
            if (PAIRS[inserted[inserted.length - 2]] == inserted.last()) return Outcome(null, mapped + WorkspacePairBox(open, open + 1))
        }

        // Backspace right after the opener of an empty box: the closer goes too.
        if (inserted.isEmpty() && replaced.length == 1 && caret == change.start && text.getOrNull(change.start) == PAIRS[replaced[0]] &&
            boxes.any { it.open == change.originalStart && it.close == it.open + 1 }) {
            return Outcome(Fix(change.start, change.start + 1, "", change.start), shiftFrom(mapped, change.start + 1, -1))
        }
        return Outcome(null, mapped)
    }

    /** The boxes still standing: both symbols in place and the selection inside. */
    fun validate(text: CharSequence, selection: IntRange, boxes: List<WorkspacePairBox>): List<WorkspacePairBox> {
        val from = minOf(selection.first, selection.last)
        val to = maxOf(selection.first, selection.last)
        return boxes.filter {
            it.open >= 0 && it.close < text.length && it.open < it.close && PAIRS[text[it.open]] == text[it.close] &&
                from > it.open && to <= it.close
        }
    }

    /** A box through a change: shifted when the change is before it, resized when inside, gone when it hits a symbol. */
    internal fun map(box: WorkspacePairBox, change: Change): WorkspacePairBox? {
        val delta = (change.end - change.start) - (change.originalEnd - change.originalStart)
        return when {
            change.originalEnd <= box.open -> WorkspacePairBox(box.open + delta, box.close + delta)
            change.originalStart > box.open && change.originalEnd <= box.close -> box.copy(close = box.close + delta)
            change.originalStart > box.close -> box
            else -> null
        }
    }

    private fun shiftFrom(boxes: List<WorkspacePairBox>, from: Int, delta: Int) = boxes.map {
        WorkspacePairBox(if (it.open >= from) it.open + delta else it.open, if (it.close >= from) it.close + delta else it.close)
    }

    private fun opensHere(text: CharSequence, at: Int, opener: Char, closer: Char): Boolean {
        val next = text.getOrNull(at + 1)
        if (next != null && !isStatsSpace(next.code) && next !in CLOSERS && !isPunctuation(next)) return false
        // A straight quote after a letter or digit is an apostrophe or a closing quote.
        if (opener == closer) text.getOrNull(at - 1)?.let { if (Character.isLetterOrDigit(it)) return false }
        return true
    }

    private fun isPunctuation(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
        else -> false
    }
}
