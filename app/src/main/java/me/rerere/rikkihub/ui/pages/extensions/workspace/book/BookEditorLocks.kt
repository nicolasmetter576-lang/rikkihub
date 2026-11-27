package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Lock
import me.rerere.hugeicons.stroke.LockOpen
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.book.history.BookLocks
import me.rerere.rikkihub.data.book.history.FileLocks
import me.rerere.rikkihub.data.book.history.PassageLock
import me.rerere.rikkihub.ui.theme.AppSpacing

/**
 * 锁定段落在编辑器文字里的位置（按 UTF-16 偏移，含末尾不含换行）。打字时按改动平移，所以改锁定段落时竖条跟着走，
 * 不会消失；还没定位到的段落（文字刚读进来、整篇重载或还原后）稍后按全文重新找。
 */
@Composable
internal fun rememberLockedRanges(state: TextFieldState, locks: FileLocks?): Map<String, IntRange> {
    var ranges by remember(state) { mutableStateOf(emptyMap<String, IntRange>()) }
    LaunchedEffect(state, locks) {
        val passages = locks?.passages.orEmpty().filter { it.dormantSince == null }
        if (passages.isEmpty()) {
            ranges = emptyMap()
            return@LaunchedEffect
        }
        var last: String? = null
        snapshotFlow { state.text.toString() }.collectLatest { now ->
            val old = last
            last = now
            if (old == null) {
                ranges = withContext(Dispatchers.Default) { BookLocks.rangesIn(now, passages) }
                return@collectLatest
            }
            if (old != now) ranges = ranges.mapValues { (_, range) -> rebase(range, old, now) }.filterValues { !it.isEmpty() }
            val missing = passages.filter { it.id !in ranges }
            if (missing.isEmpty()) return@collectLatest
            delay(LOCATE_DELAY_MS)
            val found = withContext(Dispatchers.Default) { BookLocks.rangesIn(now, missing) }
            if (found.isNotEmpty()) ranges = ranges + found
        }
    }
    return ranges
}

/** 打字停下多久后再给还没定位到的锁定段落按全文找一次位置。 */
private const val LOCATE_DELAY_MS = 300L

/** 把 [old] 里的 [range] 平移到 [now]：按两段文字的共同前后缀找出改动的那一块。 */
internal fun rebase(range: IntRange, old: String, now: String): IntRange {
    val prefix = old.commonPrefixWith(now).length
    val suffix = minOf(old.length, now.length).let { limit ->
        var n = 0
        while (n < limit - prefix && old[old.length - 1 - n] == now[now.length - 1 - n]) n++
        n
    }
    val oldEnd = old.length - suffix
    val newEnd = now.length - suffix
    val delta = now.length - old.length
    fun move(offset: Int, end: Boolean): Int = when {
        offset < prefix || (!end && offset == prefix) -> offset
        offset >= oldEnd -> offset + delta
        else -> if (end) newEnd else prefix
    }
    val start = move(range.first, end = false)
    val endExclusive = move(range.last + 1, end = true)
    return start until endExclusive
}

/** 编辑器里的偏移换成归一后文字（去 BOM、CRLF 换 LF）里的偏移，加锁接口按后者算。 */
internal fun normalizedOffset(text: CharSequence, offset: Int): Int = BookLocks.normalize(text.subSequence(0, offset).toString()).length

/** 状态栏里的锁：没锁是淡色开锁，整章锁是实心色，有段落锁加一个小点。点按由父级处理，不撑高状态栏。 */
@Composable
internal fun EditorLockIcon(locks: FileLocks?, modifier: Modifier = Modifier) {
    val chapter = locks?.chapter != null
    val passages = locks?.passages.orEmpty().isNotEmpty()
    Box(modifier) {
        Icon(
            if (chapter || passages) HugeIcons.Lock else HugeIcons.LockOpen,
            stringResource(R.string.book_lock_panel_title),
            Modifier.size(16.dp),
            tint = when {
                chapter -> MaterialTheme.colorScheme.primary
                passages -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.outlineVariant
            },
        )
        if (passages && !chapter) {
            Box(Modifier.align(Alignment.TopEnd).size(5.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

/**
 * 本章锁面板（加锁、解锁的主入口，不用长按）：整章锁一行、每处段落锁一行（开头 20 字，点一行跳过去），每行都能解锁；
 * 底部是锁定或解锁整章、锁定光标所在段（有选区时是锁定选中的段落）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChapterLockSheet(
    locks: FileLocks?,
    hasSelection: Boolean,
    busy: Boolean,
    onJump: (PassageLock) -> Unit,
    onLockChapter: () -> Unit,
    onUnlockChapter: () -> Unit,
    onLockParagraph: () -> Unit,
    onUnlockPassage: (PassageLock) -> Unit,
    onDismiss: () -> Unit,
) {
    val chapter = locks?.chapter
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                stringResource(R.string.book_lock_panel_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 4.dp),
            )
            if (chapter == null && locks?.passages.isNullOrEmpty()) {
                Text(
                    stringResource(R.string.book_lock_panel_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 4.dp),
                )
            }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (chapter != null) item("chapter") {
                    LockRow(
                        text = stringResource(R.string.book_lock_whole_chapter),
                        note = stringResource(R.string.book_lock_from_web).takeIf { chapter.web },
                        onClick = null,
                        onUnlock = onUnlockChapter,
                    )
                }
                items(locks?.passages.orEmpty(), key = { it.id }) { passage ->
                    val notes = listOfNotNull(
                        stringResource(R.string.book_lock_paused).takeIf { passage.dormantSince != null },
                        stringResource(R.string.book_lock_from_web).takeIf { passage.web },
                    )
                    LockRow(
                        text = "“" + passage.lines.firstOrNull().orEmpty().trim().take(20) + "…”",
                        note = notes.joinToString(" · ").ifEmpty { null },
                        onClick = { onJump(passage) }.takeIf { passage.dormantSince == null },
                        onUnlock = { onUnlockPassage(passage) },
                    )
                }
            }
            FlowRow(Modifier.padding(horizontal = AppSpacing.md, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (chapter == null) {
                    OutlinedButton(onClick = onLockChapter, enabled = !busy, modifier = Modifier.testTag("book-lock-chapter")) {
                        Text(stringResource(R.string.book_lock_chapter))
                    }
                    OutlinedButton(onClick = onLockParagraph, enabled = !busy, modifier = Modifier.testTag("book-lock-paragraph")) {
                        Text(stringResource(if (hasSelection) R.string.book_lock_selected_paragraphs else R.string.book_lock_cursor_paragraph))
                    }
                } else {
                    OutlinedButton(onClick = onUnlockChapter, enabled = !busy) { Text(stringResource(R.string.book_lock_unlock_chapter)) }
                }
            }
        }
    }
}

@Composable
private fun LockRow(text: String, note: String?, onClick: (() -> Unit)?, onUnlock: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = onClick != null) { onClick?.invoke() }.heightIn(min = 48.dp).padding(start = AppSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HugeIcons.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onUnlock) { Text(stringResource(R.string.book_lock_unlock)) }
    }
}

/** 锁加上时编辑器里还有没保存的修改：修改留在草稿里，这一行给出解锁并保存、复制、丢弃。 */
@Composable
internal fun LockedDraftBar(onUnlockAndSave: () -> Unit, onCopy: () -> Unit, onDiscard: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.book_lock_draft_kept),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUnlockAndSave) { Text(stringResource(R.string.book_lock_unlock_and_save)) }
            TextButton(onClick = onCopy) { Text(stringResource(R.string.copy)) }
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.book_lock_discard)) }
        }
    }
}
